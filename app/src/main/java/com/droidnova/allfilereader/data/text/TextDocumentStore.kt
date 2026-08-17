package com.droidnova.allfilereader.data.text

import android.content.ContentResolver
import android.net.Uri
import java.io.*
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object TxtLimits {
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_DECODED_CACHE_BYTES = 200L * 1024L * 1024L
    const val READ_BUFFER_BYTES = 64 * 1024
    const val CHUNK_CHARACTERS = 32 * 1024
    const val CACHE_CHUNKS = 10
    const val MAX_SEARCH_MATCHES = 10_000
    const val MAX_QUERY_CHARACTERS = 1_024
}

class BinaryTextException : Exception()
class UnsupportedTextEncodingException : Exception()
class TextFileTooLargeException : Exception()

data class TextChunkIndex(val byteOffset: Long, val byteLength: Int, val characterOffset: Long, val characterLength: Int)
data class TextMatch(val characterOffset: Long, val chunkIndex: Int)
data class TextSearchResult(val matches: List<TextMatch>, val truncated: Boolean)

class TextDocumentStore internal constructor(
    private val file: File,
    val chunks: List<TextChunkIndex>
) : Closeable {
    private val cache = object : LinkedHashMap<Int, String>(TxtLimits.CACHE_CHUNKS, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > TxtLimits.CACHE_CHUNKS
    }

    @Synchronized fun readChunk(index: Int): String {
        cache[index]?.let { return it }
        val entry = chunks[index]
        val bytes = ByteArray(entry.byteLength)
        RandomAccessFile(file, "r").use { source -> source.seek(entry.byteOffset); source.readFully(bytes) }
        return bytes.toString(StandardCharsets.UTF_8).also { cache[index] = it }
    }

    suspend fun search(query: String): TextSearchResult {
        if (query.isEmpty()) return TextSearchResult(emptyList(), false)
        val results = ArrayList<TextMatch>(minOf(128, TxtLimits.MAX_SEARCH_MATCHES))
        var tail = ""
        var truncated = false
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val text = readChunk(chunkIndex)
            val combined = tail + text
            val combinedStart = chunk.characterOffset - tail.length
            var from = 0
            while (from <= combined.length - query.length) {
                coroutineContext.ensureActive()
                val found = combined.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                val global = combinedStart + found
                // The previous iteration already considered matches ending before this chunk.
                if (global + query.length > chunk.characterOffset) {
                    if (results.size == TxtLimits.MAX_SEARCH_MATCHES) { truncated = true; break }
                    val owner = chunks.binarySearchBy(global) { it.characterOffset }.let { hit ->
                        if (hit >= 0) hit else (-hit - 2).coerceAtLeast(0)
                    }
                    results += TextMatch(global, owner)
                }
                from = found + 1
            }
            if (truncated) break
            tail = combined.takeLast((query.length - 1).coerceAtMost(TxtLimits.CHUNK_CHARACTERS))
        }
        return TextSearchResult(results, truncated)
    }

    @Synchronized fun clearCache() = cache.clear()
    override fun close() { clearCache(); file.delete() }
}

class TextDocumentPreparer(private val resolver: ContentResolver, private val cacheDir: File) {
    suspend fun prepare(uri: Uri, onEncodingDetected: suspend () -> Unit = {}): TextDocumentStore {
        val output = File.createTempFile("txt_session_", ".cache", cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                val indexes = IncrementalTextDecoder.decode(input, output, onEncodingDetected = onEncodingDetected)
                return TextDocumentStore(output, indexes)
            } ?: throw FileNotFoundException()
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }
}

object IncrementalTextDecoder {
    suspend fun decode(source: InputStream, output: File, maxBytes: Long = TxtLimits.MAX_FILE_BYTES, onEncodingDetected: suspend () -> Unit = {}): List<TextChunkIndex> {
        val counted = BoundedInputStream(BufferedInputStream(source, TxtLimits.READ_BUFFER_BYTES), maxBytes)
        val input = PushbackInputStream(counted, 4096)
        val sample = ByteArray(4096)
        val sampleCount = input.read(sample).coerceAtLeast(0)
        val (charset, bom) = when {
            sampleCount >= 3 && sample.sliceArray(0..2).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> StandardCharsets.UTF_8 to 3
            sampleCount >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() -> StandardCharsets.UTF_16LE to 2
            sampleCount >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() -> StandardCharsets.UTF_16BE to 2
            else -> { if (looksBinary(sample, sampleCount)) throw BinaryTextException(); StandardCharsets.UTF_8 to 0 }
        }
        onEncodingDetected()
        if (sampleCount > bom) input.unread(sample, bom, sampleCount - bom)
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = BufferedReader(InputStreamReader(input, decoder), TxtLimits.READ_BUFFER_BYTES)
        val indexes = ArrayList<TextChunkIndex>()
        var byteOffset = 0L; var characterOffset = 0L
        output.outputStream().buffered(TxtLimits.READ_BUFFER_BYTES).use { target ->
            reader.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val chars = CharArray(TxtLimits.CHUNK_CHARACTERS + 1)
                    var count = 0
                    while (count < TxtLimits.CHUNK_CHARACTERS) {
                        val read = reader.read(chars, count, TxtLimits.CHUNK_CHARACTERS - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    if (Character.isHighSurrogate(chars[count - 1]) || chars[count - 1] == '\r') {
                        val next = reader.read(); if (next >= 0) chars[count++] = next.toChar()
                    }
                    val bytes = String(chars, 0, count).toByteArray(StandardCharsets.UTF_8)
                    if (byteOffset + bytes.size > TxtLimits.MAX_DECODED_CACHE_BYTES) throw TextFileTooLargeException()
                    target.write(bytes)
                    indexes += TextChunkIndex(byteOffset, bytes.size, characterOffset, count)
                    byteOffset += bytes.size; characterOffset += count
                }
            }
        }
        return indexes
    }

    private fun looksBinary(sample: ByteArray, count: Int): Boolean {
        if (count == 0) return false
        var controls = 0
        for (index in 0 until count) {
            val value = sample[index].toInt() and 0xff
            if (value == 0) return true
            if (value < 0x20 && value !in setOf(0x08, 0x09, 0x0a, 0x0c, 0x0d)) controls++
        }
        return controls * 20 > count
    }
}

private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }
    private fun add(amount: Long) { count += amount; if (count > limit) throw TextFileTooLargeException() }
}
