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
import java.util.Locale

enum class IncomingError { MissingUri, AmbiguousUri, Unsupported, FormatMismatch, AccessDenied }
sealed interface IncomingResolution {
    data class Ready(val document: DocumentFile, val destination: DocumentReaderDestination) : IncomingResolution
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
            val declared = normalizeMime(request.declaredMimeType)
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
            val decision = DocumentReaderResolver.resolve(document)
            if (decision is DocumentOpenResult.Internal) {
                val destination = decision.destination
                trace("source_resolution stage=complete selected_reader=${destination.name} code=READY")
                IncomingResolution.Ready(document, destination)
            } else IncomingResolution.Error(IncomingError.Unsupported)
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
            "xls" -> ole
            "txt", "csv", "tsv" -> header.take(count).none { it == 0.toByte() }
            else -> false
        }
    }

    private companion object {
        val PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }

    private fun safeAuthority(uri: Uri) = uri.authority?.take(80)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "none"
    private fun trace(message: String) { if (BuildConfig.DEBUG) Log.d("ExternalDocumentOpen", message) }
}
