package com.droidnova.allfilereader.data.word

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class DocxDeviceProfile(val memoryClassMb: Int, val largeMemoryClassMb: Int, val isLowRamDevice: Boolean)

data class DocxRenderBudget(
    val maxCompressedBytes: Long,
    val maxTotalUncompressedBytes: Long,
    val maxEntryBytes: Long,
    val maxXmlPartBytes: Long,
    val maxMediaEntryBytes: Long,
    val maxEntryCount: Int,
    val maxCompressionRatio: Long,
    val maxParagraphCount: Int,
    val maxTableCellCount: Int,
    val maxDrawingReferenceCount: Int,
    val maxSearchHighlightCount: Int,
    val profileName: String
) { fun acceptsCompressedSize(bytes: Long) = bytes in 1..maxCompressedBytes }

/** Budgets account for the compressed file, WebView buffer, JSZip expansion, images and rendered DOM. */
object DocxBudgetPolicy {
    const val ABSOLUTE_MAX_COMPRESSED_BYTES = 50L * 1024 * 1024
    const val DEFAULT_MEMORY_CLASS_MB = 256
    fun forProfile(profile: DocxDeviceProfile): DocxRenderBudget {
        val memory = profile.memoryClassMb.takeIf { it > 0 } ?: DEFAULT_MEMORY_CLASS_MB
        val large = profile.largeMemoryClassMb.takeIf { it >= memory } ?: memory
        return when {
            profile.isLowRamDevice || minOf(memory, large) < 192 -> DocxRenderBudget(
                24L*1024*1024,128L*1024*1024,48L*1024*1024,12L*1024*1024,12L*1024*1024,
                2_048,100,60_000,20_000,5_000,250,"low")
            minOf(memory, large) < 384 -> DocxRenderBudget(
                40L*1024*1024,192L*1024*1024,64L*1024*1024,20L*1024*1024,20L*1024*1024,
                3_072,100,100_000,35_000,10_000,500,"normal")
            else -> DocxRenderBudget(
                ABSOLUTE_MAX_COMPRESSED_BYTES,256L*1024*1024,64L*1024*1024,32L*1024*1024,25L*1024*1024,
                4_096,100,150_000,50_000,15_000,750,"high")
        }
    }
    fun from(context: Context): DocxRenderBudget {
        val manager=context.getSystemService(ActivityManager::class.java)
        return forProfile(DocxDeviceProfile(manager?.memoryClass?:DEFAULT_MEMORY_CLASS_MB,manager?.largeMemoryClass?:DEFAULT_MEMORY_CLASS_MB,manager?.isLowRamDevice?:false))
    }
}

internal object DocxSessionFiles {
    const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
    fun deleteOwned(file:File?,directory:File):Boolean = runCatching {
        file != null && file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.delete()
    }.getOrDefault(false)
    fun cleanStale(directory:File,nowMillis:Long,active:File?=null):Int = directory.listFiles().orEmpty().count { file ->
        file != active && file.isFile && nowMillis-file.lastModified()>STALE_AFTER_MS && deleteOwned(file,directory)
    }
}
