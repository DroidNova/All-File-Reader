package com.droidnova.allfilereader.data.excel

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpreadsheetStreamingCopyTest {
    @Test fun actualBytesAreAuthoritativeForKnownOrUnknownMetadata() = runBlocking {
        val bytes = ByteArray(32_769) { (it % 251).toByte() }; val target = temp()
        try { assertEquals(bytes.size.toLong(), copyWorkbookStream(ByteArrayInputStream(bytes), target, bytes.size.toLong()));assertArrayEquals(bytes,target.readBytes()) } finally { target.delete() }
    }
    @Test fun exactLimitSucceedsAndOneByteOverStopsAndCleansPartialFile() = runBlocking {
        val bytes=ByteArray(10);val exact=temp();assertEquals(10L,copyWorkbookStream(ByteArrayInputStream(bytes),exact,10));exact.delete()
        val over=temp();assertTrue(runCatching{copyWorkbookStream(ByteArrayInputStream(bytes),over,9)}.exceptionOrNull() is SpreadsheetPreflightException.TooLarge);assertFalse(over.exists())
    }
    @Test fun emptyStreamIsCountedWithoutFailure() = runBlocking { val target=temp();try{assertEquals(0L,copyWorkbookStream(ByteArrayInputStream(byteArrayOf()),target,10))}finally{target.delete()} }
    @Test fun readFailureDeletesPartialFile() = runBlocking { val target=temp();val input=object:InputStream(){var count=0;override fun read():Int=if(count++<4)1 else throw IOException()};assertTrue(runCatching{copyWorkbookStream(input,target,100)}.isFailure);assertFalse(target.exists()) }
    @Test fun cancellationDeletesPartialFile() = runBlocking { val target=temp();val input=object:InputStream(){override fun read():Int{throw CancellationException("cancel")}};assertTrue(runCatching{copyWorkbookStream(input,target,100)}.exceptionOrNull() is CancellationException);assertFalse(target.exists()) }
    private fun temp()=File.createTempFile("spreadsheet_copy_",".bin").apply{delete()}
}
