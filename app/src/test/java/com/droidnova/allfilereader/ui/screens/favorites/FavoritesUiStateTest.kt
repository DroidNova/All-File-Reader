package com.droidnova.allfilereader.ui.screens.favorites

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesUiStateTest {
    private fun document(id: String, category: DocumentCategory = DocumentCategory.Pdf) = DocumentFile(
        id, "$id.pdf", "file:///$id.pdf", "application/pdf", "pdf", 0, 0, category, false
    )

    @Test fun noPersistedIdsProducesGenuinelyEmptyState() {
        val state = favoritesDocumentState(FavoritesUiState(), emptyList(), emptySet())
        assertEquals(0, state.savedFavoriteCount)
        assertTrue(state.documents.isEmpty())
    }

    @Test fun persistedIdsWithNoResolvedDocumentsProduceUnavailableStateInput() {
        val state = favoritesDocumentState(FavoritesUiState(), emptyList(), setOf("saved"))
        assertEquals(1, state.savedFavoriteCount)
        assertTrue(state.documents.isEmpty())
    }

    @Test fun availableAndUnresolvedFavoritesKeepPersistedCountAndShowAvailableRows() {
        val state = favoritesDocumentState(FavoritesUiState(), listOf(document("visible")), setOf("visible", "unresolved"))
        assertEquals(2, state.savedFavoriteCount)
        assertEquals(listOf("visible"), state.documents.map { it.id })
    }

    @Test fun removalEmissionNaturallyUpdatesSavedCount() {
        val initial = favoritesDocumentState(FavoritesUiState(), listOf(document("one")), setOf("one"))
        val removed = favoritesDocumentState(initial, emptyList(), emptySet())
        assertEquals(0, removed.savedFavoriteCount)
        assertTrue(removed.documents.isEmpty())
    }

    @Test fun zeroByteDocumentIdentityDoesNotChangeCounting() {
        val state = favoritesDocumentState(FavoritesUiState(), listOf(document("empty", DocumentCategory.Text)), setOf("empty"))
        assertEquals(1, state.savedFavoriteCount)
        assertEquals("empty", state.documents.single().id)
    }
}
