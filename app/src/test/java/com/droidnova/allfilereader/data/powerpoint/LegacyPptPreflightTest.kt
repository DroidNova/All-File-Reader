package com.droidnova.allfilereader.data.powerpoint

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyPptPreflightTest {
    @Test fun acceptsOleCompoundFileSignature() {
        assertEquals(LegacyPptValidation.Valid, LegacyPptPreflight.inspect(ByteArrayInputStream(OLE_SIGNATURE + byteArrayOf(1, 2, 3))))
    }

    @Test fun rejectsInvalidSignature() {
        assertEquals(LegacyPptValidation.InvalidSignature, LegacyPptPreflight.inspect(ByteArrayInputStream("not a ppt".toByteArray())))
    }

    @Test fun rejectsEmptyFile() {
        assertEquals(LegacyPptValidation.Empty, LegacyPptPreflight.inspect(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun reportsMissingAndUnreadableStreams() {
        assertEquals(LegacyPptValidation.Unreadable, LegacyPptPreflight.validate { null })
        assertEquals(LegacyPptValidation.Unreadable, LegacyPptPreflight.validate { throw IOException("unreadable") })
        assertEquals(LegacyPptValidation.Unreadable, LegacyPptPreflight.validate { throw SecurityException("denied") })
    }

    @Test fun handlesShortReadsWithoutReadingTheWholeFile() {
        val source = object : ByteArrayInputStream(OLE_SIGNATURE + ByteArray(1024)) {
            var bytesRead = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(2, length)).also { if (it > 0) bytesRead += it }
        }
        assertEquals(LegacyPptValidation.Valid, LegacyPptPreflight.inspect(source))
        assertEquals(8, source.bytesRead)
    }

    companion object {
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
