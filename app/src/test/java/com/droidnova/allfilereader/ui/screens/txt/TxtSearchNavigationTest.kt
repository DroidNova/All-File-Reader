package com.droidnova.allfilereader.ui.screens.txt

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtSearchNavigationTest {
    @Test fun nextWrapsFromLastToFirst() = assertEquals(0, wrappedMatchIndex(2, 1, 3))
    @Test fun previousWrapsFromFirstToLast() = assertEquals(2, wrappedMatchIndex(0, -1, 3))
    @Test fun noMatchesHasNoSelection() = assertEquals(-1, wrappedMatchIndex(-1, 1, 0))

    @Test fun longVisualSegmentsAreBoundedAndReconstructExactly() {
        val original = "a".repeat(9) + "😀" + "b".repeat(12)
        val segments = visualSegments(original, 5)
        assertEquals(original, segments.joinToString("") { it.text })
        assert(segments.all { it.text.length <= 5 })
        assert(segments.zipWithNext().all { (first, second) ->
            second.start == first.start + first.text.length
        })
    }
}
