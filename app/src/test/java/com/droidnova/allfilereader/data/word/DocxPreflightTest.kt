package com.droidnova.allfilereader.data.word
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class DocxPreflightTest{
 private val preflight=DocxPreflight(null,File(System.getProperty("java.io.tmpdir")));private val budget=DocxBudgetPolicy.forProfile(DocxDeviceProfile(512,512,false))
 @Test fun minimalRequiredPackageIsAcceptedWithoutOptionalParts()=runBlocking{val f=docx();try{val result=preflight.validateSessionFile(f,budget);assertEquals(3,result.first)}finally{f.delete()}}
 @Test fun requiredPartsAreRequired()=runBlocking{for(missing in listOf("[Content_Types].xml","_rels/.rels","word/document.xml")){val f=docx(missing);try{assertTrue(runCatching{preflight.validateSessionFile(f,budget)}.exceptionOrNull() is DocxPreflightException.MissingParts)}finally{f.delete()}}}
 @Test fun nonZipTraversalAbsoluteAndDuplicateNamesAreRejected()=runBlocking{val bad=File.createTempFile("not_docx_",".docx").apply{writeText("bad")};try{assertTrue(runCatching{preflight.validateSessionFile(bad,budget)}.exceptionOrNull() is DocxPreflightException.Invalid)}finally{bad.delete()};for(name in listOf("../bad","/bad","word/../bad")){val f=zip(listOf(name to byteArrayOf(1)));try{assertTrue(runCatching{preflight.validateSessionFile(f,budget)}.exceptionOrNull() is DocxPreflightException.Unsafe)}finally{f.delete()}}}
 @Test fun encryptedMacroAndExternalEntityPackagesAreRejected()=runBlocking{val encrypted=docx(extra=listOf("EncryptedPackage" to byteArrayOf(1)));try{assertTrue(runCatching{preflight.validateSessionFile(encrypted,budget)}.exceptionOrNull() is DocxPreflightException.Unsupported)}finally{encrypted.delete()};val macro=docx(types="macroEnabled vbaProject");try{assertTrue(runCatching{preflight.validateSessionFile(macro,budget)}.exceptionOrNull() is DocxPreflightException.Unsupported)}finally{macro.delete()};val entity=docx(xml="<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///x'>]><w:document/>");try{assertTrue(runCatching{preflight.validateSessionFile(entity,budget)}.exceptionOrNull() is DocxPreflightException.Unsafe)}finally{entity.delete()}}
 @Test fun structuralComplexityIsBounded()=runBlocking{val tiny=budget.copy(maxParagraphCount=2);val f=docx(xml="<w:document xmlns:w='x'><w:p/><w:p/><w:p/></w:document>");try{assertTrue(runCatching{preflight.validateSessionFile(f,tiny)}.exceptionOrNull() is DocxPreflightException.TooComplex)}finally{f.delete()}}
 private fun docx(missing:String?=null,types:String="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",xml:String="<w:document xmlns:w='x'><w:p/></w:document>",extra:List<Pair<String,ByteArray>> = emptyList()):File{val entries=listOf("[Content_Types].xml" to types.toByteArray(),"_rels/.rels" to "<Relationships/>".toByteArray(),"word/document.xml" to xml.toByteArray()).filter{it.first!=missing}+extra;return zip(entries)}
 private fun zip(entries:List<Pair<String,ByteArray>>)=File.createTempFile("docx_test_",".docx").also{f->ZipOutputStream(f.outputStream()).use{out->for((name,data)in entries){out.putNextEntry(ZipEntry(name));out.write(data);out.closeEntry()}}}
}
