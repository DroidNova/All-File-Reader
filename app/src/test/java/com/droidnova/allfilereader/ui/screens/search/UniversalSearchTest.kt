package com.droidnova.allfilereader.ui.screens.search
import com.droidnova.allfilereader.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class UniversalSearchTest {
 private fun f(name:String,time:Long=1)=DocumentFile(name,name,"file:///$name",null,name.substringAfterLast('.',""),1,time,DocumentCategory.Other,false)
 @Test fun matchingIsUnicodeNormalizedCaseInsensitiveAndIncludesExtension(){val files=listOf(f("Résumé.PDF"),f("notes.txt"));assertEquals("Résumé.PDF",SearchViewModel.rank(files,SearchViewModel.normalize("re\u0301SUME\u0301")).single().displayName);assertEquals("notes.txt",SearchViewModel.rank(files,"txt").single().displayName)}
 @Test fun exactPrefixTokenContainsRankingIsDeterministic(){val r=SearchViewModel.rank(listOf(f("my report.txt",4),f("report old",3),f("xreport",2),f("report",1)),"report");assertEquals(listOf("report","report old","my report.txt","xreport"),r.map{it.displayName})}
 @Test fun emptyQueryReturnsNoRows(){assertTrue(SearchViewModel.rank(listOf(f("a")),"").isEmpty())}
 @Test fun supportsLargeMetadataSnapshot(){assertEquals(10_000,SearchViewModel.rank((0 until 10_000).map{f("match-$it")},"match").size)}
 @Test fun maximumQueryIsDocumented(){assertEquals(200,MAX_SEARCH_QUERY_LENGTH)}
}
