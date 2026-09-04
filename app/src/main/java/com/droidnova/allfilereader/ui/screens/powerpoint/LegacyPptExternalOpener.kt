package com.droidnova.allfilereader.ui.screens.powerpoint

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log

enum class LegacyPptOpenResult { Launched, NoCompatibleApp, AccessDenied }

object LegacyPptExternalOpener {
    const val MIME_TYPE = "application/vnd.ms-powerpoint"

    fun open(context: Context, uri: Uri): LegacyPptOpenResult {
        if (uri.scheme != ContentResolverScheme) {
            trace(context, "stage=uri_validation scheme=${uri.scheme ?: "none"} authority=${authority(uri)} sourceType=share extension=ppt code=UNSUPPORTED_SOURCE")
            return LegacyPptOpenResult.AccessDenied
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("legacy-presentation", uri)
        }
        val externalHandlers = context.packageManager.queryIntentActivities(viewIntent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filterNot { it.packageName == context.packageName }
            .distinct()
        val canResolve = externalHandlers.isNotEmpty()
        trace(context, "stage=chooser_resolution scheme=content authority=${authority(uri)} sourceType=share extension=ppt code=${if (canResolve) "HANDLER_FOUND" else "NO_COMPATIBLE_APP"}")
        return launch(canResolve, { code, exception -> trace(context, "stage=chooser_launch scheme=content authority=${authority(uri)} sourceType=share extension=ppt exception=${exception ?: "none"} code=$code") }) {
            val chooser = Intent.createChooser(viewIntent, "Open legacy PowerPoint presentation").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context.packageName, "${context.packageName}.MainActivity")))
            }
            context.startActivity(chooser)
        }
    }

    internal fun launch(canResolve: Boolean, logger: (String, String?) -> Unit = { _, _ -> }, launcher: () -> Unit): LegacyPptOpenResult {
        if (!canResolve) return LegacyPptOpenResult.NoCompatibleApp
        return try {
            launcher()
            logger("LAUNCHED", null)
            LegacyPptOpenResult.Launched
        } catch (error: ActivityNotFoundException) {
            logger("NO_COMPATIBLE_APP", error.javaClass.simpleName)
            LegacyPptOpenResult.NoCompatibleApp
        } catch (error: SecurityException) {
            logger("CHOOSER_SECURITY_FAILURE", error.javaClass.simpleName)
            LegacyPptOpenResult.AccessDenied
        }
    }

    private fun authority(uri: Uri) = uri.authority?.take(80)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "none"
    private fun trace(context: Context, message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("LegacyPptOpen", message)
    }

    private const val ContentResolverScheme = "content"
}
