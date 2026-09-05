package com.droidnova.allfilereader.data.excel

import android.app.ActivityManager
import android.content.Context

data class SpreadsheetDeviceProfile(
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val isLowRamDevice: Boolean
)

data class SpreadsheetRenderBudget(
    val maxWorkbookBytes: Long,
    val normalDomCellLimit: Int,
    val pageRowCount: Int,
    val pageColumnCount: Int,
    val maxUsedRows: Int,
    val maxUsedColumns: Int,
    val maxMergedRangeSpan: Long,
    val profileName: String
) {
    fun acceptsWorkbook(actualBytes: Long): Boolean = actualBytes in 1..maxWorkbookBytes
}

/**
 * WebView and SheetJS can transiently retain several representations of a workbook. These limits
 * therefore use only a small fraction of the Java heap and retain absolute caps. Large worksheets
 * are paged rather than rejected, so the DOM limits do not limit workbook dimensions.
 */
object SpreadsheetBudgetPolicy {
    const val ABSOLUTE_MAX_WORKBOOK_BYTES = 64L * 1024 * 1024
    const val DEFAULT_MEMORY_CLASS_MB = 256

    fun forProfile(profile: SpreadsheetDeviceProfile): SpreadsheetRenderBudget {
        val memory = profile.memoryClassMb.takeIf { it > 0 } ?: DEFAULT_MEMORY_CLASS_MB
        val large = profile.largeMemoryClassMb.takeIf { it >= memory } ?: memory
        val effective = minOf(memory, large)
        return when {
            profile.isLowRamDevice || effective < 192 -> SpreadsheetRenderBudget(
                16L * 1024 * 1024, 20_000, 120, 24, 1_048_576, 16_384, 20_000, "low")
            effective < 384 -> SpreadsheetRenderBudget(
                32L * 1024 * 1024, 40_000, 200, 32, 1_048_576, 16_384, 50_000, "normal")
            else -> SpreadsheetRenderBudget(
                ABSOLUTE_MAX_WORKBOOK_BYTES, 75_000, 250, 40, 1_048_576, 16_384, 100_000, "high")
        }
    }

    fun from(context: Context): SpreadsheetRenderBudget {
        val manager = context.getSystemService(ActivityManager::class.java)
        return forProfile(SpreadsheetDeviceProfile(
            memoryClassMb = manager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB,
            largeMemoryClassMb = manager?.largeMemoryClass ?: DEFAULT_MEMORY_CLASS_MB,
            isLowRamDevice = manager?.isLowRamDevice ?: false
        ))
    }
}
