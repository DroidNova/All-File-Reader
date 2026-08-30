package com.droidnova.allfilereader.ui.screens.powerpoint

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
