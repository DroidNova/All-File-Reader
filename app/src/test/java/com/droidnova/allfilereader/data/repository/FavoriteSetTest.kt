package com.droidnova.allfilereader.data.repository

import com.droidnova.allfilereader.domain.model.DocumentIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSetTest {
    @Test fun addAndDuplicateAddProduceOneEntry() {
        val once = applyFavoriteChange(emptySet(), "document", FavoriteChange.Add)
        val twice = applyFavoriteChange(once, "document", FavoriteChange.Add)
        assertEquals(setOf("document"), twice)
    }

    @Test fun removeAndRemovingMissingEntryAreSafe() {
        assertTrue(applyFavoriteChange(setOf("document"), "document", FavoriteChange.Remove).isEmpty())
        assertEquals(setOf("other"), applyFavoriteChange(setOf("other"), "document", FavoriteChange.Remove))
    }

    @Test fun toggleHasConsistentParityUnderRapidUpdates() {
        val result = (1..1_000).fold(emptySet<String>()) { ids, _ ->
            applyFavoriteChange(ids, "document", FavoriteChange.Toggle)
        }
        assertTrue(result.isEmpty())
    }

    @Test fun stableCanonicalLocatorProducesStableIdentityAcrossRescans() {
        val locator = "/storage/emulated/0/Documents/report.pdf"
        assertEquals(DocumentIds.fromStorageLocation(locator), DocumentIds.fromStorageLocation(locator))
    }

    @Test fun bulkRemovalSubtractsOnlyRequestedIdsAndIsIdempotent() {
        val original = setOf("one", "two", "three")
        val once = removeFavoriteIds(original, setOf("one", "two"))
        assertEquals(setOf("three"), once)
        assertEquals(once, removeFavoriteIds(once, setOf("one", "two")))
    }

    @Test fun emptyBulkRemovalIsANoOp() {
        val original = setOf("one")
        assertTrue(original === removeFavoriteIds(original, emptySet()))
    }
}
