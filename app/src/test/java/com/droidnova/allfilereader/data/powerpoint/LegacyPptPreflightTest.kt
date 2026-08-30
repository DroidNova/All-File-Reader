package com.droidnova.allfilereader.data.powerpoint

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyPptPreflightTest {
    @Test fun acceptsOleCompoundFileSignature() {
        assertEquals(null, LegacyPptSourceResolver.inspect(ByteArrayInputStream(OLE_SIGNATURE)).code)
    }

    @Test fun rejectsInvalidAndEmptyInputs() {
        assertEquals(LegacyPptErrorCode.INVALID_OLE_SIGNATURE, LegacyPptSourceResolver.inspect(ByteArrayInputStream("not a ppt".toByteArray())).code)
        assertEquals(LegacyPptErrorCode.INPUT_EMPTY, LegacyPptSourceResolver.inspect(ByteArrayInputStream(byteArrayOf())).code)
    }

    @Test fun combinesShortReadsAndStopsAfterHeader() {
        val source = object : ByteArrayInputStream(OLE_SIGNATURE + ByteArray(1024)) {
            var bytesRead = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(2, length)).also { if (it > 0) bytesRead += it }
        }
        assertEquals(null, LegacyPptSourceResolver.inspect(source).code)
        assertEquals(8, source.bytesRead)
    }

    companion object {
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
