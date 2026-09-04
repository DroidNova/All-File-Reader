package com.droidnova.allfilereader.ui.screens.home

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFavoritesCountTest {
    private fun document(id: String) = DocumentFile(
        id, "$id.pdf", "file:///$id.pdf", "application/pdf", "pdf", 1, 1,
        DocumentCategory.Pdf, false
    )

    @Test fun countIncludesOnlyAvailableUniqueFavorites() {
        assertEquals(1, availableFavoriteCount(listOf(document("a"), document("a"), document("b")), setOf("a", "missing")))
    }

    @Test fun unavailableFavoriteIsRestoredWhenDocumentReappears() {
        val ids = setOf("a")
        assertEquals(0, availableFavoriteCount(emptyList(), ids))
        assertEquals(1, availableFavoriteCount(listOf(document("a")), ids))
    }
}
