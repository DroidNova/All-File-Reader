package com.droidnova.allfilereader.ui.screens.powerpoint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPptFallbackContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
    private fun source(path: String) = File(root, path).readText()
    private val opener = source("app/src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/LegacyPptExternalOpener.kt")
    private val resolver = source("app/src/main/java/com/droidnova/allfilereader/data/powerpoint/LegacyPptSourceResolver.kt")
    private val viewModel = source("app/src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/PowerPointReaderViewModel.kt")

    @Test fun usesReadOnlyContentUriChooserContract() {
        assertTrue(opener.contains("Intent.ACTION_VIEW"))
        assertTrue(opener.contains("application/vnd.ms-powerpoint"))
        assertTrue(opener.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(opener.contains("ClipData.newRawUri"))
        assertFalse(opener.contains("FLAG_GRANT_WRITE_URI_PERMISSION"))
        assertFalse(opener.contains("Uri.fromFile"))
    }

    @Test fun handlesNoActivityAndLaunchExceptions() {
        assertEquals(LegacyPptOpenResult.Launched, LegacyPptExternalOpener.launch(true) {})
        assertEquals(LegacyPptOpenResult.NoCompatibleApp, LegacyPptExternalOpener.launch(false) { error("must not launch") })
        assertEquals(LegacyPptOpenResult.AccessDenied, LegacyPptExternalOpener.launch(true) { throw SecurityException("denied") })
    }

    @Test fun scannedFilesAreResolvedAndSharedOnlyThroughDedicatedCache() {
        assertTrue(resolver.contains("\"file\" -> resolveLocal"))
        assertTrue(resolver.contains("null, \"\" -> resolveLocal"))
        assertTrue(resolver.contains("canonicalFile"))
        assertTrue(resolver.contains("legacy_ppt_share"))
        assertTrue(resolver.contains("FileProvider.getUriForFile"))
        assertTrue(resolver.contains("uri.scheme != \"content\""))
        assertTrue(resolver.contains("target.delete()"))
        assertFalse(resolver.contains("Uri.fromFile"))
    }

    @Test fun pptBranchesBeforePptxPreflight() {
        val legacy = viewModel.indexOf("ext==\"ppt\"")
        val modern = viewModel.indexOf("preflight.copyAndValidate")
        assertTrue(legacy >= 0 && modern > legacy)
        assertTrue(viewModel.contains("ext!=\"pptx\""))
    }
}
