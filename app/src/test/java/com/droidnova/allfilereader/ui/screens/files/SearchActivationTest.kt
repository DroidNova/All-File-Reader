package com.droidnova.allfilereader.ui.screens.files

import com.droidnova.allfilereader.navigation.SearchActivationRequest
import com.droidnova.allfilereader.navigation.SearchActivationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchActivationTest {
    private val homeRequest = SearchActivationRequest(
        id = 42,
        source = SearchActivationSource.Home,
        resetCategoryToAll = true,
        clearQuery = true
    )

    @Test fun `home activation resets Word clears query and emits one-shot UI work`() {
        val change = applySearchActivation(
            current = FilesUiState(hasAccess = true, query = "report", searching = true),
            selectedFilter = RecentDocumentFilter.Word,
            lastHandledRequestId = null,
            request = homeRequest
        )!!

        assertEquals(RecentDocumentFilter.All, change.selectedFilter)
        assertTrue(change.state.searchActive)
        assertEquals("", change.state.query)
        assertFalse(change.state.searching)
        assertEquals(42L, change.state.categoryResetRequestId)
        assertEquals(42L, change.state.focusRequestId)
        assertEquals(RecentDocumentFilter.All.ordinal, change.selectedFilter.ordinal)
    }

    @Test fun `same request id is idempotent and cannot emit focus again`() {
        val repeated = applySearchActivation(
            current = FilesUiState(hasAccess = true),
            selectedFilter = RecentDocumentFilter.All,
            lastHandledRequestId = homeRequest.id,
            request = homeRequest
        )

        assertNull(repeated)
    }

    @Test fun `ordinary Recent activation preserves category and query`() {
        val change = applySearchActivation(
            current = FilesUiState(hasAccess = true, query = "budget"),
            selectedFilter = RecentDocumentFilter.Word,
            lastHandledRequestId = null,
            request = SearchActivationRequest(
                id = 43,
                source = SearchActivationSource.Recent,
                resetCategoryToAll = false,
                clearQuery = false
            )
        )!!

        assertEquals(RecentDocumentFilter.Word, change.selectedFilter)
        assertEquals("budget", change.state.query)
        assertNull(change.state.categoryResetRequestId)
        assertEquals(43L, change.state.focusRequestId)
    }
}
