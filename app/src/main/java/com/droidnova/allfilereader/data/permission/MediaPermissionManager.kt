package com.droidnova.allfilereader.data.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

enum class MediaPermissionType {
    Documents,
    Images
}

data class RequiredMediaPermission(
    val permission: String,
    val type: MediaPermissionType
)

class MediaPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun requiredPermission(): RequiredMediaPermission = if (Build.VERSION.SDK_INT >= 33) {
        RequiredMediaPermission(Manifest.permission.READ_MEDIA_IMAGES, MediaPermissionType.Images)
    } else {
        RequiredMediaPermission(Manifest.permission.READ_EXTERNAL_STORAGE, MediaPermissionType.Documents)
    }

    fun isGranted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredPermission().permission
    ) == PackageManager.PERMISSION_GRANTED

    fun canQueryDocumentsWithoutPermission(): Boolean = Build.VERSION.SDK_INT >= 33
}
