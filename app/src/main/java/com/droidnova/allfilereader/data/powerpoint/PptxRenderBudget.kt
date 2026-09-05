package com.droidnova.allfilereader.data.powerpoint

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.security.SecureRandom

data class PptxDeviceProfile(val memoryClassMb: Int, val largeMemoryClassMb: Int, val isLowRamDevice: Boolean)

enum class PptxBudgetCategory { LowRam, Standard, HighMemory }

data class PptxRenderBudget(
    val category: PptxBudgetCategory,
    val maxCompressedBytes: Long,
    val maxTotalUncompressedBytes: Long,
    val maxEntryBytes: Long,
    val maxEntryCount: Int,
    val maxCompressionRatio: Double,
    val rendererStallMillis: Long,
    val maxStoredSearchMatches: Int
) {
    fun acceptsCompressedSize(bytes: Long): Boolean = bytes >= 0L && bytes <= maxCompressedBytes
}

object PptxRenderBudgetPolicy {
    const val HARD_MAX_STORED_SEARCH_MATCHES = 1_000
    private const val MIB = 1024L * 1024L
    fun profile(context: Context): PptxDeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return PptxDeviceProfile(manager?.memoryClass ?: 0, manager?.largeMemoryClass ?: 0, manager?.isLowRamDevice ?: true)
    }
    fun forProfile(profile: PptxDeviceProfile): PptxRenderBudget {
        val memory = profile.memoryClassMb.takeIf { it >= 64 } ?: 128
        return when {
            profile.isLowRamDevice || memory < 192 -> PptxRenderBudget(PptxBudgetCategory.LowRam,64*MIB,192*MIB,32*MIB,2_000,150.0,120_000,250)
            memory < 384 -> PptxRenderBudget(PptxBudgetCategory.Standard,128*MIB,384*MIB,64*MIB,4_000,200.0,120_000,500)
            else -> PptxRenderBudget(PptxBudgetCategory.HighMemory,256*MIB,512*MIB,128*MIB,6_000,250.0,120_000,HARD_MAX_STORED_SEARCH_MATCHES)
        }
    }
}

class PptxSessionStore(private val cacheDir: File) {
    private val directory = File(cacheDir, DIRECTORY_NAME)
    fun newId(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun create(id: String): File {
        require(ID.matches(id))
        ensureDirectory()
        ACTIVE += id
        return File(directory, "$id.pptx").also { require(it.parentFile?.canonicalFile == directory.canonicalFile) }
    }
    fun release(id: String, file: File?): Boolean {
        if (file != null && !isOwned(file)) return false
        ACTIVE -= id
        if (file == null) return false
        return !file.exists() || file.delete()
    }
    fun removeStaleSessions(nowMillis: Long = System.currentTimeMillis()): Int {
        ensureDirectory()
        var removed = 0
        directory.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".pptx")
            if (file.isFile && ID.matches(id) && id !in ACTIVE && nowMillis - file.lastModified() >= STALE_AGE_MILLIS && isOwned(file) && file.delete()) removed++
        }
        return removed
    }
    internal fun isOwned(file: File): Boolean = runCatching { file.parentFile?.canonicalFile == directory.canonicalFile }.getOrDefault(false)
    private fun ensureDirectory() { if (!directory.exists()) directory.mkdirs(); check(directory.isDirectory) }
    companion object {
        const val DIRECTORY_NAME = "pptx_sessions"
        const val STALE_AGE_MILLIS = 24L * 60 * 60 * 1000
        private val ID = Regex("^[0-9a-f]{48}$")
        private val ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}
