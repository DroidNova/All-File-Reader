package com.droidnova.allfilereader.ui.screens.powerpoint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPptFallbackContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
    private val opener = File(root, "app/src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/LegacyPptExternalOpener.kt").readText()
    private val viewModel = File(root, "app/src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/PowerPointReaderViewModel.kt").readText()

    @Test fun usesReadOnlyContentUriChooserContract() {
        assertTrue(opener.contains("Intent.ACTION_VIEW"))
        assertTrue(opener.contains("application/vnd.ms-powerpoint"))
        assertTrue(opener.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(opener.contains("uri.scheme != ContentResolverScheme"))
        assertFalse(opener.contains("FLAG_GRANT_WRITE_URI_PERMISSION"))
        assertFalse(opener.contains("Uri.fromFile"))
    }

    @Test fun handlesNoActivityAndLaunchExceptions() {
        assertEquals(LegacyPptOpenResult.Launched, LegacyPptExternalOpener.launch(true) {})
        assertEquals(LegacyPptOpenResult.NoCompatibleApp, LegacyPptExternalOpener.launch(false) { error("must not launch") })
        assertEquals(LegacyPptOpenResult.AccessDenied, LegacyPptExternalOpener.launch(true) { throw SecurityException("denied") })
        assertTrue(opener.contains("ActivityNotFoundException"))
        assertTrue(opener.contains("SecurityException"))
    }

    @Test fun pptBranchesBeforePptxPreflight() {
        val legacy = viewModel.indexOf("extension == \"ppt\"")
        val modern = viewModel.indexOf("preflight.copyAndValidate")
        assertTrue(legacy >= 0 && modern > legacy)
        assertTrue(viewModel.contains("extension != \"pptx\""))
    }
}
