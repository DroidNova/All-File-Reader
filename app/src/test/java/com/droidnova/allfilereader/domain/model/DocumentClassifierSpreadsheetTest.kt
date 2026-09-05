package com.droidnova.allfilereader.domain.model
import org.junit.Assert.assertEquals
import org.junit.Test
class DocumentClassifierSpreadsheetTest {
 @Test fun classifiesCommonSpreadsheetExtensions(){listOf("xlsx","XLS","xlsm","xlsb","ods","csv","tsv").forEach{assertEquals(DocumentCategory.Excel,DocumentClassifier.classifyMetadata(null,it).category)}}
 @Test fun classifiesCommonSpreadsheetMimes(){listOf("application/octet-stream" to null,"text/csv" to DocumentCategory.Excel,"application/vnd.oasis.opendocument.spreadsheet" to DocumentCategory.Excel).forEach{(mime,expected)->assertEquals(expected,DocumentClassifier.classifyMetadata(mime,null).category)}}
}
