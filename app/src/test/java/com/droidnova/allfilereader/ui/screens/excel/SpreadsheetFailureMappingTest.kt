package com.droidnova.allfilereader.ui.screens.excel

import com.droidnova.allfilereader.data.excel.SpreadsheetPreflightException
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetFailureMappingTest {
    @Test fun sourceAccessFailuresStayDistinctFromCorruption() {
        assertEquals(ExcelReaderPhase.PermissionDenied, spreadsheetFailurePhase(SecurityException()))
        assertEquals(ExcelReaderPhase.MissingFile, spreadsheetFailurePhase(FileNotFoundException()))
    }
    @Test fun encryptedWrongTypeLegacyAndCorruptionHaveStructuredResults() {
        assertEquals(ExcelReaderPhase.PasswordProtected, spreadsheetFailurePhase(SpreadsheetPreflightException.Encrypted()))
        assertEquals(ExcelReaderPhase.WrongOleFormat, spreadsheetFailurePhase(SpreadsheetPreflightException.WrongOleDocument()))
        assertEquals(ExcelReaderPhase.UnsupportedLegacyVersion, spreadsheetFailurePhase(SpreadsheetPreflightException.UnsupportedBiff()))
        assertEquals(ExcelReaderPhase.Corrupted, spreadsheetFailurePhase(SpreadsheetPreflightException.Corrupted()))
    }
}
