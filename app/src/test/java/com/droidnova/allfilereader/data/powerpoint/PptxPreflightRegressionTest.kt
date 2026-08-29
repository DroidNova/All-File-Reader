package com.droidnova.allfilereader.data.powerpoint

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxPreflightRegressionTest {
    private fun pptx(entries: List<Pair<String, ByteArray>>): File = File.createTempFile("pptx-", ".pptx").apply {
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        deleteOnExit()
    }
    private val required get() = listOf("[Content_Types].xml" to "<Types/>".toByteArray(), "ppt/presentation.xml" to "<p:presentation xmlns:p='urn:test'/>".toByteArray())
    private fun code(file: File) = try { PptxPreflight(null, file.parentFile).validate(file); "OK" } catch (error: PptxPreflightException) { error.resultCode }

    @Test fun acceptsMinimalValidAndMissingOptionalParts() { assertEquals("OK", code(pptx(required))) }
    @Test fun acceptsDifferentPrefixesAndHighCompressionWithinLimits() { val large=("<x:presentation xmlns:x='urn:test'>"+" ".repeat(200_000)+"</x:presentation>").toByteArray(); assertEquals("OK",code(pptx(listOf(required[0],"ppt/presentation.xml" to large)))) }
    @Test fun reportsMissingRequiredParts() { assertEquals("MISSING_CONTENT_TYPES",code(pptx(listOf(required[1]))));assertEquals("MISSING_PRESENTATION_PART",code(pptx(listOf(required[0])))) }
    @Test fun reportsNonZipAndEncryptedOle() { val plain=File.createTempFile("plain-",".pptx").apply{writeText("plain")};assertEquals("NOT_ZIP_CONTAINER",code(plain));val ole=File.createTempFile("ole-",".pptx").apply{writeBytes(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte()))};assertEquals("ENCRYPTED_OFFICE_FILE",code(ole)) }
    @Test fun rejectsTraversal() { assertEquals("UNSAFE_ZIP_PATH",code(pptx(required+("../escape" to byteArrayOf(1))))) }
}
