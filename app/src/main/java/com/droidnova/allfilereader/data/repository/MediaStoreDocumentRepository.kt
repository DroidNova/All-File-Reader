package com.droidnova.allfilereader.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.repository.DocumentRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MediaStoreDocumentRepository @Inject constructor(
    private val contentResolver: ContentResolver
) : DocumentRepository {
    private val scanMutex = Mutex()
    private var cachedDocuments: List<DocumentFile>? = null
    private var cacheIncludesImages = false
    private val _documents = MutableStateFlow<List<DocumentFile>>(emptyList())
    override val documents: StateFlow<List<DocumentFile>> = _documents.asStateFlow()

    override suspend fun getDocuments(
        includeImages: Boolean,
        forceRefresh: Boolean
    ): List<DocumentFile> = scanMutex.withLock {
        val cached = cachedDocuments
        if (!forceRefresh && cached != null && (!includeImages || cacheIncludesImages)) {
            return@withLock if (includeImages) cached else cached.filterNot {
                it.category == DocumentCategory.Image
            }
        }

        val documents = withContext(Dispatchers.IO) { queryDocuments(includeImages) }
        cachedDocuments = documents
        cacheIncludesImages = includeImages
        _documents.value = documents
        documents
    }

    private fun queryDocuments(includeImages: Boolean): List<DocumentFile> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val mediaTypes = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
            if (includeImages) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        }
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                mediaTypes.joinToString(" OR ") {
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                }
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                mediaTypes.map(Int::toString).toTypedArray()
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RESULTS)
        }

        try {
            return contentResolver.query(collection, columns, queryArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val displayName = cursor.getString(nameColumn)?.trim().orEmpty()
                        if (displayName.isEmpty() || isTemporary(displayName)) continue

                        val mimeType = cursor.getString(mimeColumn)?.takeIf(String::isNotBlank)
                        val extension = DocumentClassifier.extensionOf(displayName)
                        val category = DocumentClassifier.classify(mimeType, extension)
                        if (!includeImages && category == DocumentCategory.Image) continue

                        val uri = ContentUris.withAppendedId(collection, id).toString()
                        val size = if (cursor.isNull(sizeColumn)) -1L else cursor.getLong(sizeColumn)
                        val modifiedSeconds = if (cursor.isNull(modifiedColumn)) {
                            0L
                        } else {
                            cursor.getLong(modifiedColumn)
                        }
                        add(
                            DocumentFile(
                                id = "external:$id",
                                displayName = displayName,
                                uri = uri,
                                mimeType = mimeType,
                                extension = extension,
                                sizeBytes = size,
                                lastModifiedEpochMillis = TimeUnit.SECONDS.toMillis(modifiedSeconds),
                                category = category,
                                isBookmarked = false
                            )
                        )
                    }
                }
            }.orEmpty()
                .distinctBy(DocumentFile::uri)
                .sortedByDescending(DocumentFile::lastModifiedEpochMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (securityException: SecurityException) {
            throw DocumentAccessException(securityException)
        } catch (runtimeException: RuntimeException) {
            throw DocumentAccessException(runtimeException)
        }
    }

    private fun isTemporary(displayName: String): Boolean {
        val normalized = displayName.lowercase(java.util.Locale.ROOT)
        return normalized.startsWith('.') || TEMPORARY_SUFFIXES.any(normalized::endsWith)
    }

    private companion object {
        const val MAX_RESULTS = 500
        val TEMPORARY_SUFFIXES = setOf(".tmp", ".temp", ".part", ".crdownload")
    }
}

class DocumentAccessException(cause: Throwable) : Exception(cause)
