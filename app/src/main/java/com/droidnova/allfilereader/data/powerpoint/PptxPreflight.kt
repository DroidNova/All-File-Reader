package com.droidnova.allfilereader.data.powerpoint

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed class PptxPreflightException(val resultCode: String) : IOException(resultCode) {
    class Empty : PptxPreflightException("EMPTY_FILE")
    class Encrypted : PptxPreflightException("ENCRYPTED_OFFICE_FILE")
    class Corrupt(code: String = "ZIP_OPEN_FAILED") : PptxPreflightException(code)
    class MissingParts(code: String) : PptxPreflightException(code)
    class Unsafe(code: String = "ZIP_LIMIT_EXCEEDED") : PptxPreflightException(code)
    class TooLarge : PptxPreflightException("TOO_LARGE_FOR_DEVICE")
    class Unsupported : PptxPreflightException("UNSUPPORTED_MACRO_FORMAT")
    class Internal : PptxPreflightException("INTERNAL_VALIDATION_ERROR")
    class CopyFailed : PptxPreflightException("SOURCE_COPY_FAILED")
}

data class PptxSession(
    val file: File,
    val byteCount: Long,
    val declaredBytes: Long?,
    val entryCount: Int,
    val uncompressedBytes: Long
)

data class PptxValidationStats(val entryCount: Int, val uncompressedBytes: Long)

