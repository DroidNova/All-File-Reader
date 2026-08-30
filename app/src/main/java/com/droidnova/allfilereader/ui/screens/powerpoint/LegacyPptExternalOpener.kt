package com.droidnova.allfilereader.ui.screens.powerpoint

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

enum class LegacyPptOpenResult { Launched, NoCompatibleApp, AccessDenied }

object LegacyPptExternalOpener {
    const val MIME_TYPE = "application/vnd.ms-powerpoint"

    fun open(context: Context, uri: Uri): LegacyPptOpenResult {
        if (uri.scheme != ContentResolverScheme) return LegacyPptOpenResult.AccessDenied
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("legacy-presentation", uri)
        }
        val canResolve = viewIntent.resolveActivity(context.packageManager) != null
        return launch(canResolve) {
            val chooser = Intent.createChooser(viewIntent, "Open legacy PowerPoint presentation").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        }
    }

    internal fun launch(canResolve: Boolean, launcher: () -> Unit): LegacyPptOpenResult {
        if (!canResolve) return LegacyPptOpenResult.NoCompatibleApp
        return try {
            launcher()
            LegacyPptOpenResult.Launched
        } catch (_: ActivityNotFoundException) {
            LegacyPptOpenResult.NoCompatibleApp
        } catch (_: SecurityException) {
            LegacyPptOpenResult.AccessDenied
        }
    }

    private const val ContentResolverScheme = "content"
}
