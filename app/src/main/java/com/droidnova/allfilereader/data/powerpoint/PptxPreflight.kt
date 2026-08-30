package com.droidnova.allfilereader.data.powerpoint

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

sealed class PptxPreflightException(val resultCode: String) : IOException(resultCode) {
    class Encrypted : PptxPreflightException("ENCRYPTED_OFFICE_FILE")
    class Corrupt(code: String = "ZIP_OPEN_FAILED") : PptxPreflightException(code)
    class Unsafe(code: String = "ZIP_LIMIT_EXCEEDED") : PptxPreflightException(code)
    class Unsupported : PptxPreflightException("UNSUPPORTED_MACRO_FORMAT")
    class Internal : PptxPreflightException("INTERNAL_VALIDATION_ERROR")
    class CopyFailed : PptxPreflightException("SOURCE_COPY_FAILED")
}

data class PptxSession(val file: File)

class PptxPreflight(private val resolver: ContentResolver?, private val cacheDir: File) {
    fun copyAndValidate(uri: Uri): PptxSession {
        val directory = File(cacheDir, "pptx_sessions").apply { mkdirs() }
        val output = File.createTempFile("presentation-", ".pptx", directory)
        try {
            trace("stage=SOURCE_OPEN_START")
            val source = try { resolver?.openInputStream(uri) } catch (error: SecurityException) {
                trace("code=SOURCE_PERMISSION_DENIED streamOpened=false")
                throw error
            } ?: run {
                trace("code=SOURCE_NOT_FOUND streamOpened=false")
                throw FileNotFoundException()
            }
            trace("stage=SOURCE_OPEN_OK streamOpened=true")
            var copied = 0L
            try {
                source.use { input -> output.outputStream().use { destination ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_ARCHIVE) throw PptxPreflightException.Unsafe()
                        destination.write(buffer, 0, read)
                    }
                }}
            } catch (error: PptxPreflightException) { throw error }
            catch (error: IOException) { throw PptxPreflightException.CopyFailed() }
            trace("stage=SOURCE_COPY_OK copiedBytes=$copied")
            if (copied == 0L) throw PptxPreflightException.Corrupt("EMPTY_FILE")
            validate(output)
            trace("code=OK copiedBytes=$copied")
            return PptxSession(output)
        } catch (error: Throwable) {
            output.delete()
            if (error is PptxPreflightException) trace("code=${error.resultCode}")
            throw error
        }
    }

    internal fun validate(file: File) {
        val signature = ByteArray(8)
        val signatureSize = FileInputStream(file).use { it.read(signature) }
        val signatureHex = signature.take(signatureSize.coerceAtLeast(0)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        trace("stage=SIGNATURE_READ signature=$signatureHex")
        if (signatureSize == 8 && signature.contentEquals(OLE_SIGNATURE)) throw PptxPreflightException.Encrypted()
        if (signatureSize < 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4b.toByte()) {
            throw PptxPreflightException.Corrupt("NOT_ZIP_CONTAINER")
        }
        try {
            ZipFile(file).use { zip ->
                trace("stage=ZIP_OPEN_OK zipOpened=true")
                val names = HashSet<String>()
                var count = 0
                var total = 0L
                var media = 0L
                var contentTypes = false
                var presentation = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (count > MAX_ENTRIES) throw PptxPreflightException.Unsafe()
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || name.split('/').any { it == ".." }) throw PptxPreflightException.Unsafe("UNSAFE_ZIP_PATH")
                    if (!names.add(name)) throw PptxPreflightException.Unsafe("DUPLICATE_ZIP_ENTRY")
                    if (entry.isDirectory) continue
                    var entryBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > MAX_ENTRY || total > MAX_TOTAL) throw PptxPreflightException.Unsafe()
                            if (name.startsWith("ppt/media/")) {
                                media += read
                                if (media > MAX_MEDIA) throw PptxPreflightException.Unsafe()
                            }
                        }
                    }
                    if (name == "[Content_Types].xml") contentTypes = true
                    if (name == "ppt/presentation.xml") presentation = true
                }
                trace("stage=ZIP_SCAN_DONE entries=$count contentTypes=$contentTypes presentation=$presentation total=$total media=$media")
                if (!contentTypes) throw PptxPreflightException.Corrupt("MISSING_CONTENT_TYPES")
                if (!presentation) throw PptxPreflightException.Corrupt("MISSING_PRESENTATION_PART")
            }
        } catch (error: PptxPreflightException) { throw error }
        catch (error: ZipException) { throw PptxPreflightException.Corrupt("ZIP_OPEN_FAILED") }
        catch (error: IOException) { throw PptxPreflightException.Corrupt("ZIP_OPEN_FAILED") }
        catch (error: RuntimeException) { throw PptxPreflightException.Internal() }
    }

    companion object {
        const val MAX_ENTRIES = 4000
        const val MAX_ENTRY = 32L * 1024 * 1024
        const val MAX_TOTAL = 256L * 1024 * 1024
        const val MAX_MEDIA = 192L * 1024 * 1024
        const val MAX_ARCHIVE = 256L * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte())
        private fun trace(message: String) { runCatching { Log.d("PptxPreflight", message) } }
    }
}
