package com.droidnova.allfilereader.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val manifest get() = File(root, "src/main/AndroidManifest.xml").readText()

    @Test fun `manifest exposes only supported local document MIME contracts`() {
        val viewFilter = manifest.substringAfter("</intent-filter>").substringBefore("</intent-filter>")
        val advertised = Regex("android:mimeType=\"([^\"]+)\"").findAll(viewFilter).map { it.groupValues[1].lowercase() }.toSet()
        assertEquals(setOf(
            "application/pdf", "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroenabled.12", "application/vnd.ms-excel.sheet.binary.macroenabled.12",
            "application/vnd.oasis.opendocument.spreadsheet", "text/csv", "text/tab-separated-values",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ), advertised)
        assertTrue("android.intent.action.VIEW" in viewFilter)
        assertTrue("android.intent.category.DEFAULT" in viewFilter)
        assertFalse("*/*" in viewFilter)
        assertFalse("BROWSABLE" in viewFilter)
        assertFalse("android:scheme" in viewFilter)
        assertFalse("android.intent.action.SEND" in manifest)
        assertFalse("application/vnd.ms-powerpoint" in viewFilter)
        assertFalse("application/msword" in viewFilter)
    }

    @Test fun `activity consumes cold and warm intents without restoring the launch intent`() {
        val source = File(root, "src/main/java/com/droidnova/allfilereader/MainActivity.kt").readText()
        assertTrue("if (savedInstanceState == null) incomingDocuments.accept(intent)" in source)
        assertTrue("override fun onNewIntent(intent: Intent)" in source)
        assertTrue("incomingDocuments.accept(intent)" in source)
    }

    @Test fun `legacy chooser excludes this package and checks external handlers`() {
        val source = File(root, "src/main/java/com/droidnova/allfilereader/ui/screens/powerpoint/LegacyPptExternalOpener.kt").readText()
        assertTrue("filterNot { it.packageName == context.packageName }" in source)
        assertTrue("Intent.EXTRA_EXCLUDE_COMPONENTS" in source)
        assertTrue("externalHandlers.isNotEmpty()" in source)
    }
}
