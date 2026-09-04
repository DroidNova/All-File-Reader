package com.droidnova.allfilereader.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MandatoryStorageGateContractTest {
    private val root = File("src/main")

    @Test fun disclosureIsMandatoryAndHasNoDismissAction() {
        val sheet = root.resolve("java/com/droidnova/allfilereader/ui/components/MandatoryStoragePermissionSheet.kt").readText()
        assertTrue(sheet.contains("onDismissRequest = {}"))
        assertTrue(sheet.contains("it != SheetValue.Hidden"))
        assertTrue(sheet.contains("dragHandle = null"))
        assertTrue(sheet.contains("moveTaskToBack(true)"))
        assertFalse(sheet.contains("R.string.not_now"))
        assertFalse(sheet.contains("Cancel"))
    }

    @Test fun versionSpecificFlowsAreRetained() {
        val access = root.resolve("java/com/droidnova/allfilereader/ui/components/StorageAccess.kt").readText()
        assertTrue(access.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"))
        assertTrue(access.contains("Uri.parse(\"package:\$packageName\")"))
        assertTrue(access.contains("ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION"))
        assertTrue(access.contains("Manifest.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test fun manifestScopesLegacyReadPermissionAndHasNoInternetPermission() {
        val manifest = root.resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:maxSdkVersion=\"29\""))
        assertTrue(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test fun globalGateOnlyComposesNavigationAfterGrant() {
        val app = root.resolve("java/com/droidnova/allfilereader/navigation/AllFileReaderApp.kt").readText()
        assertTrue(app.contains("accessState == StorageAccessState.Granted) NavHost"))
        assertTrue(app.contains("isRootDestination && accessState == StorageAccessState.Granted"))
    }
}
