package com.droidnova.allfilereader.data.text

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TextDocumentStoreTest {
    private fun prepared(bytes: ByteArray): TextDocumentStore = runBlocking {
        val file = File.createTempFile("txt_test_", ".cache")
        TextDocumentStore(file, IncrementalTextDecoder.decode(ByteArrayInputStream(bytes), file))
    }

    @Test fun utf8AndLineEndings() { prepared("one\ntwo\r\nतीन 😀".toByteArray()).use { assertEquals("one\ntwo\r\nतीन 😀", it.readChunk(0)) } }
    @Test fun utf8BomIsRemoved() { prepared(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()) + "hello".toByteArray()).use { assertEquals("hello", it.readChunk(0)) } }
    @Test fun utf16LittleEndianBom() { val body="नमस्ते 😀".toByteArray(StandardCharsets.UTF_16LE); prepared(byteArrayOf(0xFF.toByte(),0xFE.toByte())+body).use { assertEquals("नमस्ते 😀",it.readChunk(0)) } }
    @Test fun utf16BigEndianBom() { val body="hello 😀".toByteArray(StandardCharsets.UTF_16BE); prepared(byteArrayOf(0xFE.toByte(),0xFF.toByte())+body).use { assertEquals("hello 😀",it.readChunk(0)) } }
    @Test fun multibyteAcrossInputBuffers() { val text="a".repeat(TxtLimits.READ_BUFFER_BYTES-1)+"😀न"; prepared(text.toByteArray()).use { store -> assertEquals(text,store.chunks.indices.joinToString("") { store.readChunk(it) }) } }
    @Test fun searchWithinChunkIsCaseInsensitiveAndUnicodeSafe() = runBlocking { prepared("Alpha alpha नमस्ते 😀".toByteArray()).use { store -> assertEquals(2,store.search("ALPHA").matches.size); assertEquals(1,store.search("नमस्ते").matches.size); assertEquals(1,store.search("😀").matches.size) } }
    @Test fun searchCrossesChunkBoundary() = runBlocking { val text="a".repeat(TxtLimits.CHUNK_CHARACTERS-2)+"needle"; prepared(text.toByteArray()).use { assertEquals(TxtLimits.CHUNK_CHARACTERS-2L,it.search("needle").matches.single().characterOffset) } }
    @Test fun emptyAndMissingSearch() = runBlocking { prepared("content".toByteArray()).use { assertTrue(it.search("").matches.isEmpty()); assertTrue(it.search("absent").matches.isEmpty()) } }
    @Test fun excessiveMatchesAreCapped() = runBlocking { prepared("x".repeat(TxtLimits.MAX_SEARCH_MATCHES+10).toByteArray()).use { val result=it.search("x"); assertEquals(TxtLimits.MAX_SEARCH_MATCHES,result.matches.size); assertTrue(result.truncated) } }
    @Test fun oldSearchCanBeCancelled() = runBlocking { prepared("x".repeat(500_000).toByteArray()).use { store -> val job=launch { store.search("none") }; job.cancelAndJoin(); assertTrue(job.isCancelled) } }
    @Test fun configuredFileLimitIsEnforced() = runBlocking { val file=File.createTempFile("txt_test_", ".cache"); try { IncrementalTextDecoder.decode(ByteArrayInputStream("12345".toByteArray()),file,4); fail("Expected limit") } catch (_: TextFileTooLargeException) {} finally { file.delete() } }
    @Test fun binaryNulIsRejected() = runBlocking { val file=File.createTempFile("txt_test_", ".cache"); try { IncrementalTextDecoder.decode(ByteArrayInputStream(byteArrayOf(1,0,2)),file); fail("Expected binary rejection") } catch (_: BinaryTextException) {} finally { file.delete() } }
}
