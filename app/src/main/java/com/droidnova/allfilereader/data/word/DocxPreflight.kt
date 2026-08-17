package com.droidnova.allfilereader.data.word

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal object DocxLimits {
    const val MAX_COMPRESSED_BYTES = 50L * 1024L * 1024L
    const val MAX_ENTRY_COUNT = 4_096
    const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    const val MAX_INSPECTED_BYTES = 256L * 1024L * 1024L
    const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
    const val MAX_CONTENT_TYPES_BYTES = 1L * 1024L * 1024L

    /** Reject entries whose uncompressed size exceeds compressed size by more than 100:1. */
    const val MAX_COMPRESSION_RATIO = 100L
}

internal sealed class DocxPreflightException : Exception() {
    class Invalid : DocxPreflightException()
    class Unsupported : DocxPreflightException()
    class Unsafe : DocxPreflightException()
}

/** Copies a content URI once into private cache and validates the entire ZIP without extracting it. */
internal class DocxPreflight(private val resolver: ContentResolver, private val cacheDir: File) {
    suspend fun copyAndValidate(uri: Uri): File {
        val target = File.createTempFile("docx_session_", ".docx", cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var compressed = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        compressed += read
                        if (compressed > DocxLimits.MAX_COMPRESSED_BYTES) throw DocxPreflightException.Unsafe()
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw FileNotFoundException()
            if (target.length() == 0L) throw DocxPreflightException.Invalid()
            inspectZip(target)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private suspend fun inspectZip(file: File) {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() !in 1..DocxLimits.MAX_ENTRY_COUNT) throw DocxPreflightException.Unsafe()
                if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry("word/document.xml") == null) {
                    throw DocxPreflightException.Invalid()
                }
                var inspected = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(name) || name.split('/').any { it == ".." }) {
                        throw DocxPreflightException.Unsafe()
                    }
                    if (entry.size < 0L || entry.compressedSize < 0L || entry.size > DocxLimits.MAX_ENTRY_BYTES) {
                        throw DocxPreflightException.Unsafe()
                    }
                    if (name.startsWith("word/media/") && entry.size > DocxLimits.MAX_IMAGE_BYTES) {
                        throw DocxPreflightException.Unsafe()
                    }
                    val isEmbeddedImage = name.startsWith("word/media/")
                    if (entry.size > 0 && entry.compressedSize == 0L ||
                        entry.compressedSize > 0 && entry.size > entry.compressedSize * DocxLimits.MAX_COMPRESSION_RATIO) {
                        throw DocxPreflightException.Unsafe()
                    }
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryRead = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            entryRead += read
                            inspected += read
                            if (isEmbeddedImage && entryRead > DocxLimits.MAX_IMAGE_BYTES) throw DocxPreflightException.Unsafe()
                            if (entryRead > DocxLimits.MAX_ENTRY_BYTES || inspected > DocxLimits.MAX_INSPECTED_BYTES) {
                                throw DocxPreflightException.Unsafe()
                            }
                        }
                    }
                }
                validateContentTypes(zip)
            }
        } catch (error: DocxPreflightException) {
            throw error
        } catch (_: ZipException) {
            throw DocxPreflightException.Invalid()
        }
    }

    private fun validateContentTypes(zip: ZipFile) {
        val entry = zip.getEntry("[Content_Types].xml")
        if (entry.size > DocxLimits.MAX_CONTENT_TYPES_BYTES) throw DocxPreflightException.Unsafe()
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val normalMain = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
        val macroMain = "application/vnd.ms-word.document.macroEnabled.main+xml"
        if (macroMain in text || "vbaProject" in text) throw DocxPreflightException.Unsupported()
        if (normalMain !in text) throw DocxPreflightException.Unsupported()
    }
}
