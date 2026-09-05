package com.droidnova.allfilereader.data.powerpoint

import org.junit.Assert.*
import org.junit.Test

class PptxRenderBudgetTest {
    @Test fun lowRamDeviceGetsConservativeBudget() {
        val value = PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(512, 512, true))
        assertEquals(PptxBudgetCategory.LowRam, value.category)
        assertEquals(64L * 1024 * 1024, value.maxCompressedBytes)
        assertEquals(250, value.maxStoredSearchMatches)
    }
    @Test fun ordinaryDeviceGetsStandardBudgetAtBoundaries() {
        assertEquals(PptxBudgetCategory.LowRam, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(191, 256, false)).category)
        assertEquals(PptxBudgetCategory.Standard, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(192, 256, false)).category)
        assertEquals(PptxBudgetCategory.Standard, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(383, 512, false)).category)
        assertEquals(500, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(256, 256, false)).maxStoredSearchMatches)
    }
    @Test fun highMemoryBudgetIsLargerAndAbsolutelyCapped() {
        val boundary = PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(384, 512, false))
        val enormous = PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(Int.MAX_VALUE, Int.MAX_VALUE, false))
        assertEquals(PptxBudgetCategory.HighMemory, boundary.category)
        assertEquals(boundary, enormous)
        assertEquals(256L * 1024 * 1024, enormous.maxCompressedBytes)
        assertEquals(PptxRenderBudgetPolicy.HARD_MAX_STORED_SEARCH_MATCHES, enormous.maxStoredSearchMatches)
    }
    @Test fun invalidMemoryMetadataFallsBackSafely() {
        assertEquals(PptxBudgetCategory.LowRam, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(-1, -1, false)).category)
        assertEquals(250, PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(-1, -1, false)).maxStoredSearchMatches)
    }
    @Test fun sizeBoundaryAndOverflowLikeValuesCannotBypassLimit() {
        val value = PptxRenderBudgetPolicy.forProfile(PptxDeviceProfile(256, 256, false))
        assertTrue(value.acceptsCompressedSize(value.maxCompressedBytes))
        assertFalse(value.acceptsCompressedSize(value.maxCompressedBytes + 1))
        assertFalse(value.acceptsCompressedSize(-1))
        assertFalse(value.acceptsCompressedSize(Long.MAX_VALUE))
    }
}
