package com.droidnova.allfilereader.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberStorageAccessRequest(onReturn: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnReturn = rememberUpdatedState(onReturn)
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { currentOnReturn.value() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { currentOnReturn.value() }
    return remember(context) {{
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = storageSettingsIntent(context.packageName)
            try { settingsLauncher.launch(appIntent) }
            catch (_: ActivityNotFoundException) { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }}
}

internal fun storageSettingsIntent(packageName: String) = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    Uri.parse("package:$packageName")
)
