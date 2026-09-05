package com.droidnova.allfilereader.navigation

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.droidnova.allfilereader.BuildConfig
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentIds
import com.droidnova.allfilereader.domain.reader.DocumentReaderDestination
import com.droidnova.allfilereader.domain.reader.DocumentReaderResolver
import com.droidnova.allfilereader.domain.reader.DocumentOpenResult
import java.io.FileNotFoundException

enum class IncomingError { MissingUri, AmbiguousUri, Unsupported, FormatMismatch, AccessDenied }
sealed interface IncomingResolution {
    data class Ready(val document: DocumentFile, val destination: DocumentReaderDestination?) : IncomingResolution
    data class Error(val reason: IncomingError) : IncomingResolution
}

/** Strict boundary between untrusted Android intents/providers and the existing readers. */
class IncomingDocumentResolver(private val contentResolver: ContentResolver) {
    fun resolve(request: IncomingRequest): IncomingResolution {
        val uri = request.uri
        trace("source_resolution stage=start scheme=${uri.scheme ?: "none"} authority=${safeAuthority(uri)} read_grant=${request.readGrantFlags != 0}")
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            trace("source_resolution stage=uri_validation scheme=${uri.scheme ?: "none"} authority=${safeAuthority(uri)} code=UNSUPPORTED_URI")
            return IncomingResolution.Error(IncomingError.Unsupported)
        }
        return try {
            val metadata = query(uri)
            // Opening a descriptor verifies the caller/provider's current read grant without retaining it.
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length == 0L) { /* Readers retain their accurate empty-file states. */ }
            } ?: throw FileNotFoundException()
            val reported = contentResolver.getType(uri)
            val specificMime = DocumentClassifier.normalizeMimeType(reported)
                ?: DocumentClassifier.normalizeMimeType(request.declaredMimeType)
            val extension = DocumentClassifier.extensionOf(metadata.name ?: "")
            // The explicit filename extension wins when provider MIME metadata conflicts. Complete
            // signature/package validation belongs to the selected reader's existing preflight.
            val classification = DocumentClassifier.classifyMetadata(specificMime, extension)
            val category = classification.category ?: return IncomingResolution.Error(IncomingError.Unsupported)
            val document = DocumentFile(
                id = DocumentIds.fromStorageLocation(uri.toString()),
                displayName = sanitizeName(metadata.name) ?: "Document",
                uri = uri.toString(), mimeType = specificMime, extension = extension,
                sizeBytes = metadata.size ?: -1L, lastModifiedEpochMillis = 0L,
                category = category, isBookmarked = false
            )
            val destination = (DocumentReaderResolver.resolve(classification) as? DocumentOpenResult.Internal)?.destination
            trace("source_resolution stage=complete selected_reader=${destination?.name ?: "unsupported"} code=READY")
            IncomingResolution.Ready(document, destination)
        } catch (error: SecurityException) {
            trace("source_resolution stage=read exception=${error.javaClass.simpleName} code=ACCESS_DENIED")
            IncomingResolution.Error(IncomingError.AccessDenied)
        } catch (error: FileNotFoundException) {
            trace("source_resolution stage=read exception=${error.javaClass.simpleName} code=SOURCE_MISSING")
            IncomingResolution.Error(IncomingError.AccessDenied)
        } catch (error: RuntimeException) {
            trace("source_resolution stage=provider exception=${error.javaClass.simpleName} code=PROVIDER_FAILURE")
            IncomingResolution.Error(IncomingError.AccessDenied)
        }
    }

    private data class Metadata(val name: String?, val size: Long?)
    private fun query(uri: Uri): Metadata {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, PROJECTION, null, null, null)
            if (cursor == null || !cursor.moveToFirst()) return Metadata(null, null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            return Metadata(
                cursor.takeIf { nameIndex >= 0 && !it.isNull(nameIndex) }?.getString(nameIndex),
                cursor.takeIf { sizeIndex >= 0 && !it.isNull(sizeIndex) }?.getLong(sizeIndex)?.takeIf { it >= 0 }
            )
        } finally { cursor?.close() }
    }

    private fun sanitizeName(value: String?): String? = value?.replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_")?.trim()?.take(160)?.takeIf { it.isNotEmpty() }

    private companion object {
        val PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }

    private fun safeAuthority(uri: Uri) = uri.authority?.take(80)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "none"
    private fun trace(message: String) { if (BuildConfig.DEBUG) Log.d("ExternalDocumentOpen", message) }
}
