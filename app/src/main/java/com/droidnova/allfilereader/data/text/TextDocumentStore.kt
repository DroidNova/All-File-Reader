package com.droidnova.allfilereader.data.text

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

object TxtLimits {
    val budget: TextReaderBudget = TextReaderBudgetPolicy.default
    const val MAX_FILE_BYTES = TextReaderBudgetPolicy.ABSOLUTE_SESSION_BYTES
    const val READ_BUFFER_BYTES = 64 * 1024
    val CHUNK_CHARACTERS = budget.decodedChunkCharLimit
    val CACHE_CHUNKS = budget.maxCachedChunkCount
    val MAX_SEARCH_MATCHES = budget.maxStoredSearchMatches
    const val MAX_QUERY_CHARACTERS = 256
    val MAX_VISUAL_SEGMENT_CHARACTERS = budget.maxVisualSegmentCharacters
}

class BinaryTextException : Exception()
class UnsupportedTextEncodingException : Exception()
class TextFileTooLargeException : Exception()
class InsufficientTextStorageException : Exception()

data class TextChunkIndex(
    val byteOffset: Long,
    val byteLength: Int,
    val characterOffset: Long,
    val characterLength: Int
)

data class TextMatch(val characterOffset: Long, val chunkIndex: Int)
data class TextSearchResult(val matches: List<TextMatch>, val truncated: Boolean)

class TextDocumentStore internal constructor(
    private val file: File,
    private val budget: TextReaderBudget = TxtLimits.budget,
    private val mutableChunks: CopyOnWriteArrayList<TextChunkIndex> = CopyOnWriteArrayList()
) : Closeable {
    val chunks: List<TextChunkIndex> get() = mutableChunks
    var encoding: TextEncoding = TextEncoding.UTF8
        internal set
    var loadingMode: TextLoadingMode = TextLoadingMode.CHUNKED
        internal set
    var actualSourceBytes: Long = 0L
        internal set

    private var cachedCharacters = 0
    private val cache = object : LinkedHashMap<Int, String>(budget.maxCachedChunkCount, .75f, true) {}
    private var closed = false

    @Synchronized
    fun readChunk(index: Int): String {
        check(!closed)
        cache[index]?.let { return it }
        val entry = chunks[index]
        require(entry.byteLength >= 0)
        val bytes = ByteArray(entry.byteLength)
        RandomAccessFile(file, "r").use { source ->
            source.seek(entry.byteOffset)
            source.readFully(bytes)
        }
        return bytes.toString(StandardCharsets.UTF_8).also { cacheChunk(index, it) }
    }

    suspend fun search(query: String): TextSearchResult {
        if (query.isEmpty()) return TextSearchResult(emptyList(), false)
        val boundedQuery = query.take(TxtLimits.MAX_QUERY_CHARACTERS)
        val results = ArrayList<TextMatch>(minOf(128, budget.maxStoredSearchMatches))
        var tail = ""
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val text = readChunk(chunkIndex)
            val combined = tail + text
            val combinedStart = chunk.characterOffset - tail.length
            var from = 0
            while (from <= combined.length - boundedQuery.length) {
                coroutineContext.ensureActive()
                val found = combined.indexOf(boundedQuery, from, ignoreCase = true)
                if (found < 0) break
                val global = combinedStart + found
                if (global + boundedQuery.length > chunk.characterOffset) {
                    if (results.size >= budget.maxStoredSearchMatches) {
                        return TextSearchResult(results, truncated = true)
                    }
                    val owner = chunks.binarySearchBy(global) { it.characterOffset }.let { hit ->
                        if (hit >= 0) hit else (-hit - 2).coerceAtLeast(0)
                    }
                    results += TextMatch(global, owner)
                }
                from = found + 1
            }
            tail = combined.takeLast((boundedQuery.length - 1).coerceAtMost(budget.decodedChunkCharLimit))
        }
        return TextSearchResult(results, truncated = false)
    }

    @Synchronized
    fun clearCache() {
        cache.clear()
        cachedCharacters = 0
    }

    @Synchronized
    internal fun addChunk(chunk: TextChunkIndex, text: String) {
        check(!closed)
        mutableChunks.add(chunk)
        cacheChunk(mutableChunks.lastIndex, text)
    }

    @Synchronized
    private fun cacheChunk(index: Int, text: String) {
        cache.remove(index)?.let { cachedCharacters -= it.length }
        cache[index] = text
        cachedCharacters = Math.addExact(cachedCharacters, text.length)
        while (cache.size > budget.maxCachedChunkCount || cachedCharacters > budget.maxCachedCharacters) {
            val eldest = cache.entries.iterator().next()
            cachedCharacters -= eldest.value.length
            cache.remove(eldest.key)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clearCache()
        file.delete()
        file.parentFile?.takeIf { it.name == SESSION_DIRECTORY && it.list()?.isEmpty() == true }?.delete()
    }

    companion object {
        internal const val SESSION_DIRECTORY = "txt_reader_sessions"
    }
}

