package com.droidnova.allfilereader.domain.search

import com.droidnova.allfilereader.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class FilenameSearchTest {
 private fun f(name:String,time:Long=1,category:DocumentCategory=DocumentCategory.Pdf)=DocumentFile(name,name,"file:///$name",null,name.substringAfterLast('.',""),1,time,category,false)
 @Test fun emptyQueryReturnsVisibleDocumentsOnly(){assertEquals(listOf("a.pdf"),FilenameSearch.search(listOf(f("a.pdf"),f("photo.jpg",category=DocumentCategory.Other)),"").map{it.displayName})}
 @Test fun matchingSupportsCaseUnicodeEmojiExtensionsAndMultipleTerms(){val files=listOf(f("Résumé 😀 REPORT.PDF"),f("budget.xlsx",category=DocumentCategory.Excel));assertEquals("Résumé 😀 REPORT.PDF",FilenameSearch.search(files,FilenameSearch.query("re\u0301sume\u0301 😀 pdf")).single().displayName)}
 @Test fun ranksExactStemExactPrefixTokenAndContains(){val result=FilenameSearch.search(listOf(f("xreport.pdf",5),f("my report.pdf",4),f("report old.pdf",3),f("report.pdf",2),f("report",1)),"report");assertEquals(listOf("report","report.pdf","report old.pdf","my report.pdf","xreport.pdf"),result.map{it.displayName})}
 @Test fun rangeMappingFindsEveryCaseInsensitiveUnicodeMatch(){val name="É-é-REPORT-report-😀";val unicode=FilenameSearch.matchRanges(name,"e\u0301");assertEquals(2,unicode.size);val ascii=FilenameSearch.matchRanges(name,"report");assertEquals(listOf("REPORT","report"),ascii.map{name.substring(it)})}
 @Test fun excludedMetadataNeverAppearsAndLargeCollectionsRemainSupported(){val source=(0 until 20_000).map{f("report-$it.pdf") }+f("report.jpg",category=DocumentCategory.Other);assertEquals(20_000,FilenameSearch.search(source,"report").size)}
 @Test fun queryIsTrimmedAndBounded(){assertEquals("report",FilenameSearch.query("  REPORT  "));assertEquals(FilenameSearch.MAX_QUERY_LENGTH,FilenameSearch.query("x".repeat(300)).length)}
}
