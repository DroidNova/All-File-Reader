package com.droidnova.allfilereader.data.excel

import org.junit.Assert.*
import org.junit.Test

class SpreadsheetRenderBudgetTest {
    @Test fun profilesScaleWithinHardCaps() {
        val low = SpreadsheetBudgetPolicy.forProfile(SpreadsheetDeviceProfile(128, 128, true))
        val normal = SpreadsheetBudgetPolicy.forProfile(SpreadsheetDeviceProfile(256, 512, false))
        val high = SpreadsheetBudgetPolicy.forProfile(SpreadsheetDeviceProfile(512, 512, false))
        assertEquals("low", low.profileName); assertEquals(16L * 1024 * 1024, low.maxWorkbookBytes)
        assertEquals("normal", normal.profileName); assertEquals(32L * 1024 * 1024, normal.maxWorkbookBytes)
        assertEquals("high", high.profileName); assertEquals(SpreadsheetBudgetPolicy.ABSOLUTE_MAX_WORKBOOK_BYTES, high.maxWorkbookBytes)
        assertTrue(low.normalDomCellLimit < normal.normalDomCellLimit && normal.normalDomCellLimit < high.normalDomCellLimit)
    }

    @Test fun invalidMetadataFallsBackAndBoundariesAreExact() {
        val budget = SpreadsheetBudgetPolicy.forProfile(SpreadsheetDeviceProfile(-1, 0, false))
        assertEquals("normal", budget.profileName)
        assertTrue(budget.acceptsWorkbook(budget.maxWorkbookBytes))
        assertFalse(budget.acceptsWorkbook(budget.maxWorkbookBytes + 1))
        assertFalse(budget.acceptsWorkbook(0))
        assertFalse(budget.acceptsWorkbook(Long.MAX_VALUE))
    }
}
