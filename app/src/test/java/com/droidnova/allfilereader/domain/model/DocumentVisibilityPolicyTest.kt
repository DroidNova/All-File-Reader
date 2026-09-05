package com.droidnova.allfilereader.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentVisibilityPolicyTest {
 @Test fun onlyProductDocumentCategoriesAreVisible(){assertEquals(listOf(DocumentCategory.Pdf,DocumentCategory.Word,DocumentCategory.Excel,DocumentCategory.PowerPoint,DocumentCategory.Text),DocumentCategory.entries.filter(DocumentClassifier::isVisibleDocument))}
 @Test fun otherAndFolderAreExcluded(){assertFalse(DocumentClassifier.isVisibleDocument(DocumentCategory.Other));assertFalse(DocumentClassifier.isVisibleDocument(DocumentCategory.Folder))}
 @Test fun mediaPackagesAndArchivesAreExcluded(){listOf("jpg","png","mp4","mp3","apk","zip","rar","7z","exe","tmp","rtf","epub").forEach{assertFalse(it,DocumentClassifier.isVisibleDocument(DocumentClassifier.classifyMetadata(null,it)))}}
 @Test fun recognizedUnsupportedOfficeDocumentsStayVisible(){listOf("doc","ppt","pptm","pps","ppsx").forEach{assertTrue(it,DocumentClassifier.isVisibleDocument(DocumentClassifier.classifyMetadata(null,it)))}}
}
