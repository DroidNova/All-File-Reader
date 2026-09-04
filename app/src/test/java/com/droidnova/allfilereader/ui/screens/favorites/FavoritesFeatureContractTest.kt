package com.droidnova.allfilereader.ui.screens.favorites

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesFeatureContractTest {
    private val main = File("src/main")

    @Test fun persistenceStoresOnlyIdsAndSurvivesRepositoryRecreation() {
        val source = main.resolve("java/com/droidnova/allfilereader/data/repository/DataStoreFavoritesRepository.kt").readText()
        assertTrue(source.contains("preferencesDataStore(name = \"document_favorites\")"))
        assertTrue(source.contains("stringSetPreferencesKey(\"favorite_document_ids\")"))
        assertFalse(source.contains("displayName"))
        assertFalse(source.contains("uri"))
    }

    @Test fun sharedRowHasAccessibleIndependentToggle() {
        val row = main.resolve("java/com/droidnova/allfilereader/ui/components/DocumentFileRow.kt").readText()
        assertTrue(row.contains("IconToggleButton"))
        assertTrue(row.contains("R.string.add_to_favorites"))
        assertTrue(row.contains("R.string.remove_from_favorites"))
        assertTrue(row.contains("onCheckedChange = { toggle() }"))
    }

    @Test fun favoritesUsesStableLazyKeysAndCentralDocumentCallback() {
        val screen = main.resolve("java/com/droidnova/allfilereader/ui/screens/favorites/FavoritesScreen.kt").readText()
        assertTrue(screen.contains("key = DocumentFile::id"))
        assertTrue(screen.contains("onDocumentClick(document)"))
        assertTrue(screen.contains("PullToRefreshBox"))
    }

    @Test fun permissionRevocationClearsMetadataNotFavoriteIds() {
        val gate = main.resolve("java/com/droidnova/allfilereader/navigation/StorageAccessViewModel.kt").readText()
        assertTrue(gate.contains("repository.clearSnapshots()"))
        assertFalse(gate.contains("favoritesRepository"))
    }
}
