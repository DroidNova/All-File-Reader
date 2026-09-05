package com.droidnova.allfilereader.data.powerpoint

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxNativeLoadingContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
    private val preflight = File(root, "app/src/main/java/com/droidnova/allfilereader/data/powerpoint/PptxPreflight.kt").readText()
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

    @Test fun nativePipelineStreamsAndNeverMaterializesTheDocument() {
        assertTrue(preflight.contains("openInputStream"))
        assertTrue(preflight.contains("outputStream().buffered()"))
        assertFalse(preflight.contains("readBytes()"))
        assertFalse(preflight.contains("Base64"))
    }
    @Test fun appDoesNotRequestNetworkOrLargeHeap() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("largeHeap"))
    }
}
