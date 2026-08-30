package com.droidnova.allfilereader.data.powerpoint

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.SecureRandom
import java.util.Locale

internal enum class LegacyPptErrorCode {
    SOURCE_MISSING, PERMISSION_DENIED, UNSUPPORTED_SOURCE, INPUT_EMPTY,
    INVALID_OLE_SIGNATURE, TEMP_COPY_FAILED, INSUFFICIENT_STORAGE,
    FILE_PROVIDER_FAILED, UNEXPECTED_IO
}

internal sealed interface LegacyPptResolution {
    data class Valid(val source: LegacyPptSource) : LegacyPptResolution
    data class Error(val code: LegacyPptErrorCode) : LegacyPptResolution
}

internal sealed interface LegacyPptSource {
    val scheme: String
    data class Content(val uri: Uri) : LegacyPptSource { override val scheme = "content" }
    data class LocalFile(val file: File, override val scheme: String) : LegacyPptSource
}

internal sealed interface LegacyPptShareResult {
    data class Ready(val uri: Uri) : LegacyPptShareResult
    data class Error(val code: LegacyPptErrorCode) : LegacyPptShareResult
}

/** Resolves, validates and safely shares legacy PowerPoint sources without loading them whole. */
internal class LegacyPptSourceResolver(private val context: Context) {
    private val resolver = context.contentResolver
    private val shareDirectory = File(context.cacheDir, SHARE_DIRECTORY)
    private val debugLogging = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init { cleanupExpiredShares() }