class TextDocumentPreparer(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val budget: TextReaderBudget = TxtLimits.budget
) {
    suspend fun prepare(
        uri: Uri,
        onStreamOpened: suspend () -> Unit = {},
        onEncodingDetected: suspend () -> Unit = {},
        onChunkPrepared: suspend (TextDocumentStore, String) -> Unit = { _, _ -> }
    ): TextDocumentStore {
        val sessions = File(cacheDir, TextDocumentStore.SESSION_DIRECTORY).apply { mkdirs() }
        cleanupStaleTextSessions(sessions)
        if (sessions.usableSpace <= TextReaderBudgetPolicy.RESERVED_FREE_BYTES) {
            throw InsufficientTextStorageException()
        }
        val output = File.createTempFile("session_", ".utf8", sessions)
        val store = TextDocumentStore(output, budget)
        try {
            resolver.openInputStream(uri)?.use { input ->
                onStreamOpened()
                val result = IncrementalTextDecoder.decode(
                    source = input,
                    output = output,
                    maxBytes = TextReaderBudgetPolicy.ABSOLUTE_SESSION_BYTES,
                    budget = budget,
                    onEncodingDetected = onEncodingDetected
                ) { chunk, text ->
                    store.addChunk(chunk, text)
                    onChunkPrepared(store, text)
                }
                store.encoding = result.encoding
                store.actualSourceBytes = result.actualSourceBytes
                store.loadingMode = if (result.actualSourceBytes <= budget.smallFileByteLimit) {
                    TextLoadingMode.IN_MEMORY
                } else {
                    TextLoadingMode.CHUNKED
                }
                return store
            } ?: throw FileNotFoundException()
        } catch (error: Exception) {
            store.close()
            throw error
        }
    }
}

internal fun cleanupStaleTextSessions(
    sessionDirectory: File,
    activeFiles: Set<File> = emptySet(),
    nowMillis: Long = System.currentTimeMillis(),
    staleAfterMillis: Long = 24L * 60L * 60L * 1_000L
) {
    val root = sessionDirectory.canonicalFile
    val active = activeFiles.mapTo(HashSet()) { it.canonicalFile }
    root.listFiles()?.forEach { candidate ->
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
        val contained = canonical.parentFile == root
        val eligibleName = canonical.name.startsWith("session_") && canonical.extension == "utf8"
        val age = nowMillis - canonical.lastModified()
        if (contained && eligibleName && canonical !in active && age >= staleAfterMillis && age >= 0L) {
            canonical.delete()
        }
    }
}

data class TextDecodeResult(
    val chunks: List<TextChunkIndex>,
    val encoding: TextEncoding,
    val actualSourceBytes: Long
)

object IncrementalTextDecoder {
    suspend fun decode(
        source: InputStream,
        output: File,
        maxBytes: Long = TxtLimits.MAX_FILE_BYTES,
        budget: TextReaderBudget = TxtLimits.budget,
        onEncodingDetected: suspend () -> Unit = {},
        onChunkIndexed: suspend (TextChunkIndex, String) -> Unit = { _, _ -> }
    ): TextDecodeResult {
        val counted = BoundedInputStream(BufferedInputStream(source, TxtLimits.READ_BUFFER_BYTES), maxBytes)
        val sampleSize = budget.binarySampleBytes.coerceAtLeast(4)
        val input = PushbackInputStream(counted, sampleSize)
        val sample = ByteArray(sampleSize)
        val sampleCount = input.read(sample).coerceAtLeast(0)
        val detection = detectEncoding(sample, sampleCount)
        onEncodingDetected()
        if (sampleCount > detection.bomBytes) {
            input.unread(sample, detection.bomBytes, sampleCount - detection.bomBytes)
        }
        val decoder = detection.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = BufferedReader(InputStreamReader(input, decoder), TxtLimits.READ_BUFFER_BYTES)
        val indexes = ArrayList<TextChunkIndex>()
        var byteOffset = 0L
        var characterOffset = 0L
        output.outputStream().buffered(TxtLimits.READ_BUFFER_BYTES).use { target ->
            reader.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val chars = CharArray(budget.decodedChunkCharLimit + 1)
                    var count = 0
                    while (count < budget.decodedChunkCharLimit) {
                        val read = reader.read(chars, count, budget.decodedChunkCharLimit - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    if (Character.isHighSurrogate(chars[count - 1])) {
                        val next = reader.read()
                        if (next >= 0) chars[count++] = next.toChar()
                    }
                    val text = String(chars, 0, count)
                    val bytes = text.toByteArray(StandardCharsets.UTF_8)
                    val nextOffset = Math.addExact(byteOffset, bytes.size.toLong())
                    if (nextOffset > maxBytes || output.parentFile?.usableSpace?.let {
                            it <= TextReaderBudgetPolicy.RESERVED_FREE_BYTES
                        } == true) {
                        throw InsufficientTextStorageException()
                    }
                    target.write(bytes)
                    target.flush()
                    val chunk = TextChunkIndex(byteOffset, bytes.size, characterOffset, count)
                    indexes += chunk
                    onChunkIndexed(chunk, text)
                    byteOffset = nextOffset
                    characterOffset = Math.addExact(characterOffset, count.toLong())
                }
            }
        }
        return TextDecodeResult(indexes, detection.encoding, counted.count)
    }

