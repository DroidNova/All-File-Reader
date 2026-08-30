package com.droidnova.allfilereader.data.excel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyXlsPreflightTest {
    private val preflight = SpreadsheetPreflight(null, File(System.getProperty("java.io.tmpdir")))

    @Test fun validatesBiff8Biff5MultiSheetAndUppercaseFixtures() = runBlocking {
        fixture("minimal-biff8.xls").useFile { assertEquals(BiffVersion.BIFF8, preflight.validateSessionFile(it, SpreadsheetExpectedFormat.XLS).biffVersion) }
        fixture("minimal-biff5.xls").useFile { assertEquals(BiffVersion.BIFF5, preflight.validateSessionFile(it, SpreadsheetExpectedFormat.XLS).biffVersion) }
        fixture("multi-sheet-biff8.xls").useFile { assertEquals(BiffVersion.BIFF8, preflight.validateSessionFile(it, SpreadsheetExpectedFormat.XLS).biffVersion) }
        fixture("features-uppercase.XLS").useFile { assertEquals(BiffVersion.BIFF8, preflight.validateSessionFile(it, SpreadsheetExpectedFormat.XLS).biffVersion) }
    }

    @Test fun rejectsTruncatedAndInvalidHeaders() = runBlocking {
        fixture("minimal-biff8.xls").useFile { file ->
            file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
            assertReason<SpreadsheetPreflightException.Corrupted>(file)
        }
        fixture("minimal-biff8.xls").useFile { file -> file.writeBytes(ByteArray(512)); assertReason<SpreadsheetPreflightException.Corrupted>(file) }
    }

    @Test fun rejectsDirectorySectorLoopAndInvalidDirectoryEntry() = runBlocking {
        fixture("minimal-biff8.xls").useFile { file ->
            val bytes = file.readBytes(); val header = le(bytes)
            val directory = header.getInt(48); val fatSector = header.getInt(76)
            le(bytes).putInt(512 + fatSector * 512 + directory * 4, directory)
            file.writeBytes(bytes); assertReason<SpreadsheetPreflightException.Corrupted>(file)
        }
        fixture("minimal-biff8.xls").useFile { file ->
            val bytes = file.readBytes(); val base = directoryEntryBase(bytes, "Workbook")
            le(bytes).putShort(base + 64, 3); file.writeBytes(bytes)
            assertReason<SpreadsheetPreflightException.Corrupted>(file)
        }
    }

    @Test fun rejectsRenamedLegacyDocAndPowerPointContainers() = runBlocking {
        fixture("minimal-biff8.xls").useFile { file ->
            renameDirectoryEntry(file, "Workbook", "WordDocument")
            assertReason<SpreadsheetPreflightException.WrongOleDocument>(file)
        }
        fixture("minimal-biff8.xls").useFile { file ->
            renameDirectoryEntry(file, "Workbook", "PowerPoint Document")
            assertReason<SpreadsheetPreflightException.WrongOleDocument>(file)
        }
    }

    @Test fun recognizesEncryptedPackagePairBeforeLookingForWorkbook() = runBlocking {
        fixture("minimal-biff8.xls").useFile { file ->
            renameDirectoryEntry(file, "Workbook", "EncryptedPackage")
            renameDirectoryEntry(file, "\u0001Sh33tJ5", "EncryptionInfo")
            assertReason<SpreadsheetPreflightException.Encrypted>(file)
        }
    }

    @Test fun detectsFilePassAndUnsupportedBiffWithoutCallingSheetJs() {
        val inspector = CfbXlsInspector(File("unused"))
        val filePass = records(record(0x0809, 0x0600, 0x0005), record(0x002f), record(0x000a))
        assertTrue(runCatching { inspector.inspectBiffForTest(filePass) }.exceptionOrNull() is SpreadsheetPreflightException.Encrypted)
        val old = records(record(0x0809, 0x0400, 0x0005), record(0x000a))
        assertTrue(runCatching { inspector.inspectBiffForTest(old) }.exceptionOrNull() is SpreadsheetPreflightException.UnsupportedBiff)
    }

    @Test fun rejectsDeclaredWorkbookResourceLimit() = runBlocking {
        fixture("minimal-biff8.xls").useFile { file ->
            val bytes = file.readBytes(); val base = directoryEntryBase(bytes, "Workbook")
            le(bytes).putInt(base + 120, (SpreadsheetLimits.MAX_INPUT_BYTES + 1).toInt()); file.writeBytes(bytes)
            assertReason<SpreadsheetPreflightException.Safety>(file)
        }
    }

    @Test fun cancellationIsObserved() = runBlocking {
        fixture("multi-sheet-biff8.xls").useFile { file ->
            kotlinx.coroutines.coroutineScope {
                val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { preflight.validateSessionFile(file, SpreadsheetExpectedFormat.XLS) }
                job.cancel(CancellationException("test cancellation")); job.start(); job.join()
                assertTrue(job.isCancelled)
            }
        }
    }

    private suspend inline fun <reified T : Throwable> assertReason(file: File) {
        assertTrue(runCatching { preflight.validateSessionFile(file, SpreadsheetExpectedFormat.XLS) }.exceptionOrNull() is T)
    }
    private fun fixture(name: String): File {
        val encoded = checkNotNull(javaClass.classLoader?.getResourceAsStream("xls/$name.b64"))
            .bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val decoded = Base64.getMimeDecoder().decode(encoded)
        assertEquals(checkNotNull(FIXTURE_HASHES[name]), sha256(decoded))
        return File.createTempFile("xls_fixture_", ".xls").apply { writeBytes(decoded) }
    }
    private suspend inline fun File.useFile(crossinline block: suspend (File) -> Unit) { try { block(this) } finally { delete() } }
    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun directoryEntryBase(bytes: ByteArray, name: String): Int {
        val needle = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
        val found = bytes.indexOfSlice(needle)
        check(found >= 0) { "directory entry not found: $name" }
        return found / 128 * 128
    }
    private fun renameDirectoryEntry(file: File, old: String, new: String) {
        val bytes = file.readBytes(); val base = directoryEntryBase(bytes, old); val encoded = new.toByteArray(Charsets.UTF_16LE)
        check(encoded.size + 2 <= 64)
        bytes.fill(0, base, base + 64); encoded.copyInto(bytes, base)
        le(bytes).putShort(base + 64, (encoded.size + 2).toShort()); file.writeBytes(bytes)
    }
    private fun ByteArray.indexOfSlice(needle: ByteArray): Int = indices.firstOrNull { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } } ?: -1
    private fun record(id: Int, vararg words: Int): ByteArray = ByteBuffer.allocate(4 + words.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(id.toShort()); putShort((words.size * 2).toShort()); words.forEach { putShort(it.toShort()) } }.array()
    private fun records(vararg values: ByteArray) = values.fold(ByteArray(0)) { result, value -> result + value }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        val FIXTURE_HASHES = mapOf(
            "minimal-biff8.xls" to "4fabc7b9d1bf34a4dbfea3e5e6b2826efb1d352a0a45b241ca9b4e22c5339a5d",
            "multi-sheet-biff8.xls" to "c71e87832ea2739eab1f93c14aac481b826e2f4fef0f2dbabfebee139b8149de",
            "features-uppercase.XLS" to "66563ce914a958bdbe81d6e278ae660ac3db359146182fc205fe12330c768f08",
            "empty-sheet-biff8.xls" to "08b65d4e6902b457be014ffd8c731d45f3b9bfdba161d0b59ea42162211a89bd",
            "minimal-biff5.xls" to "4892979c1de6afd86acf74ed1765545da503c354da84de0cf354cdb44b405b71"
        )
    }
}
