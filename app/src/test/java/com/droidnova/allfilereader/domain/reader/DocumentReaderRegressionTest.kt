package com.droidnova.allfilereader.domain.reader

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentReaderRegressionTest {
    @Test fun existingReaderRoutesRemainUnchanged() {
        listOf(
            DocumentCategory.Pdf to DocumentReaderDestination.Pdf,
            DocumentCategory.Text to DocumentReaderDestination.PlainText,
            DocumentCategory.Word to DocumentReaderDestination.Docx,
            DocumentCategory.Excel to DocumentReaderDestination.Spreadsheet,
            DocumentCategory.PowerPoint to DocumentReaderDestination.PowerPoint
        ).forEach { (category, expected) ->
            val extension = when(category){DocumentCategory.Pdf->"pdf";DocumentCategory.Text->"txt";DocumentCategory.Word->"docx";DocumentCategory.Excel->"xls";else->"pptx"}
            val document = DocumentFile("id", "file.$extension", "file:///test", null, extension, 1, 1, category, false)
            assertEquals(DocumentOpenResult.Internal(expected), DocumentReaderResolver.resolve(document))
        }
    }
    @Test fun everySpreadsheetExtensionStillUsesInternalSpreadsheetRoute() {
        listOf("xls","xlsx","xlsm","xlsb","csv","tsv","ods").forEach { extension ->
            val document = DocumentFile("id", "file.$extension", "file:///test", null, extension, 1, 1, DocumentCategory.Excel, false)
            assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.Spreadsheet), DocumentReaderResolver.resolve(document))
        }
    }
    @Test fun legacyPptAndPptxKeepTheirDedicatedRoutes() {
        listOf("ppt", "pptx").forEach { extension ->
            val document = DocumentFile("id", "file.$extension", "file:///test", null, extension, 1, 1, DocumentCategory.PowerPoint, false)
            assertEquals(if(extension=="ppt") DocumentOpenResult.LegacyPowerPoint else DocumentOpenResult.Internal(DocumentReaderDestination.PowerPoint), DocumentReaderResolver.resolve(document))
        }
    }
}
