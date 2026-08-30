package com.droidnova.allfilereader.ui.screens.excel

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetAssetContractTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "app").isDirectory) it else it.parentFile }
    @Test fun bundledSheetJsIsTheVerifiedOfflineAsset() {
        val bundled = File(root, "app/src/main/assets/excel_viewer/xlsx.full.min.js")
        val verified = File(root, "verified-excel-assets/xlsx.full.min.js")
        assertEquals("cc015130aa8521e7f088f88898eba949ccdcbfb38df0bd129b44b7273c3a6f41", sha256(bundled))
        assertTrue(bundled.readBytes().contentEquals(verified.readBytes()))
    }
    @Test fun viewerMaintainsLocalOnlyDefensiveContract() {
        val html = File(root, "app/src/main/assets/excel_viewer/viewer.html").readText()
        val js = File(root, "app/src/main/assets/excel_viewer/viewer.js").readText()
        assertTrue(html.contains("Content-Security-Policy")); assertTrue(html.contains("default-src 'none'"))
        assertTrue(js.contains("bookVBA:false")); assertTrue(js.contains("Object.prototype.hasOwnProperty.call"))
        assertTrue(js.contains("RESOURCE_LIMIT_EXCEEDED")); assertTrue(js.contains("resultCategory"))
        assertFalse(js.contains("http://")); assertFalse(js.contains("https://"))
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
