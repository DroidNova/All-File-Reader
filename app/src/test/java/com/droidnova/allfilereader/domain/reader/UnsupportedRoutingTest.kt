package com.droidnova.allfilereader.domain.reader
import com.droidnova.allfilereader.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
class UnsupportedRoutingTest {
 private fun file(ext:String,category:DocumentCategory=DocumentClassifier.classifyMetadata(null,ext).category ?: DocumentCategory.Other)=DocumentFile(ext,"file.$ext","file:///file.$ext",null,ext,1,1,category,false)
 @Test fun legacyDocAndUnimplementedFormatsAreUnsupported(){listOf("doc","rtf","odt","epub","unknown","pptm","pps","ppsx").forEach{assertEquals(it,DocumentOpenResult.Unsupported,DocumentReaderResolver.resolve(file(it)))}}
 @Test fun pptKeepsDedicatedFallback(){assertEquals(DocumentOpenResult.LegacyPowerPoint,DocumentReaderResolver.resolve(file("ppt")))}
 @Test fun unsupportedIsDistinctFromOperationalErrors(){assertNotEquals(DocumentOpenResult.Unsupported as DocumentOpenResult,DocumentOpenResult.AccessFailure);assertNotEquals(DocumentOpenResult.Unsupported as DocumentOpenResult,DocumentOpenResult.FormatMismatch)}
}
