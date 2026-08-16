package com.droidnova.allfilereader.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Central version-aware shared-storage access check. */
class MediaPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isGranted(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun runtimePermission(): String? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        Manifest.permission.READ_EXTERNAL_STORAGE
    } else null
}
