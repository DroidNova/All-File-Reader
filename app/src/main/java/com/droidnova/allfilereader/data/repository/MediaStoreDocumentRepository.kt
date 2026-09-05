package com.droidnova.allfilereader.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentIds
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.domain.repository.FavoritesRepository
import com.droidnova.allfilereader.data.paging.LocalMetadataPagingConfig
import com.droidnova.allfilereader.data.paging.SnapshotPagingSource
import com.droidnova.allfilereader.data.permission.MediaPermissionManager
import androidx.paging.Pager
import androidx.paging.PagingData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class MediaStoreDocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: MediaPermissionManager,
    private val favoritesRepository: FavoritesRepository
) : DocumentRepository {
    private val scanMutex = Mutex()
    private var cache: List<DocumentFile>? = null
    private val _documents = MutableStateFlow<List<DocumentFile>>(emptyList())
    override val documents: StateFlow<List<DocumentFile>> = _documents.asStateFlow()
    private val knownDocuments = LinkedHashMap<String, DocumentFile>()
    private val pagingRefreshRequested = AtomicBoolean(false)
    private val scanGeneration = AtomicLong(0)
    private var authoritativeEvidence: DocumentSnapshotEvidence? = null

    override fun pagedDocuments(category: DocumentCategory?): Flow<PagingData<DocumentFile>> = Pager(
        config = LocalMetadataPagingConfig,
        pagingSourceFactory = {
            SnapshotPagingSource {
                getDocuments(pagingRefreshRequested.getAndSet(false))
                    .asSequence()
                    .filter(DocumentClassifier::isVisibleDocument)
                    .filter { category == null || it.category == category }
                    .distinctBy(DocumentFile::id)
                    .sortedWith(compareByDescending<DocumentFile> { it.lastModifiedEpochMillis }.thenByDescending { it.id })
                    .toList()
            }
        }
    ).flow

    override fun requestPagingRefresh() { pagingRefreshRequested.set(true) }

    override suspend fun getDocuments(forceRefresh: Boolean): List<DocumentFile> {
        val generation = scanGeneration.incrementAndGet()
        return scanMutex.withLock {
        if (!permissionManager.isGranted()) {
            clearSnapshots()
            throw SecurityException("Storage access is required")
        }
        if (!forceRefresh) cache?.let { if(BuildConfig.DEBUG)Log.d(TAG,"cache=hit count=${it.size}");return@withLock it }
        if(BuildConfig.DEBUG)Log.d(TAG,"scan=start generation=$generation reason=${if(forceRefresh)"explicit_refresh" else "initial_load"}")
        val favoriteIdsBeforeScan = favoritesRepository.favoriteIds.first()
        val outcome = try {
            withContext(Dispatchers.IO) { scan() }
        } catch (cancelled: CancellationException) {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=cancelled reconciliation=skipped")
            throw cancelled
        } catch (error: Exception) {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=failure code=${error.javaClass.simpleName} reconciliation=skipped")
            throw error
        }
        if (!permissionManager.isGranted()) {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=permission_revoked reconciliation=skipped")
            clearSnapshots()
            throw SecurityException("Storage access was revoked")
        }
        if (!outcome.coverage.completed) {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=partial completedRoots=${outcome.coverage.completedRootIds.size} unavailableRoots=${outcome.coverage.unavailableRootIds.size} reconciliation=skipped")
            throw DocumentAccessException(java.io.IOException("Document scan was incomplete"))
        }
        val result = outcome.documents
        val currentEvidence = DocumentSnapshotEvidence(result.mapTo(hashSetOf(), DocumentFile::id), outcome.rootByDocumentId)
        val deletedFavoriteIds = confirmedDeletedFavoriteIds(
            favoriteIdsBeforeScan,
            authoritativeEvidence,
            currentEvidence,
            outcome.coverage
        )
        if (generation != scanGeneration.get() || !permissionManager.isGranted()) {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=stale reconciliation=skipped")
            throw SecurityException("Storage access changed during reconciliation")
        }
        val removed = favoritesRepository.removeFavorites(deletedFavoriteIds).getOrElse {
            if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=authoritative reconciliation=write_failed considered=${favoriteIdsBeforeScan.size}")
            throw DocumentAccessException(it)
        }
        cache = result
        authoritativeEvidence = currentEvidence
        synchronized(knownDocuments) {
            knownDocuments.putAll(result.associateBy(DocumentFile::id))
        }
        _documents.value = result
        if(BuildConfig.DEBUG)Log.d(TAG,"scan=end generation=$generation result=authoritative completedRoots=${outcome.coverage.completedRootIds.size} unavailableRoots=0 considered=${favoriteIdsBeforeScan.size} confirmedDeleted=${deletedFavoriteIds.size} removed=$removed count=${result.size}")
        result
        }
    }

    override fun rememberDocument(document: DocumentFile) {
        if (!permissionManager.isGranted()) return
        synchronized(knownDocuments) { knownDocuments[document.id] = document }
    }

    override suspend fun resolveDocument(id: String): DocumentFile? {
        if (!permissionManager.isGranted()) return null
        val known = synchronized(knownDocuments) { knownDocuments[id] }
            ?: getDocuments(forceRefresh = false).firstOrNull { it.id == id }
        return withContext(Dispatchers.IO) {
            known?.takeIf { document ->
            runCatching {
                val uri = java.net.URI(document.uri)
                uri.scheme != "file" || File(uri).isFile
            }.getOrDefault(true)
            }
        }
    }

    private data class ScanOutcome(
        val documents: List<DocumentFile>,
        val rootByDocumentId: Map<String, String>,
        val coverage: ScanCoverage
    )

    private suspend fun scan(): ScanOutcome {
        val found = LinkedHashMap<String, DocumentFile>()
        val rootsByPath = LinkedHashMap<String, String>()
        val completedRoots = linkedSetOf<String>()
        val unavailableRoots = linkedSetOf<String>()
        var rootDiscoveryComplete = true
        for (root in storageRoots()) {
            val rootPath = runCatching { root.canonicalPath }.getOrNull()
            if (rootPath == null) { rootDiscoveryComplete = false; continue }
            val rootId = privateRootId(rootPath)
            val visited = HashSet<String>()
            val queue = ArrayDeque<File>().apply { add(root) }
            var complete = true
            while (queue.isNotEmpty()) {
                coroutineContext.ensureActive()
                if (!permissionManager.isGranted()) throw SecurityException("Storage access was revoked")
                val directory = queue.removeFirst()
                val canonical = runCatching { directory.canonicalPath }.getOrNull()
                if (canonical == null) { complete = false; continue }
                if (!visited.add(canonical) || isRestricted(canonical)) continue
                val children = try { directory.listFiles() } catch (_: SecurityException) { null }
                if (children == null) { complete = false; continue }
                for (file in children) {
                    coroutineContext.ensureActive()
                    if (file.isDirectory) {
                        if (!file.isHidden) queue.add(file)
                        continue
                    }
                    val extension = DocumentClassifier.extensionOf(file.name)
                    val classification = DocumentClassifier.classifyMetadata(null, extension)
                    if (!DocumentClassifier.isVisibleDocument(classification)) continue
                    val category = classification.category ?: continue
                    val path = runCatching { file.canonicalPath }.getOrNull()
                    if (path == null) { complete = false; continue }
                    found[path] = DocumentFile(
                        id = DocumentIds.fromStorageLocation(path), displayName = file.name, uri = file.toURI().toString(),
                        mimeType = null, extension = extension, sizeBytes = runCatching { file.length() }.getOrDefault(-1),
                        lastModifiedEpochMillis = runCatching { file.lastModified() }.getOrDefault(0),
                        category = category, isBookmarked = false
                    )
                    rootsByPath[path] = rootId
                }
            }
            if (complete) completedRoots += rootId else unavailableRoots += rootId
        }
        val documents = found.values.sortedWith(compareByDescending<DocumentFile> { it.lastModifiedEpochMillis }.thenByDescending { it.id })
        val rootsById = found.map { (path, document) -> document.id to rootsByPath.getValue(path) }.toMap()
        return ScanOutcome(
            documents,
            rootsById,
            ScanCoverage(completedRoots, unavailableRoots, permissionManager.isGranted(), rootDiscoveryComplete && unavailableRoots.isEmpty())
        )
    }

    private fun storageRoots(): List<File> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return context.getSystemService(StorageManager::class.java).storageVolumes
                .mapNotNull { it.directory }.filter { it.exists() && it.canRead() }
        }
        return buildList {
            add(Environment.getExternalStorageDirectory())
            context.getExternalFilesDirs(null).mapNotNull { it?.parentFile?.parentFile?.parentFile?.parentFile }
                .forEach(::add)
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    private fun isRestricted(path: String): Boolean = path.contains("/Android/data") || path.contains("/Android/obb")

    override fun clearSnapshots() {
        cache = null
        authoritativeEvidence = null
        synchronized(knownDocuments) { knownDocuments.clear() }
        _documents.value = emptyList()
        pagingRefreshRequested.set(false)
    }

    private companion object { const val TAG = "DocumentSessionCache" }

}
private fun privateRootId(canonicalPath:String):String=MessageDigest.getInstance("SHA-256").digest(canonicalPath.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
class DocumentAccessException(cause: Throwable) : Exception(cause)
