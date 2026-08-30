package com.droidnova.allfilereader.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDirectoryVisibilityTest {
    @Test fun `directory card is temporarily excluded from Home`() {
        assertFalse(SHOW_DIRECTORIES_ON_HOME)
        assertFalse(isHomeCategoryVisible("directories"))
        assertTrue(isHomeCategoryVisible(null)) // All Files and document cards reflow normally.
        assertTrue(isHomeCategoryVisible("favorites"))
    }
}
