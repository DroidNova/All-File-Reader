package com.droidnova.allfilereader.ui.screens.powerpoint

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class PptxSearchBoundsScriptTest {
    @Test fun appOwnedSearchResultsAreBoundedByTheExactBundledHelper() {
        val available = runCatching { ProcessBuilder("node", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("Node is required to execute the bundled PPTX helper", available)
        val process = ProcessBuilder("node", "app/src/test/scripts/verify-pptx-search-bounds.mjs").inheritIO().start()
        assertEquals(0, process.waitFor())
    }
}
