package com.droidnova.allfilereader.data.powerpoint

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PptxPreflightRegressionTest {
    private fun pptx(entries: List<Pair<String, ByteArray>>): File = File.createTempFile("pptx-", ".pptx").apply {
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        deleteOnExit()
    }
    private val required get() = listOf(
        "[Content_Types].xml" to "<Types/>".toByteArray(),
        "_rels/.rels" to "<Relationships/>".toByteArray(),
        "ppt/presentation.xml" to "<p:presentation xmlns:p='urn:test'/>".toByteArray(),
        "ppt/_rels/presentation.xml.rels" to "<Relationships/>".toByteArray()
    )
    private fun code(file: File, budget: PptxRenderBudget = PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(256, 256, false))) = runBlocking {
        try { PptxPreflight(null, PptxSessionStore(file.parentFile), budget).validate(file); "OK" }
        catch (error: PptxPreflightException) { error.resultCode }
    }
    private fun budget(entries: Int = 100, entry: Long = 1024, total: Long = 4096, ratio: Double = 200.0) =
        PptxRenderBudget(PptxBudgetCategory.LowRam, 4096, total, entry, entries, ratio, 120_000)

    @Test fun validPptxPasses() { assertEquals("OK", code(pptx(required))) }
    @Test fun missingRequiredPartsAreDistinct() {
        assertEquals("MISSING_CONTENT_TYPES", code(pptx(required.drop(1))))
        assertEquals("MISSING_ROOT_RELATIONSHIPS", code(pptx(required.filterNot { it.first == "_rels/.rels" })))
        assertEquals("MISSING_PRESENTATION_PART", code(pptx(required.filterNot { it.first == "ppt/presentation.xml" })))
        assertEquals("MISSING_PRESENTATION_RELATIONSHIPS", code(pptx(required.dropLast(1))))
    }
    @Test fun nonZipAndEncryptedOfficeFailDistinctly() {
        val plain = File.createTempFile("plain-", ".pptx").apply { writeText("plain") }
        assertEquals("NOT_ZIP_CONTAINER", code(plain))
        assertEquals("ENCRYPTED_OFFICE_FILE", code(pptx(required + ("EncryptedPackage" to byteArrayOf(1)))))
    }
    @Test fun traversalAndAbsolutePathsAreRejected() {
        assertEquals("UNSAFE_ZIP_PATH", code(pptx(required + ("../escape" to byteArrayOf(1)))))
        assertEquals("UNSAFE_ZIP_PATH", code(pptx(required + ("/absolute" to byteArrayOf(1)))))
        assertEquals("UNSAFE_ZIP_PATH", code(pptx(required + ("C:\\absolute" to byteArrayOf(1)))))
    }
    @Test fun duplicateEntriesAreRejected() {
        assertEquals("DUPLICATE_ZIP_ENTRY", code(pptx(required + listOf("ppt/media/a" to byteArrayOf(1), "ppt\\media\\a" to byteArrayOf(2)))))
    }
    @Test fun entryCountAndActualSizesAreBounded() {
        assertEquals("ENTRY_COUNT_LIMIT", code(pptx(required), budget(entries = 3)))
        assertEquals("ENTRY_SIZE_LIMIT", code(pptx(required + ("ppt/media/a" to ByteArray(80))), budget(entry = 64)))
        assertEquals("TOTAL_SIZE_LIMIT", code(pptx(required), budget(total = 20)))
    }
    @Test fun extremeCompressionRatioIsRejected() {
        assertEquals("COMPRESSION_RATIO_LIMIT", code(pptx(required + ("ppt/media/a" to ByteArray(2_000))), budget(entry = 4_000, total = 8_000, ratio = 2.0)))
    }
}
