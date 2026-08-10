package com.droidnova.allfilereader.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.droidnova.allfilereader.domain.model.SafEntry
import com.droidnova.allfilereader.domain.repository.FolderAccessRevokedException
import com.droidnova.allfilereader.domain.repository.FolderRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafFolderRepository @Inject constructor(
    private val contentResolver: ContentResolver
) : FolderRepository {
    override suspend fun persistedFolders(): List<SafEntry> = withContext(Dispatchers.IO) {
        contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .mapNotNull { permission -> queryEntry(permission.uri) }
            .distinctBy(SafEntry::uri)
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    override suspend fun children(folderUri: String): List<SafEntry> = withContext(Dispatchers.IO) {
        try {
            val parent = Uri.parse(folderUri)
            val treeDocumentId = runCatching { DocumentsContract.getDocumentId(parent) }
                .getOrElse { DocumentsContract.getTreeDocumentId(parent) }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, treeDocumentId)
            contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex)?.trim().orEmpty().ifBlank { id }
                        val mime = cursor.getString(mimeIndex)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(parent, id)
                        add(SafEntry(id, name, uri.toString(), mime == DocumentsContract.Document.MIME_TYPE_DIR, mime,
                            cursor.nullableLong(sizeIndex), cursor.nullableLong(modifiedIndex)))
                    }
                }
            }.orEmpty().distinctBy(SafEntry::uri).sortedWith(
                compareByDescending<SafEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: SecurityException) {
            throw FolderAccessRevokedException(exception)
        } catch (exception: RuntimeException) {
            throw FolderAccessRevokedException(exception)
        }
    }

    override suspend fun persistReadPermission(folderUri: String): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.takePersistableUriPermission(Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun queryEntry(uri: Uri): SafEntry? = try {
        contentResolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) ?: return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))?.trim().orEmpty().ifBlank { id }
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            val size = cursor.nullableLong(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE))
            val modified = cursor.nullableLong(cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            SafEntry(id, name, uri.toString(), true, mime, size, modified)
        }
    } catch (_: SecurityException) { null } catch (_: RuntimeException) { null }

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
