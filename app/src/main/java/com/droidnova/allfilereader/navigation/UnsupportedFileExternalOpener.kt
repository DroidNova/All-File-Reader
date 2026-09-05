package com.droidnova.allfilereader.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import java.io.File
import java.io.FileNotFoundException

enum class ExternalOpenResult { Launched, NoCompatibleApp, AccessFailure, PreparationFailure, TooLarge }

/** Read-only, bounded bridge for opening a discovered unsupported file outside this app. */
object UnsupportedFileExternalOpener {
    const val MAX_SHARE_BYTES = 100L * 1024L * 1024L
    fun open(context: Context, document: com.droidnova.allfilereader.domain.model.DocumentFile): ExternalOpenResult {
        val shared = try { shareUri(context, document.uri) } catch (_: SecurityException) { return ExternalOpenResult.AccessFailure }
          catch (_: FileNotFoundException) { return ExternalOpenResult.AccessFailure }
          catch (_: TooLargeException) { return ExternalOpenResult.TooLarge }
          catch (_: Exception) { return ExternalOpenResult.PreparationFailure }
        val mime = safeMime(document.mimeType, document.extension)
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(shared, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData=ClipData.newRawUri("document",shared) }
        val handlers=context.packageManager.queryIntentActivities(view,0).map{ComponentName(it.activityInfo.packageName,it.activityInfo.name)}.filterNot{it.packageName==context.packageName}.distinct()
        if(handlers.isEmpty()) return ExternalOpenResult.NoCompatibleApp
        val chooser=Intent.createChooser(view,"Open with another app").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,handlers.filter{it.packageName==context.packageName}.toTypedArray()+ComponentName(context.packageName,"${context.packageName}.MainActivity")) }
        return try { context.startActivity(chooser); ExternalOpenResult.Launched } catch (_:ActivityNotFoundException){ExternalOpenResult.NoCompatibleApp} catch (_:SecurityException){ExternalOpenResult.AccessFailure}
    }
    private fun shareUri(context:Context,location:String):Uri { val uri=Uri.parse(location); if(uri.scheme=="content"&& !uri.authority.isNullOrBlank())return uri
        val source=when(uri.scheme){"file"->File(requireNotNull(uri.path));null->File(location);else->throw SecurityException()}
        if(!source.isFile||!source.canRead())throw FileNotFoundException(); if(source.length()>MAX_SHARE_BYTES)throw TooLargeException()
        val dir=File(context.cacheDir,"legacy_ppt_share").apply{mkdirs()}; dir.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>24*60*60*1000L}?.forEach{it.delete()}
        val target=File(dir,"external_${System.nanoTime()}.${source.extension.take(16)}"); source.inputStream().use{input->target.outputStream().use{out->val buffer=ByteArray(32*1024);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;if(total>MAX_SHARE_BYTES)throw TooLargeException();out.write(buffer,0,n)}}}
        return FileProvider.getUriForFile(context,"${context.packageName}.legacy-ppt-files",target)
    }
    private fun safeMime(mime:String?,extension:String?):String =
        DocumentClassifier.findByExtension(extension)?.mimeTypes?.firstOrNull()
            ?: DocumentClassifier.findByMimeType(mime)?.mimeTypes?.firstOrNull()
            ?: "application/octet-stream"
    private class TooLargeException:Exception()
}
