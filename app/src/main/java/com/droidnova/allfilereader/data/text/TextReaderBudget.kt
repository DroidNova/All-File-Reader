package com.droidnova.allfilereader.data.text

data class TextReaderDeviceProfile(
    val memoryClassMb: Int,
    val isLowRamDevice: Boolean
)

data class TextReaderBudget(
    val smallFileByteLimit: Long,
    val decodedChunkCharLimit: Int,
    val maxCachedChunkCount: Int,
    val maxCachedCharacters: Int,
    val maxVisualSegmentCharacters: Int,
    val maxStoredSearchMatches: Int,
    val binarySampleBytes: Int
)

/** Memory limits apply only to app-owned decoded text; the source stays on disk. */
object TextReaderBudgetPolicy {
    private const val MIB = 1024L * 1024L

    fun forDevice(profile: TextReaderDeviceProfile): TextReaderBudget {
        val lowMemory = profile.isLowRamDevice || profile.memoryClassMb in 1 until 192
        return if (lowMemory) {
            TextReaderBudget(
                smallFileByteLimit = 512L * 1024L,
                decodedChunkCharLimit = 16 * 1024,
                maxCachedChunkCount = 3,
                maxCachedCharacters = 48 * 1024,
                maxVisualSegmentCharacters = 2 * 1024,
                maxStoredSearchMatches = 500,
                binarySampleBytes = 16 * 1024
            )
        } else {
            TextReaderBudget(
                smallFileByteLimit = 2L * MIB,
                decodedChunkCharLimit = 32 * 1024,
                maxCachedChunkCount = 5,
                maxCachedCharacters = 160 * 1024,
                maxVisualSegmentCharacters = 4 * 1024,
                maxStoredSearchMatches = 1_000,
                binarySampleBytes = 16 * 1024
            )
        }
    }

    val default: TextReaderBudget = forDevice(TextReaderDeviceProfile(256, false))

    // A disk guard, not a render limit. Large files remain chunked and are never held in memory.
    const val ABSOLUTE_SESSION_BYTES: Long = 1024L * 1024L * 1024L
    const val RESERVED_FREE_BYTES: Long = 64L * 1024L * 1024L
}

enum class TextEncoding { UTF8, UTF16_LE, UTF16_BE, UTF32_LE, UTF32_BE, WINDOWS_1252 }

enum class TextLoadingMode { IN_MEMORY, CHUNKED }