    internal fun detectEncoding(sample: ByteArray, count: Int = sample.size): EncodingDetection {
        val length = count.coerceIn(0, sample.size)
        val prefix = sample.copyOf(length)
        val detection = when {
            prefix.startsWith(0x00, 0x00, 0xFE, 0xFF) -> encoding(TextEncoding.UTF32_BE, "UTF-32BE", 4)
            prefix.startsWith(0xFF, 0xFE, 0x00, 0x00) -> encoding(TextEncoding.UTF32_LE, "UTF-32LE", 4)
            prefix.startsWith(0xEF, 0xBB, 0xBF) -> EncodingDetection(TextEncoding.UTF8, StandardCharsets.UTF_8, 3)
            prefix.startsWith(0xFF, 0xFE) -> EncodingDetection(TextEncoding.UTF16_LE, StandardCharsets.UTF_16LE, 2)
            prefix.startsWith(0xFE, 0xFF) -> EncodingDetection(TextEncoding.UTF16_BE, StandardCharsets.UTF_16BE, 2)
            strictUtf8(prefix) -> EncodingDetection(TextEncoding.UTF8, StandardCharsets.UTF_8, 0)
            !looksBinaryBytes(prefix) -> encoding(TextEncoding.WINDOWS_1252, "windows-1252", 0)
            else -> throw BinaryTextException()
        }
        if (looksBinaryDecoded(prefix, detection)) throw BinaryTextException()
        return detection
    }

    private fun encoding(type: TextEncoding, name: String, bomBytes: Int): EncodingDetection = try {
        EncodingDetection(type, Charset.forName(name), bomBytes)
    } catch (_: Exception) {
        throw UnsupportedTextEncodingException()
    }

    private fun strictUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun looksBinaryDecoded(bytes: ByteArray, detection: EncodingDetection): Boolean = try {
        val decoded = detection.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, detection.bomBytes, bytes.size - detection.bomBytes))
        var controls = 0
        for (character in decoded) {
            if (character.code < 0x20 && character !in charArrayOf('\b', '\t', '\n', '\u000C', '\r')) controls++
        }
        decoded.isNotEmpty() && controls.toDouble() / decoded.length > 0.10
    } catch (_: CharacterCodingException) {
        detection.encoding != TextEncoding.WINDOWS_1252
    }

    private fun looksBinaryBytes(bytes: ByteArray): Boolean {
        if (bytes.startsWith(0x89, 0x50, 0x4E, 0x47) || bytes.startsWith(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWith(0x7F, 0x45, 0x4C, 0x46) || bytes.startsWith(0x4D, 0x5A)) return true
        if (bytes.isEmpty()) return false
        val controls = bytes.count { value ->
            val unsigned = value.toInt() and 0xff
            unsigned < 0x20 && unsigned !in intArrayOf(0x08, 0x09, 0x0a, 0x0c, 0x0d)
        }
        return controls.toDouble() / bytes.size > 0.10
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xff) == values[it] }
}

data class EncodingDetection(
    val encoding: TextEncoding,
    val charset: Charset,
    val bomBytes: Int
)

private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var count: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) add(1L) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }

    private fun add(amount: Long) {
        count = Math.addExact(count, amount)
        if (count > limit) throw TextFileTooLargeException()
    }
}
