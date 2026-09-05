package com.droidnova.allfilereader.data.text

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.droidnova.allfilereader.ui.screens.txt.TxtReaderContent
import com.droidnova.allfilereader.ui.screens.txt.failureContent
import com.droidnova.allfilereader.ui.screens.txt.TxtLoadRequestGate
import org.junit.Assert.*
import org.junit.Test

class TextDocumentStoreTest {
    private fun prepared(bytes: ByteArray): TextDocumentStore = runBlocking {
        val file = File.createTempFile("txt_test_", ".cache")
        val store = TextDocumentStore(file)
        IncrementalTextDecoder.decode(ByteArrayInputStream(bytes), file) { chunk, text -> store.addChunk(chunk, text) }
        store
    }

    @Test fun utf8AndLineEndings() { prepared("one\ntwo\r\nतीन 😀".toByteArray()).use { assertEquals("one\ntwo\r\nतीन 😀", it.readChunk(0)) } }
    @Test fun utf8BomIsRemoved() { prepared(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()) + "hello".toByteArray()).use { assertEquals("hello", it.readChunk(0)) } }
    @Test fun utf16LittleEndianBom() { val body="नमस्ते 😀".toByteArray(StandardCharsets.UTF_16LE); prepared(byteArrayOf(0xFF.toByte(),0xFE.toByte())+body).use { assertEquals("नमस्ते 😀",it.readChunk(0)) } }
    @Test fun utf16BigEndianBom() { val body="hello 😀".toByteArray(StandardCharsets.UTF_16BE); prepared(byteArrayOf(0xFE.toByte(),0xFF.toByte())+body).use { assertEquals("hello 😀",it.readChunk(0)) } }
    @Test fun utf32BomIsCheckedBeforeUtf16() {
        val charset = Charset.forName("UTF-32LE")
        val body = "hello 😀".toByteArray(charset)
        prepared(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + body).use {
            assertEquals("hello 😀", it.readChunk(0))
        }
    }
    @Test fun multibyteAcrossInputBuffers() { val text="a".repeat(TxtLimits.READ_BUFFER_BYTES-1)+"😀न"; prepared(text.toByteArray()).use { store -> assertEquals(text,store.chunks.indices.joinToString("") { store.readChunk(it) }) } }
    @Test fun searchWithinChunkIsCaseInsensitiveAndUnicodeSafe() = runBlocking { prepared("Alpha alpha नमस्ते 😀".toByteArray()).use { store -> assertEquals(2,store.search("ALPHA").matches.size); assertEquals(1,store.search("नमस्ते").matches.size); assertEquals(1,store.search("😀").matches.size) } }
    @Test fun searchCrossesChunkBoundary() = runBlocking { val text="a".repeat(TxtLimits.CHUNK_CHARACTERS-2)+"needle"; prepared(text.toByteArray()).use { assertEquals(TxtLimits.CHUNK_CHARACTERS-2L,it.search("needle").matches.single().characterOffset) } }
    @Test fun emptyAndMissingSearch() = runBlocking { prepared("content".toByteArray()).use { assertTrue(it.search("").matches.isEmpty()); assertTrue(it.search("absent").matches.isEmpty()) } }
    @Test fun excessiveMatchesAreCapped() = runBlocking { prepared("x".repeat(TxtLimits.MAX_SEARCH_MATCHES+10).toByteArray()).use { val result=it.search("x"); assertEquals(TxtLimits.MAX_SEARCH_MATCHES,result.matches.size); assertTrue(result.truncated) } }
    @Test fun oldSearchCanBeCancelled() = runBlocking { prepared("x".repeat(500_000).toByteArray()).use { store -> val job=launch { store.search("none") }; job.cancelAndJoin(); assertTrue(job.isCancelled) } }
    @Test fun configuredFileLimitIsEnforced() = runBlocking { val file=File.createTempFile("txt_test_", ".cache"); try { IncrementalTextDecoder.decode(ByteArrayInputStream("12345".toByteArray()),file,4); fail("Expected limit") } catch (_: TextFileTooLargeException) {} finally { file.delete() } }
    @Test fun binaryNulIsRejected() = runBlocking { val file=File.createTempFile("txt_test_", ".cache"); try { IncrementalTextDecoder.decode(ByteArrayInputStream(byteArrayOf(1,0,2)),file); fail("Expected binary rejection") } catch (_: BinaryTextException) {} finally { file.delete() } }
    @Test fun archiveSignatureIsRejected() = runBlocking {
        val file = File.createTempFile("txt_test_", ".cache")
        try {
            IncrementalTextDecoder.decode(ByteArrayInputStream(byteArrayOf(0x50, 0x4b, 3, 4, 1, 2)), file)
            fail("Expected binary rejection")
        } catch (_: BinaryTextException) {
            // Expected.
        } finally {
            file.delete()
        }
    }

    @Test fun budgetSelectsLowRamAndNormalProfiles() {
        val low = TextReaderBudgetPolicy.forDevice(TextReaderDeviceProfile(128, true))
        val normal = TextReaderBudgetPolicy.forDevice(TextReaderDeviceProfile(256, false))
        assertTrue(low.smallFileByteLimit < normal.smallFileByteLimit)
        assertTrue(low.maxCachedCharacters < normal.maxCachedCharacters)
        assertTrue(normal.maxStoredSearchMatches <= 1_000)
    }

    @Test fun staleCleanupOnlyRemovesOwnedInactiveSessions() {
        val root = createTempDir(prefix = "txt_sessions_")
        val stale = File(root, "session_old.utf8").apply { writeText("old"); setLastModified(1L) }
        val active = File(root, "session_active.utf8").apply { writeText("active"); setLastModified(1L) }
        val unrelated = File(root, "other.cache").apply { writeText("keep"); setLastModified(1L) }
        cleanupStaleTextSessions(root, setOf(active), nowMillis = 100_000L, staleAfterMillis = 10L)
        assertFalse(stale.exists())
        assertTrue(active.exists())
        assertTrue(unrelated.exists())
        root.deleteRecursively()
    }

    @Test fun initialLoadingAlwaysTransitionsToAContentTerminalState() = runBlocking {
        prepared("small file".toByteArray()).use {
            assertEquals("small file", it.readChunk(0))
            assertTrue(it.chunks.isNotEmpty())
        }
        prepared(byteArrayOf()).use { assertTrue(it.chunks.isEmpty()) }
        assertSame(TxtReaderContent.UnsupportedEncoding, failureContent(UnsupportedTextEncodingException()))
        assertSame(TxtReaderContent.MalformedText, failureContent(java.nio.charset.MalformedInputException(1)))
        assertFalse(failureContent(java.io.IOException()) is TxtReaderContent.Loading)
        assertSame(TxtReaderContent.NotFound, failureContent(java.io.FileNotFoundException()))
        assertSame(TxtReaderContent.AccessDenied, failureContent(SecurityException()))
    }

    @Test fun largeFilePublishesFirstChunkBeforeIndexingCompletes() = runBlocking {
        val file = File.createTempFile("txt_test_", ".cache")
        var callbacks = 0
        var chunksVisibleAtFirstCallback = -1
        val store = TextDocumentStore(file)
        try {
            IncrementalTextDecoder.decode(
                ByteArrayInputStream("नमस्ते 😀\n".repeat(20_000).toByteArray()),
                file
            ) { chunk, text ->
                store.addChunk(chunk, text)
                callbacks++
                if (callbacks == 1) chunksVisibleAtFirstCallback = store.chunks.size
            }
            assertTrue(store.chunks.size > 1)
            assertEquals(1, chunksVisibleAtFirstCallback)
            assertEquals(store.chunks.size, callbacks)
            assertTrue(store.readChunk(0).startsWith("नमस्ते 😀"))
        } finally { store.close() }
    }

    @Test fun sameStableDocumentDoesNotRestartAndCancelledGenerationCannotWin() {
        val gate = TxtLoadRequestGate()
        val old = gate.begin("document-a")!!
        assertNull(gate.begin("document-a"))
        val newer = gate.begin("document-b")!!
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(newer))
        assertNotNull(gate.begin("document-b", force = true))
    }
}
