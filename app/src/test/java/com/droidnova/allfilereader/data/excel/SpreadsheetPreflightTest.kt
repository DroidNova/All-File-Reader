package com.droidnova.allfilereader.data.excel
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class SpreadsheetPreflightTest {
 private val preflight=SpreadsheetPreflight(null,File(System.getProperty("java.io.tmpdir")))
 @Test fun acceptsNonDefaultStrictTargetAndCompressibleXml()=runBlocking{val f=zip("_rels/.rels" to """<Relationships><Relationship Type="http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument" Target="custom/main/workbook.xml"/></Relationships>""".toByteArray(),"custom/main/workbook.xml" to "<workbook>${"x".repeat(2_000_000)}</workbook>".toByteArray());try{assertEquals(SpreadsheetSignature.ZIP_OPC,preflight.inspectSessionFile(f))}finally{f.delete()}}
 @Test fun acceptsDataDescriptorAndNoFixedWorkbookPath()=runBlocking{val f=zip("unexpected/producer/data.xml" to "<data/>".toByteArray());try{assertEquals(SpreadsheetSignature.ZIP_OPC,preflight.inspectSessionFile(f))}finally{f.delete()}}
 @Test fun rejectsTraversal()=runBlocking{val f=zip("../escape.xml" to byteArrayOf(1));try{val e=runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull();assertTrue(e is SpreadsheetPreflightException.Safety);assertEquals(PreflightReason.PATH_TRAVERSAL,(e as SpreadsheetPreflightException).reason)}finally{f.delete()}}
 @Test fun rejectsCorruptedZip()=runBlocking{val f=File.createTempFile("corrupt_",".zip").apply{writeBytes(byteArrayOf(0x50,0x4b,0x03,0x04,1,2,3))};try{assertTrue(runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull() is SpreadsheetPreflightException.Corrupted)}finally{f.delete()}}
 private fun zip(vararg entries:Pair<String,ByteArray>)=File.createTempFile("sheet_",".zip").also{f->ZipOutputStream(f.outputStream()).use{out->entries.forEach{(n,d)->out.putNextEntry(ZipEntry(n));out.write(d);out.closeEntry()}}}
}
