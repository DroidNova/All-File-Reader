package com.droidnova.allfilereader.data.powerpoint

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PptxStreamingCopyTest {
    @Test fun unknownDeclaredLengthIsAcceptedAndStreamCountIsAuthoritative() {
        assertFalse(declaredSizeExceedsLimit(null, 4))
        assertFalse(declaredSizeExceedsLimit(-1, 4))
        val output = File.createTempFile("pptx-copy-", ".tmp")
        assertEquals(4, copyPptxStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 4))
        assertEquals(4, output.length())
    }
    @Test fun actualBytesOverLimitStopAndDeletePartialOutput() {
        val output = File.createTempFile("pptx-copy-", ".tmp")
        assertThrows(PptxPreflightException.TooLarge::class.java) { copyPptxStream(ByteArrayInputStream(ByteArray(9)), output, 8) }
        assertFalse(output.exists())
    }
    @Test fun readFailureDeletesPartialOutput() {
        val output = File.createTempFile("pptx-copy-", ".tmp")
        val failing = object : InputStream() { var count = 0; override fun read(): Int = if (count++ < 2) 1 else throw IOException() }
        assertThrows(IOException::class.java) { copyPptxStream(failing, output, 8) }
        assertFalse(output.exists())
    }
    @Test fun cancellationDeletesPartialOutput() {
        val output = File.createTempFile("pptx-copy-", ".tmp")
        assertThrows(CancellationException::class.java) { copyPptxStream(ByteArrayInputStream(ByteArray(64 * 1024)), output, 128 * 1024) { throw CancellationException() } }
        assertFalse(output.exists())
    }
    @Test fun emptyStreamReturnsZeroForExplicitEmptyFileMapping() {
        val output = File.createTempFile("pptx-copy-", ".tmp")
        assertEquals(0, copyPptxStream(ByteArrayInputStream(ByteArray(0)), output, 8))
    }
}
