package com.droidnova.allfilereader.data.word
import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class DocxStreamingCopyTest{
 @Test fun actualLengthIsAuthoritativeAndBoundaryIsInclusive()=runBlocking{val data=ByteArray(32769){it.toByte()};val f=temp();try{assertEquals(data.size.toLong(),copyDocxStream(ByteArrayInputStream(data),f,data.size.toLong()));assertArrayEquals(data,f.readBytes())}finally{f.delete()}}
 @Test fun oneByteOverStopsAndDeletesPartial()=runBlocking{val f=temp();assertTrue(runCatching{copyDocxStream(ByteArrayInputStream(ByteArray(11)),f,10)}.exceptionOrNull() is DocxPreflightException.TooLarge);assertFalse(f.exists())}
 @Test fun emptyIsReportedByActualCount()=runBlocking{val f=temp();try{assertEquals(0L,copyDocxStream(ByteArrayInputStream(byteArrayOf()),f,10))}finally{f.delete()}}
 @Test fun readFailureAndCancellationDeletePartial()=runBlocking{for(error in listOf(IOException(),CancellationException())){val f=temp();val input=object:InputStream(){override fun read():Int=throw error};assertTrue(runCatching{copyDocxStream(input,f,10)}.isFailure);assertFalse(f.exists())}}
 private fun temp()=File.createTempFile("docx_copy_",".docx").apply{delete()}
}
