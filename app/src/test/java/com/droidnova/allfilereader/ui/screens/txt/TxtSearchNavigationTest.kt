package com.droidnova.allfilereader.ui.screens.txt

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtSearchNavigationTest {
    @Test fun nextWrapsFromLastToFirst() = assertEquals(0, wrappedMatchIndex(2, 1, 3))
    @Test fun previousWrapsFromFirstToLast() = assertEquals(2, wrappedMatchIndex(0, -1, 3))
    @Test fun noMatchesHasNoSelection() = assertEquals(-1, wrappedMatchIndex(-1, 1, 0))
}
