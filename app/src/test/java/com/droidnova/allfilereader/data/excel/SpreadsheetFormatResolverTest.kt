package com.droidnova.allfilereader.data.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetFormatResolverTest {
    @Test fun resolvesEverySupportedFormatCaseInsensitively() {
        val values = mapOf("XLS" to SpreadsheetExpectedFormat.XLS,"xlsx" to SpreadsheetExpectedFormat.XLSX,"XLSM" to SpreadsheetExpectedFormat.XLSM,"xlsb" to SpreadsheetExpectedFormat.XLSB,"CSV" to SpreadsheetExpectedFormat.CSV,"tsv" to SpreadsheetExpectedFormat.TSV,"ODS" to SpreadsheetExpectedFormat.ODS)
        values.forEach { (extension, expected) -> assertEquals(expected, SpreadsheetFormatResolver.resolve(extension, null)) }
    }
    @Test fun resolvesCanonicalMimesAndAllowsGenericMime() {
        assertEquals(SpreadsheetExpectedFormat.XLS, SpreadsheetFormatResolver.resolve(null, "application/vnd.ms-excel"))
        assertEquals(SpreadsheetExpectedFormat.XLSX, SpreadsheetFormatResolver.resolve("xlsx", "application/octet-stream"))
    }
    @Test fun rejectsMimeExtensionConflict() {
        assertTrue(runCatching { SpreadsheetFormatResolver.resolve("xls", "text/csv") }.exceptionOrNull() is SpreadsheetPreflightException.FormatMismatch)
    }
}