    fun resolve(location: String, detectedExtension: String?): LegacyPptResolution {
        val extension = detectedExtension?.lowercase(Locale.ROOT)
        if (extension != "ppt") return reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "resolve", "unknown", null, extension)
        val parsed = runCatching { Uri.parse(location) }.getOrNull()
            ?: return reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "parse", "unknown", null, extension)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        trace("stage=resolve scheme=${scheme ?: "path"} authority=${safeAuthority(parsed)} sourceType=${sourceType(scheme)} extension=$extension")
        return when (scheme) {
            "content" -> resolveContent(parsed, extension)
            "file" -> resolveLocal(runCatching { File(URI(location)) }.getOrNull(), "file", extension)
            null, "" -> resolveLocal(File(location), "path", extension)
            else -> reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "resolve", scheme, parsed, extension)
        }
    }

    fun prepareForExternalOpen(source: LegacyPptSource): LegacyPptShareResult = when (source) {
        is LegacyPptSource.Content -> LegacyPptShareResult.Ready(source.uri)
        is LegacyPptSource.LocalFile -> copyForSharing(source)
    }

    private fun resolveContent(uri: Uri, extension: String): LegacyPptResolution {
        if (uri.authority.isNullOrBlank()) return reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "open", "content", uri, extension)
        return try {
            val input = resolver.openInputStream(uri)
                ?: return reject(LegacyPptErrorCode.SOURCE_MISSING, "open", "content", uri, extension)
            val inspection = input.use(::inspect)
            trace("stage=signature scheme=content authority=${safeAuthority(uri)} sourceType=content extension=$extension bytesRead=${inspection.bytesRead} code=${inspection.code ?: "OK"}")
            inspection.code?.let { LegacyPptResolution.Error(it) } ?: LegacyPptResolution.Valid(LegacyPptSource.Content(uri))
        } catch (error: SecurityException) {
            reject(LegacyPptErrorCode.PERMISSION_DENIED, "open", "content", uri, extension, error)
        } catch (error: FileNotFoundException) {
            reject(LegacyPptErrorCode.SOURCE_MISSING, "open", "content", uri, extension, error)
        } catch (error: IOException) {
            reject(LegacyPptErrorCode.UNEXPECTED_IO, "read", "content", uri, extension, error)
        }
    }

    private fun resolveLocal(candidate: File?, scheme: String, extension: String): LegacyPptResolution {
        val file = try { candidate?.canonicalFile } catch (error: IOException) {
            return reject(LegacyPptErrorCode.UNEXPECTED_IO, "canonicalize", scheme, null, extension, error)
        } ?: return reject(LegacyPptErrorCode.SOURCE_MISSING, "canonicalize", scheme, null, extension)
        trace("stage=resolve scheme=$scheme authority=none sourceType=local extension=$extension exists=${file.exists()} readable=${file.canRead()}")
        if (!file.exists()) return reject(LegacyPptErrorCode.SOURCE_MISSING, "stat", scheme, null, extension)
        if (!file.isFile) return reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "stat", scheme, null, extension)
        if (!file.canRead()) return reject(LegacyPptErrorCode.PERMISSION_DENIED, "stat", scheme, null, extension)
        if (!file.extension.equals("ppt", true)) return reject(LegacyPptErrorCode.UNSUPPORTED_SOURCE, "extension", scheme, null, extension)
        return try {
            val inspection = file.inputStream().use(::inspect)
            trace("stage=signature scheme=$scheme authority=none sourceType=local extension=$extension bytesRead=${inspection.bytesRead} code=${inspection.code ?: "OK"}")
            inspection.code?.let { LegacyPptResolution.Error(it) } ?: LegacyPptResolution.Valid(LegacyPptSource.LocalFile(file, scheme))
        } catch (error: SecurityException) {
            reject(LegacyPptErrorCode.PERMISSION_DENIED, "read", scheme, null, extension, error)
        } catch (error: IOException) {
            reject(LegacyPptErrorCode.UNEXPECTED_IO, "read", scheme, null, extension, error)
        }
    }

    private fun copyForSharing(source: LegacyPptSource.LocalFile): LegacyPptShareResult {
        val sourceLength = source.file.length()
        if (sourceLength <= 0L) return LegacyPptShareResult.Error(LegacyPptErrorCode.INPUT_EMPTY)
        if (sourceLength > MAX_SHARE_BYTES) return LegacyPptShareResult.Error(LegacyPptErrorCode.TEMP_COPY_FAILED)
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) return LegacyPptShareResult.Error(LegacyPptErrorCode.TEMP_COPY_FAILED)
        if (shareDirectory.usableSpace < sourceLength + SPACE_MARGIN_BYTES) return LegacyPptShareResult.Error(LegacyPptErrorCode.INSUFFICIENT_STORAGE)
        val target = File(shareDirectory, "${randomToken()}.ppt")
        try {
            source.file.inputStream().use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SHARE_BYTES) throw ShareLimitException()
                    output.write(buffer, 0, read)
                }
                if (total == 0L) throw EmptyShareException()
            }}
            val uri = FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX", target)
            if (uri.scheme != "content") throw IllegalStateException("FileProvider returned non-content URI")
            trace("stage=share_ready scheme=content authority=${safeAuthority(uri)} sourceType=cache extension=ppt bytesRead=${target.length()} code=OK")
            return LegacyPptShareResult.Ready(uri)
        } catch (error: EmptyShareException) {
            target.delete(); return LegacyPptShareResult.Error(LegacyPptErrorCode.INPUT_EMPTY)
        } catch (error: ShareLimitException) {
            target.delete(); return LegacyPptShareResult.Error(LegacyPptErrorCode.TEMP_COPY_FAILED)
        } catch (error: IllegalArgumentException) {
            target.delete(); traceFailure("provider", source, LegacyPptErrorCode.FILE_PROVIDER_FAILED, error); return LegacyPptShareResult.Error(LegacyPptErrorCode.FILE_PROVIDER_FAILED)
        } catch (error: SecurityException) {
            target.delete(); traceFailure("copy", source, LegacyPptErrorCode.PERMISSION_DENIED, error); return LegacyPptShareResult.Error(LegacyPptErrorCode.PERMISSION_DENIED)
        } catch (error: IOException) {
            target.delete(); traceFailure("copy", source, LegacyPptErrorCode.TEMP_COPY_FAILED, error); return LegacyPptShareResult.Error(LegacyPptErrorCode.TEMP_COPY_FAILED)
        } catch (error: RuntimeException) {
            target.delete(); traceFailure("provider", source, LegacyPptErrorCode.FILE_PROVIDER_FAILED, error); return LegacyPptShareResult.Error(LegacyPptErrorCode.FILE_PROVIDER_FAILED)
        }
    }

    private fun cleanupExpiredShares() {
        runCatching {
            val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
            shareDirectory.listFiles().orEmpty().asSequence().filter { it.isFile && it.extension.equals("ppt", true) && it.lastModified() < cutoff }
                .take(MAX_CLEANUP_FILES).forEach(File::delete)
        }
    }

    private fun randomToken(): String = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private fun traceFailure(stage: String, source: LegacyPptSource.LocalFile, code: LegacyPptErrorCode, error: Throwable) =
        trace("stage=$stage scheme=${source.scheme} authority=none sourceType=local extension=ppt exception=${error.javaClass.simpleName} code=$code")

    private fun reject(code: LegacyPptErrorCode, stage: String, scheme: String?, uri: Uri?, extension: String?, error: Throwable? = null): LegacyPptResolution.Error {
        trace("stage=$stage scheme=${scheme ?: "unknown"} authority=${safeAuthority(uri)} sourceType=${sourceType(scheme)} extension=${extension ?: "unknown"} exception=${error?.javaClass?.simpleName ?: "none"} code=$code")
        return LegacyPptResolution.Error(code)
    }

    companion object {
        const val SHARE_DIRECTORY = "legacy_ppt_share"
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".legacy-ppt-files"
        const val MAX_SHARE_BYTES = 50L * 1024L * 1024L
        private const val SPACE_MARGIN_BYTES = 1L * 1024L * 1024L
        private const val SHARE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val MAX_CLEANUP_FILES = 32
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        internal data class Inspection(val bytesRead: Int, val code: LegacyPptErrorCode?)
        internal fun inspect(input: InputStream): Inspection {
            val header = ByteArray(OLE_SIGNATURE.size)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read <= 0) break
                count += read
            }
            val code = when {
                count == 0 -> LegacyPptErrorCode.INPUT_EMPTY
                count != OLE_SIGNATURE.size || !header.contentEquals(OLE_SIGNATURE) -> LegacyPptErrorCode.INVALID_OLE_SIGNATURE
                else -> null
            }
            return Inspection(count, code)
        }

        private fun safeAuthority(uri: Uri?): String = uri?.authority?.take(80)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "none"
        private fun sourceType(scheme: String?): String = when (scheme) { "content" -> "content"; "file", null, "" -> "local"; else -> "unsupported" }
    }

    private fun trace(message: String) { if (debugLogging) Log.d("LegacyPptOpen", message) }

    private class ShareLimitException : IOException()
    private class EmptyShareException : IOException()
}
