package com.droidnova.allfilereader.navigation

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.DocumentIds
import com.droidnova.allfilereader.domain.reader.DocumentReaderDestination
import com.droidnova.allfilereader.domain.reader.DocumentReaderResolver
import java.io.FileNotFoundException
import java.util.Locale

enum class IncomingError { MissingUri, Unsupported, FormatMismatch, AccessDenied }
sealed interface IncomingResolution {
    data class Ready(val document: DocumentFile, val destination: DocumentReaderDestination) : IncomingResolution
    data class Error(val reason: IncomingError) : IncomingResolution
}

/** Strict boundary between untrusted Android intents/providers and the existing readers. */
class IncomingDocumentResolver(private val contentResolver: ContentResolver) {
    fun resolve(intent: Intent): IncomingResolution {
        if (intent.action != Intent.ACTION_VIEW) return IncomingResolution.Error(IncomingError.MissingUri)
        val uri = intent.data ?: return IncomingResolution.Error(IncomingError.MissingUri)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            return IncomingResolution.Error(IncomingError.MissingUri)
        }
        return try {
            val metadata = query(uri)
            // Opening a descriptor verifies the caller/provider's current read grant without retaining it.
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length == 0L) { /* Readers retain their accurate empty-file states. */ }
            } ?: throw FileNotFoundException()
            val declared = normalizeMime(intent.type)
            val reported = normalizeMime(contentResolver.getType(uri))
            if (declared != null && reported != null && !isGenericMime(declared) && !isGenericMime(reported) && declared != reported) {
                return IncomingResolution.Error(IncomingError.FormatMismatch)
            }
            val specificMime = reported?.takeUnless(::isGenericMime) ?: declared?.takeUnless(::isGenericMime)
            if (specificMime != null && specificMime !in DocumentClassifier.incomingMimeTypes) {
                return IncomingResolution.Error(IncomingError.Unsupported)
            }
            val extension = DocumentClassifier.extensionOf(metadata.name ?: "")
            if (extension != null && extension !in DocumentClassifier.incomingExtensions) {
                return IncomingResolution.Error(if (specificMime != null) IncomingError.FormatMismatch else IncomingError.Unsupported)
            }
            if (specificMime == null && extension == null) return IncomingResolution.Error(IncomingError.Unsupported)
            if (specificMime != null && extension != null && !contractsMatch(specificMime, extension)) {
                return IncomingResolution.Error(IncomingError.FormatMismatch)
            }
            if (specificMime == null && extension != null && !boundedPreflight(uri, extension)) {
                return IncomingResolution.Error(IncomingError.FormatMismatch)
            }
            val category = DocumentClassifier.classify(specificMime, extension)
            val document = DocumentFile(
                id = DocumentIds.fromStorageLocation(uri.toString()),
                displayName = sanitizeName(metadata.name) ?: "Document",
                uri = uri.toString(), mimeType = specificMime, extension = extension,
                sizeBytes = metadata.size ?: -1L, lastModifiedEpochMillis = 0L,
                category = category, isBookmarked = false
            )
            val destination = DocumentReaderResolver.resolve(document)
            if (destination == DocumentReaderDestination.Unsupported || destination == DocumentReaderDestination.LegacyWord) {
                IncomingResolution.Error(IncomingError.Unsupported)
            } else IncomingResolution.Ready(document, destination)
        } catch (_: SecurityException) {
            IncomingResolution.Error(IncomingError.AccessDenied)
        } catch (_: FileNotFoundException) {
            IncomingResolution.Error(IncomingError.AccessDenied)
        } catch (_: RuntimeException) {
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

    private fun normalizeMime(value: String?) = value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    private fun isGenericMime(value: String) = value == "application/octet-stream" || value == "*/*"
    private fun sanitizeName(value: String?): String? = value?.replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_")?.trim()?.take(160)?.takeIf { it.isNotEmpty() }

    private fun contractsMatch(mime: String, extension: String): Boolean = when (mime) {
        "application/pdf" -> extension == "pdf"
        "text/plain" -> extension == "txt"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> extension == "docx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> extension == "xlsx"
        "application/vnd.ms-excel" -> extension == "xls"
        "application/vnd.ms-excel.sheet.macroenabled.12" -> extension == "xlsm"
        "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> extension == "xlsb"
        "application/vnd.oasis.opendocument.spreadsheet" -> extension == "ods"
        "text/csv" -> extension == "csv"
        "text/tab-separated-values" -> extension == "tsv"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> extension == "pptx"
        "application/vnd.ms-powerpoint" -> extension == "ppt"
        else -> false
    }

    /** Small signature gate only; complete archive/size validation remains in each destination reader. */
    private fun boundedPreflight(uri: Uri, extension: String): Boolean {
        val header = ByteArray(4096)
        val count = contentResolver.openInputStream(uri)?.use { it.read(header) } ?: return false
        if (count <= 0) return true // Preserve the destination reader's format-specific empty state.
        val zip = count >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        val ole = count >= 8 && header.copyOfRange(0, 8).contentEquals(OLE)
        return when (extension) {
            "pdf" -> count >= 5 && String(header, 0, 5, Charsets.US_ASCII) == "%PDF-"
            "docx", "xlsx", "xlsm", "xlsb", "ods", "pptx" -> zip
            "xls", "ppt" -> ole
            "txt", "csv", "tsv" -> header.take(count).none { it == 0.toByte() }
            else -> false
        }
    }

    private companion object {
        val PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
