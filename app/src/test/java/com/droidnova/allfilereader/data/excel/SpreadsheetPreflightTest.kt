package com.droidnova.allfilereader.data.excel
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class SpreadsheetPreflightTest {
 private val preflight=SpreadsheetPreflight(null,File(System.getProperty("java.io.tmpdir")))
 @Test fun acceptsNonDefaultStrictTargetAndCompressibleXml()=runBlocking{val f=validZip("custom/main");try{assertEquals(SpreadsheetSignature.ZIP_OPC,preflight.inspectSessionFile(f))}finally{f.delete()}}
 @Test fun rejectsContainerMissingWorkbookParts()=runBlocking{val f=zip("unexpected/producer/data.xml" to "<data/>".toByteArray());try{assertTrue(runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull() is SpreadsheetPreflightException.Corrupted)}finally{f.delete()}}
 @Test fun rejectsAbsolutePath()=runBlocking{val f=zip("/escape.xml" to byteArrayOf(1));try{val e=runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull();assertEquals(PreflightReason.PATH_TRAVERSAL,(e as SpreadsheetPreflightException).reason)}finally{f.delete()}}
 @Test fun rejectsMissingContentTypesRelationshipsAndWorksheet()=runBlocking{for(entries in listOf(arrayOf("xl/workbook.xml" to byteArrayOf(1)),arrayOf("[Content_Types].xml" to byteArrayOf(1),"xl/workbook.xml" to byteArrayOf(1)),arrayOf("[Content_Types].xml" to byteArrayOf(1),"xl/workbook.xml" to byteArrayOf(1),"xl/_rels/workbook.xml.rels" to byteArrayOf(1)))){val f=zip(*entries);try{assertTrue(runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull() is SpreadsheetPreflightException.Corrupted)}finally{f.delete()}}}
 @Test fun rejectsTraversal()=runBlocking{val f=zip("../escape.xml" to byteArrayOf(1));try{val e=runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull();assertTrue(e is SpreadsheetPreflightException.Safety);assertEquals(PreflightReason.PATH_TRAVERSAL,(e as SpreadsheetPreflightException).reason)}finally{f.delete()}}
 @Test fun rejectsCorruptedZip()=runBlocking{val f=File.createTempFile("corrupt_",".zip").apply{writeBytes(byteArrayOf(0x50,0x4b,0x03,0x04,1,2,3))};try{assertTrue(runCatching{preflight.inspectSessionFile(f)}.exceptionOrNull() is SpreadsheetPreflightException.Corrupted)}finally{f.delete()}}
 private fun zip(vararg entries:Pair<String,ByteArray>)=File.createTempFile("sheet_",".zip").also{f->ZipOutputStream(f.outputStream()).use{out->entries.forEach{(n,d)->out.putNextEntry(ZipEntry(n));out.write(d);out.closeEntry()}}}
 private fun validZip(dir:String="xl")=zip("[Content_Types].xml" to "<Types/>".toByteArray(),"$dir/workbook.xml" to "<workbook/>".toByteArray(),"$dir/_rels/workbook.xml.rels" to "<Relationships/>".toByteArray(),"$dir/worksheets/sheet1.xml" to "<worksheet/>".toByteArray())
}