class PptxPreflight(
    private val resolver: ContentResolver?,
    private val sessionStore: PptxSessionStore,
    private val budget: PptxRenderBudget
) {
    constructor(resolver: ContentResolver?, cacheDir: File) : this(
        resolver,
        PptxSessionStore(cacheDir),
        PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(256, 256, false))
    )

    suspend fun copyAndValidate(uri: Uri, sessionId: String, onCopyComplete: suspend (Long) -> Unit = {}): PptxSession = withContext(Dispatchers.IO) {
        sessionStore.removeStaleSessions()
        val output = sessionStore.create(sessionId)
        try {
            val declared = declaredSize(uri)
            if (declaredSizeExceedsLimit(declared, budget.maxCompressedBytes)) throw PptxPreflightException.TooLarge()
            val copied = copy(uri, output)
            if (copied == 0L) throw PptxPreflightException.Empty()
            onCopyComplete(copied)
            val stats = validate(output)
            PptxSession(output, copied, declared, stats.entryCount, stats.uncompressedBytes)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            output.delete()
            throw cancelled
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun declaredSize(uri: Uri): Long? = try {
        resolver?.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it >= 0L }
        }
    } catch (_: Exception) { null }

    private suspend fun copy(uri: Uri, output: File): Long {
        val source = try { resolver?.openInputStream(uri) } catch (error: SecurityException) { throw error }
            ?: throw FileNotFoundException()
        try {
            source.use { input ->
                return copyPptxStream(input, output, budget.maxCompressedBytes) { coroutineContext.ensureActive() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: PptxPreflightException) { throw error }
        catch (_: IOException) { throw PptxPreflightException.CopyFailed() }
    }

    internal suspend fun validate(file: File): PptxValidationStats = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val signature = ByteArray(8)
        val signatureSize = FileInputStream(file).use { it.read(signature) }
        if (signatureSize == 8 && signature.contentEquals(OLE_SIGNATURE)) throw PptxPreflightException.Encrypted()
        if (signatureSize < 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4b.toByte()) {
            throw PptxPreflightException.Corrupt("NOT_ZIP_CONTAINER")
        }
        try {
            ZipFile(file).use { zip ->
                val names = HashSet<String>()
                var count = 0
                var total = 0L
                var contentTypes = false
                var rootRelationships = false
                var presentation = false
                var presentationRelationships = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    count++
                    if (count > budget.maxEntryCount) throw PptxPreflightException.Unsafe("ENTRY_COUNT_LIMIT")
                    val name = normalizedEntryName(entry.name)
                    if (!names.add(name)) throw PptxPreflightException.Unsafe("DUPLICATE_ZIP_ENTRY")
                    if (entry.isDirectory) continue
                    if (name.equals("EncryptionInfo", true) || name.equals("EncryptedPackage", true)) throw PptxPreflightException.Encrypted()
                    if (entry.size > budget.maxEntryBytes || entry.size < -1L || entry.compressedSize < -1L) {
                        throw PptxPreflightException.Unsafe("ENTRY_SIZE_LIMIT")
                    }
                    var entryBytes = 0L
                    zip.getInputStream(entry).buffered().use { input ->
                        val buffer = ByteArray(SCAN_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes = safeAdd(entryBytes, read.toLong())
                            total = safeAdd(total, read.toLong())
                            if (entryBytes > budget.maxEntryBytes) throw PptxPreflightException.Unsafe("ENTRY_SIZE_LIMIT")
                            if (total > budget.maxTotalUncompressedBytes) throw PptxPreflightException.Unsafe("TOTAL_SIZE_LIMIT")
                        }
                    }
                    if (entry.compressedSize == 0L && entryBytes > 0L) throw PptxPreflightException.Unsafe("COMPRESSION_RATIO_LIMIT")
                    if (entry.compressedSize > 0L && entryBytes.toDouble() / entry.compressedSize.toDouble() > budget.maxCompressionRatio) {
                        throw PptxPreflightException.Unsafe("COMPRESSION_RATIO_LIMIT")
                    }
                    when (name) {
                        "[Content_Types].xml" -> contentTypes = true
                        "_rels/.rels" -> rootRelationships = true
                        "ppt/presentation.xml" -> presentation = true
                        "ppt/_rels/presentation.xml.rels" -> presentationRelationships = true
                    }
                }
                if (!contentTypes) throw PptxPreflightException.MissingParts("MISSING_CONTENT_TYPES")
                if (!rootRelationships) throw PptxPreflightException.MissingParts("MISSING_ROOT_RELATIONSHIPS")
                if (!presentation) throw PptxPreflightException.MissingParts("MISSING_PRESENTATION_PART")
                if (!presentationRelationships) throw PptxPreflightException.MissingParts("MISSING_PRESENTATION_RELATIONSHIPS")
                PptxValidationStats(count, total)
            }
        } catch (error: PptxPreflightException) { throw error }
        catch (_: ZipException) { throw PptxPreflightException.Corrupt() }
        catch (_: IOException) { throw PptxPreflightException.Corrupt() }
        catch (_: ArithmeticException) { throw PptxPreflightException.Unsafe("INTEGER_OVERFLOW") }
        catch (_: RuntimeException) { throw PptxPreflightException.Internal() }
    }

    private fun normalizedEntryName(raw: String): String {
        if (raw.isBlank() || raw.indexOf('\u0000') >= 0 || raw.startsWith('/') || raw.startsWith('\\') || DRIVE_PATH.matches(raw)) {
            throw PptxPreflightException.Unsafe("UNSAFE_ZIP_PATH")
        }
        val name = raw.replace('\\', '/').trimEnd('/')
        if (name.isBlank() || name.startsWith('/') || name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw PptxPreflightException.Unsafe("UNSAFE_ZIP_PATH")
        }
        return name
    }

    private fun safeAdd(value: Long, amount: Long): Long = try { Math.addExact(value, amount) }
    catch (_: ArithmeticException) { throw PptxPreflightException.Unsafe("INTEGER_OVERFLOW") }

    companion object {
        private const val SCAN_BUFFER_SIZE = 32 * 1024
        private val DRIVE_PATH = Regex("^[A-Za-z]:.*")
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte())
    }
}

internal fun declaredSizeExceedsLimit(declaredBytes: Long?, limit: Long): Boolean =
    declaredBytes != null && declaredBytes >= 0L && declaredBytes > limit

internal fun copyPptxStream(input: InputStream, output: File, limit: Long, checkActive: () -> Unit = {}): Long {
    var copied = 0L
    try {
        output.outputStream().buffered().use { destination ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                checkActive()
                val read = input.read(buffer)
                if (read < 0) break
                copied = try { Math.addExact(copied, read.toLong()) }
                catch (_: ArithmeticException) { throw PptxPreflightException.Unsafe("INTEGER_OVERFLOW") }
                if (copied > limit) throw PptxPreflightException.TooLarge()
                destination.write(buffer, 0, read)
            }
            destination.flush()
        }
        return copied
    } catch (error: Exception) {
        output.delete()
        throw error
    }
}
