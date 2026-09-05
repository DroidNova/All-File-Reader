package com.droidnova.allfilereader.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteReconciliationTest {
    private val complete = ScanCoverage(setOf("internal"), emptySet(), true, true)
    private fun evidence(vararg entries: Pair<String, String>) = DocumentSnapshotEvidence(
        entries.mapTo(linkedSetOf()) { it.first }, entries.toMap()
    )

    @Test fun previouslyKnownFavoriteMissingFromCompletedRootIsDeleted() {
        assertEquals(setOf("gone"), confirmedDeletedFavoriteIds(setOf("gone"), evidence("gone" to "internal"), evidence(), complete))
    }

    @Test fun presentFavoriteAndNonFavoriteDeletionArePreserved() {
        val previous = evidence("kept" to "internal", "not-favorite" to "internal")
        assertTrue(confirmedDeletedFavoriteIds(setOf("kept"), previous, evidence("kept" to "internal"), complete).isEmpty())
    }

    @Test fun multipleDeletedAreReturnedWithoutUnrelatedFavorite() {
        val previous = evidence("one" to "internal", "two" to "internal")
        assertEquals(setOf("one", "two"), confirmedDeletedFavoriteIds(setOf("one", "two", "legacy"), previous, evidence(), complete))
    }

    @Test fun firstSnapshotCannotDeleteLegacyFavorite() {
        assertTrue(confirmedDeletedFavoriteIds(setOf("legacy"), null, evidence(), complete).isEmpty())
    }

    @Test fun renamedFileDeletesOldIdentityButDoesNotFavoriteNewIdentity() {
        assertEquals(setOf("old"), confirmedDeletedFavoriteIds(setOf("old"), evidence("old" to "internal"), evidence("new" to "internal"), complete))
    }

    @Test fun permissionLossAndIncompleteScanNeverDelete() {
        val previous = evidence("gone" to "internal")
        assertTrue(confirmedDeletedFavoriteIds(setOf("gone"), previous, evidence(), complete.copy(permissionGrantedForEntireScan=false)).isEmpty())
        assertTrue(confirmedDeletedFavoriteIds(setOf("gone"), previous, evidence(), complete.copy(completed=false)).isEmpty())
    }

    @Test fun unavailableOrUncompletedRootNeverDeletes() {
        val previous = evidence("sd" to "removable")
        assertTrue(confirmedDeletedFavoriteIds(setOf("sd"), previous, evidence(), ScanCoverage(setOf("internal"), emptySet(), true, true)).isEmpty())
        assertTrue(confirmedDeletedFavoriteIds(setOf("sd"), previous, evidence(), ScanCoverage(setOf("removable"), setOf("removable"), true, true)).isEmpty())
    }

    @Test fun oneCompletedRootCannotDeleteFavoriteFromAnotherRoot() {
        val previous = evidence("internal-gone" to "internal", "sd" to "removable")
        val coverage = ScanCoverage(setOf("internal"), emptySet(), true, true)
        assertEquals(setOf("internal-gone"), confirmedDeletedFavoriteIds(setOf("internal-gone", "sd"), previous, evidence(), coverage))
    }

    @Test fun currentNewerSnapshotFindingFileProducesNoDeletion() {
        val previous = evidence("file" to "internal")
        assertTrue(confirmedDeletedFavoriteIds(setOf("file"), previous, evidence("file" to "internal"), complete).isEmpty())
    }
}
