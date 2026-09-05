package com.droidnova.allfilereader.domain.model
import com.droidnova.allfilereader.domain.reader.*
import org.junit.Assert.assertEquals
import org.junit.Test
class DocumentClassifierPowerPointTest {
 @Test fun classifiesPptxCaseAndMime(){assertEquals(DocumentCategory.PowerPoint,DocumentClassifier.classify("application/octet-stream","PPTX"));assertEquals(DocumentCategory.PowerPoint,DocumentClassifier.classify("application/vnd.openxmlformats-officedocument.presentationml.presentation",null))}
 @Test fun classifiesUnsupportedPowerPointFormats(){listOf("ppt","PPT","PPTM","pps","ppsx").forEach{assertEquals(DocumentCategory.PowerPoint,DocumentClassifier.classify(null,it))}}
 @Test fun routesPowerPointWithoutRegressingOthers(){fun d(c:DocumentCategory,e:String)=DocumentFile("id","x.$e","file:///x",null,e,1,1,c,false);assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.PowerPoint),DocumentReaderResolver.resolve(d(DocumentCategory.PowerPoint,"pptx")));assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.Pdf),DocumentReaderResolver.resolve(d(DocumentCategory.Pdf,"pdf")));assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.Docx),DocumentReaderResolver.resolve(d(DocumentCategory.Word,"docx")));assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.Spreadsheet),DocumentReaderResolver.resolve(d(DocumentCategory.Excel,"xlsx")));assertEquals(DocumentOpenResult.Internal(DocumentReaderDestination.PlainText),DocumentReaderResolver.resolve(d(DocumentCategory.Text,"txt")))}
}
