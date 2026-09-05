package com.droidnova.allfilereader.ui.screens.powerpoint

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PptxBridgeContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
    private val js get() = File(root, "app/src/main/assets/pptx_viewer/viewer.js").readText()
    private val kotlin get() = File(root, "app/src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/PowerPointReaderScreen.kt").readText()

    @Test fun tracksMountedSlidesUsingRendererEventsAndExplicitScrollRoot() {
        assertTrue(js.contains("scrollContainer: scrollHost")); assertTrue(js.contains("sliderendered"));
        assertTrue(js.contains("slideunmounted")); assertTrue(js.contains("IntersectionObserver"));
        assertTrue(js.contains("requestAnimationFrame")); assertTrue(js.contains("setActiveSlide"))
    }
    @Test fun searchCommandsAreImmediateAndResultsComeFromPolling() {
        assertTrue(js.contains("function search(queryValue)")); assertTrue(js.contains("void navigateToMatch"));
        assertTrue(js.contains("searchGeneration")); assertTrue(js.contains("SEARCH_NOT_READY"));
        assertFalse(js.contains("search: async")); assertTrue(kotlin.contains("JSONObject.quote(query)"));
        assertTrue(kotlin.contains("pptxControl.search")); assertTrue(kotlin.contains("pptxControl.status()"))
    }
    @Test fun cleanupDisconnectsTrackingAndDestroysOnce() {
        assertTrue(js.contains("observer?.disconnect()")); assertTrue(js.contains("cancelAnimationFrame"));
        assertTrue(js.contains("destroyCalled")); assertTrue(js.contains("viewer?.destroy()"))
    }
    @Test fun nativeStatusValidationAcceptsBoundedCountsAndRejectsInvalidValues() {
        assertEquals(PptxViewerStatus(1, 500, true, 1, 2, 7), validatedPptxViewerStatus(0, 500, true, 1, 2, 7, 500))
        assertNull(validatedPptxViewerStatus(-1, 501, true, 1, 2, 7, 500))
        assertNull(validatedPptxViewerStatus(-2, 1, false, 1, 2, 7, 500))
        assertNull(validatedPptxViewerStatus(0, 0, false, 1, 2, 7, 500))
        assertNull(validatedPptxViewerStatus(0, 1, true, 1, 2, 7, 500))
        assertNull(validatedPptxViewerStatus(-1, 0, false, 1, 2, -1, 500))
    }
    @Test fun wrapperBoundsApplicationOwnedResultsAndPublishesLimitedMetadataOnly() {
        assertTrue(js.contains("boundedSearchResults(rawResults, searchLimit)"))
        assertTrue(js.contains("retainedMatches = bounded.matches"))
        assertFalse(js.contains("matches = viewer.searchText"))
        assertTrue(js.contains("hasMoreMatches"))
        assertTrue(kotlin.contains("pptx_search_count_limited"))
    }
    @Test fun rendererReportsBoundedProgressStagesAndNativeUsesInactivityWatchdog() {
        listOf("viewer_loaded", "document_fetch_started", "document_fetch_complete", "render_started", "first_slide_rendered", "render_complete", "render_failed").forEach { assertTrue(js.contains(it)) }
        assertTrue(kotlin.contains("lastProgressAt"))
        assertTrue(kotlin.contains("RendererStalled"))
        assertTrue(kotlin.contains("ready.stallMillis"))
    }
}
