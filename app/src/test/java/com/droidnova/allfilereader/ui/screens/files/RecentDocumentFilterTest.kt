package com.droidnova.allfilereader.ui.screens.files

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDocumentFilterTest {
    @Test fun `all is the default and accepts every supported category`() {
        assertEquals(RecentDocumentFilter.All, RecentDocumentFilter.entries.first())
        assertTrue(RecentDocumentFilter.All.matches(file("anything.PDF", DocumentCategory.Pdf)))
    }

    @Test fun `filters use shared case insensitive document classification`() {
        val cases = listOf(
            RecentDocumentFilter.Pdf to "A.PDF", RecentDocumentFilter.Word to "B.DOCX",
            RecentDocumentFilter.Excel to "C.XLS", RecentDocumentFilter.Excel to "D.CSV",
            RecentDocumentFilter.Excel to "E.ODS", RecentDocumentFilter.PowerPoint to "F.PPTM",
            RecentDocumentFilter.Text to "G.TXT"
        )
        cases.forEach { (filter, name) ->
            val document = file(name, filter.category!!)
            assertTrue("$filter should match $name", filter.matches(document))
            RecentDocumentFilter.entries.filter { it != RecentDocumentFilter.All && it != filter }
                .forEach { assertFalse("$it should not match $name", it.matches(document)) }
        }
    }

    @Test fun `filtering retains source sort order and rapid selection has no shared results`() {
        val sorted = listOf(file("new.pdf", DocumentCategory.Pdf, 3), file("middle.txt", DocumentCategory.Text, 2), file("old.pdf", DocumentCategory.Pdf, 1))
        assertEquals(listOf("new.pdf", "old.pdf"), sorted.filter(RecentDocumentFilter.Pdf::matches).map { it.displayName })
        assertEquals(listOf("middle.txt"), sorted.filter(RecentDocumentFilter.Text::matches).map { it.displayName })
        assertEquals(sorted, sorted.filter(RecentDocumentFilter.All::matches))
    }

    private fun file(name: String, category: DocumentCategory, modified: Long = 0) = DocumentFile(
        id = name, displayName = name, uri = "file:///$name", mimeType = null,
        extension = name.substringAfterLast('.'), sizeBytes = 1, lastModifiedEpochMillis = modified,
        category = category, isBookmarked = false
    )
}
