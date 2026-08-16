package com.droidnova.allfilereader.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentIds
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import com.droidnova.allfilereader.data.paging.LocalMetadataPagingConfig
import com.droidnova.allfilereader.data.paging.SnapshotPagingSource
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class MediaStoreDocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : DocumentRepository {
    private val scanMutex = Mutex()
    private var cache: List<DocumentFile>? = null
    private val _documents = MutableStateFlow<List<DocumentFile>>(emptyList())
    override val documents: StateFlow<List<DocumentFile>> = _documents.asStateFlow()
    private val knownDocuments = LinkedHashMap<String, DocumentFile>()
    private val pagingRefreshRequested = AtomicBoolean(false)

    override fun pagedDocuments(category: DocumentCategory?): Flow<PagingData<DocumentFile>> = Pager(
        config = LocalMetadataPagingConfig,
        pagingSourceFactory = {
            SnapshotPagingSource {
                getDocuments(pagingRefreshRequested.getAndSet(false))
                    .asSequence()
                    .filter { category == null || it.category == category }
                    .distinctBy(DocumentFile::id)
                    .sortedWith(compareByDescending<DocumentFile> { it.lastModifiedEpochMillis }.thenByDescending { it.id })
                    .toList()
            }
        }
    ).flow

    override fun requestPagingRefresh() { pagingRefreshRequested.set(true) }

    override suspend fun getDocuments(forceRefresh: Boolean): List<DocumentFile> = scanMutex.withLock {
        if (!forceRefresh) cache?.let { return@withLock it }
        val result = withContext(Dispatchers.IO) { scan() }
        cache = result
        synchronized(knownDocuments) {
            knownDocuments.putAll(result.associateBy(DocumentFile::id))
        }
        _documents.value = result
        result
    }

    override fun rememberDocument(document: DocumentFile) {
        synchronized(knownDocuments) { knownDocuments[document.id] = document }
    }

    override suspend fun resolveDocument(id: String): DocumentFile? {
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

    private suspend fun scan(): List<DocumentFile> {
        val found = LinkedHashMap<String, DocumentFile>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<File>()
        storageRoots().forEach(queue::add)
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = queue.removeFirst()
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical) || isRestricted(canonical)) continue
            val children = try { directory.listFiles() } catch (_: SecurityException) { null } ?: continue
            for (file in children) {
                coroutineContext.ensureActive()
                if (file.isDirectory) {
                    if (!file.isHidden) queue.add(file)
                    continue
                }
                val extension = DocumentClassifier.extensionOf(file.name)
                val category = DocumentClassifier.classify(null, extension)
                if (category !in supported) continue
                val path = runCatching { file.canonicalPath }.getOrNull() ?: continue
                found[path] = DocumentFile(
                    id = DocumentIds.fromStorageLocation(path), displayName = file.name, uri = file.toURI().toString(),
                    mimeType = null, extension = extension, sizeBytes = runCatching { file.length() }.getOrDefault(-1),
                    lastModifiedEpochMillis = runCatching { file.lastModified() }.getOrDefault(0),
                    category = category, isBookmarked = false
                )
            }
        }
        return found.values.sortedWith(compareByDescending<DocumentFile> { it.lastModifiedEpochMillis }.thenByDescending { it.id })
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

    private companion object {
        val supported = setOf(DocumentCategory.Pdf, DocumentCategory.Word, DocumentCategory.Excel,
            DocumentCategory.PowerPoint, DocumentCategory.Text)
    }
}
class DocumentAccessException(cause: Throwable) : Exception(cause)
