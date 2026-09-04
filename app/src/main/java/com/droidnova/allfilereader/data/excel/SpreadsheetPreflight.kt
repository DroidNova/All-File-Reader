package com.droidnova.allfilereader.data.excel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.droidnova.allfilereader.BuildConfig
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal object SpreadsheetLimits {
    const val MAX_INPUT_BYTES = 50L * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 8_192
    const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    const val MAX_INSPECTED_BYTES = 512L * 1024 * 1024
    const val COMPRESSION_CHECK_BYTES = 16L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 1_000L
    const val MAX_SHEETS = 200
    const val SNIFF_BYTES = 16 * 1024
    const val MAX_CFB_SECTORS = 131_072
    const val MAX_CFB_STEPS = 262_144
    const val MAX_DIRECTORY_ENTRIES = 16_384
    const val MAX_WORKBOOK_INSPECTION_BYTES = 8L * 1024 * 1024
}

enum class SpreadsheetExpectedFormat { XLS, XLSX, XLSM, XLSB, CSV, TSV, ODS, UNKNOWN }
enum class SpreadsheetSignature { ZIP_OPC, CFB, XML, DELIMITED, UNKNOWN }
enum class SpreadsheetContainer { XLS_CFB, OPC_ZIP, ODS_ZIP, XML, DELIMITED }
enum class BiffVersion { BIFF5, BIFF8 }
enum class PreflightReason {
    INPUT_SIZE_LIMIT, ENTRY_COUNT_LIMIT, ENTRY_SIZE_LIMIT, TOTAL_UNCOMPRESSED_LIMIT,
    SUSPICIOUS_COMPRESSION, PATH_TRAVERSAL, CORRUPTED_CONTAINER, ENCRYPTED,
    UNSUPPORTED_SIGNATURE, FORMAT_MISMATCH, WRONG_OLE_DOCUMENT, UNSUPPORTED_BIFF
}

internal sealed class SpreadsheetPreflightException(val reason: PreflightReason) : Exception() {
    class TooLarge : SpreadsheetPreflightException(PreflightReason.INPUT_SIZE_LIMIT)
    class Safety(reason: PreflightReason) : SpreadsheetPreflightException(reason)
    class Corrupted : SpreadsheetPreflightException(PreflightReason.CORRUPTED_CONTAINER)
    class Encrypted : SpreadsheetPreflightException(PreflightReason.ENCRYPTED)
    class Unsupported : SpreadsheetPreflightException(PreflightReason.UNSUPPORTED_SIGNATURE)
    class FormatMismatch : SpreadsheetPreflightException(PreflightReason.FORMAT_MISMATCH)
    class WrongOleDocument : SpreadsheetPreflightException(PreflightReason.WRONG_OLE_DOCUMENT)
    class UnsupportedBiff : SpreadsheetPreflightException(PreflightReason.UNSUPPORTED_BIFF)
}

data class SpreadsheetSession(
    val file: File,
    val expectedFormat: SpreadsheetExpectedFormat,
    val container: SpreadsheetContainer,
    val mediaType: String,
    val biffVersion: BiffVersion? = null
)

/** A single format contract, derived before bytes are assigned to a parser. */
internal object SpreadsheetFormatResolver {
    private val mimeFormats = mapOf(
        "application/vnd.ms-excel" to SpreadsheetExpectedFormat.XLS,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to SpreadsheetExpectedFormat.XLSX,
        "application/vnd.ms-excel.sheet.macroenabled.12" to SpreadsheetExpectedFormat.XLSM,
        "application/vnd.ms-excel.sheet.binary.macroenabled.12" to SpreadsheetExpectedFormat.XLSB,
        "text/csv" to SpreadsheetExpectedFormat.CSV,
        "text/tab-separated-values" to SpreadsheetExpectedFormat.TSV,
        "application/vnd.oasis.opendocument.spreadsheet" to SpreadsheetExpectedFormat.ODS
    )
    private val extensionFormats = SpreadsheetExpectedFormat.entries
        .filter { it != SpreadsheetExpectedFormat.UNKNOWN }.associateBy { it.name.lowercase(Locale.ROOT) }

    fun resolve(extension: String?, mimeType: String?): SpreadsheetExpectedFormat {
        val byExtension = extension?.trim()?.lowercase(Locale.ROOT)?.let(extensionFormats::get)
        val normalizedMime = mimeType?.trim()?.lowercase(Locale.ROOT)
        val byMime = normalizedMime?.let(mimeFormats::get)
        if (byExtension != null && byMime != null && byExtension != byMime) {
            throw SpreadsheetPreflightException.FormatMismatch()
        }
        return byExtension ?: byMime ?: SpreadsheetExpectedFormat.UNKNOWN
    }
}

/** Copies once to private storage, then applies format-specific bounded validation. */
internal class SpreadsheetPreflight(private val resolver: ContentResolver?, private val cacheDir: File) {
    suspend fun copyAndValidate(
        uri: Uri,
        declaredMime: String?,
        declaredExtension: String?,
        declaredSize: Long?
    ): SpreadsheetSession {
        val expected = SpreadsheetFormatResolver.resolve(declaredExtension, declaredMime)
        trace("stage=FORMAT_CONTRACT expected=$expected")
        if (declaredSize != null && declaredSize > SpreadsheetLimits.MAX_INPUT_BYTES) reject(PreflightReason.INPUT_SIZE_LIMIT)
        val target = File.createTempFile("spreadsheet_session_", ".bin", cacheDir)
        val started = System.nanoTime()
        try {
            resolver?.openInputStream(uri)?.use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > SpreadsheetLimits.MAX_INPUT_BYTES) reject(PreflightReason.INPUT_SIZE_LIMIT)
                    output.write(buffer, 0, count)
                }
            }} ?: throw FileNotFoundException()
            val session = validateSessionFile(target, expected)
            trace("stage=PREFLIGHT_COMPLETE result=SUCCESS expected=$expected container=${session.container} biff=${session.biffVersion ?: "NONE"} durationMs=${elapsedMs(started)}")
            return session
        } catch (error: Exception) {
            target.delete()
            trace("stage=PREFLIGHT_COMPLETE result=REJECTED expected=$expected code=${safeCode(error)} exception=${error.javaClass.simpleName} durationMs=${elapsedMs(started)}")
            throw error
        }
    }

    internal suspend fun inspectSessionFile(file: File): SpreadsheetSignature {
        val signature = sniff(file)
        when (signature) {
            SpreadsheetSignature.ZIP_OPC -> inspectZip(file)
            SpreadsheetSignature.CFB -> CfbXlsInspector(file).inspect()
            SpreadsheetSignature.XML, SpreadsheetSignature.DELIMITED -> Unit
            SpreadsheetSignature.UNKNOWN -> reject(PreflightReason.UNSUPPORTED_SIGNATURE)
        }
        return signature
    }

    internal suspend fun validateSessionFile(file: File, expected: SpreadsheetExpectedFormat): SpreadsheetSession {
        coroutineContext.ensureActive()
        val signature = sniff(file)
        trace("stage=SIGNATURE expected=$expected detected=$signature")
        return when (expected) {
            SpreadsheetExpectedFormat.XLS -> {
                if (signature == SpreadsheetSignature.UNKNOWN) reject(PreflightReason.CORRUPTED_CONTAINER)
                if (signature != SpreadsheetSignature.CFB) reject(PreflightReason.FORMAT_MISMATCH)
                val result = CfbXlsInspector(file).inspect()
                SpreadsheetSession(file, expected, SpreadsheetContainer.XLS_CFB, "application/vnd.ms-excel", result.biffVersion)
            }
            SpreadsheetExpectedFormat.XLSX, SpreadsheetExpectedFormat.XLSM, SpreadsheetExpectedFormat.XLSB -> {
                if (signature != SpreadsheetSignature.ZIP_OPC) reject(PreflightReason.FORMAT_MISMATCH)
                val zipKind = inspectZip(file)
                if (zipKind == SpreadsheetContainer.ODS_ZIP) reject(PreflightReason.FORMAT_MISMATCH)
                SpreadsheetSession(file, expected, SpreadsheetContainer.OPC_ZIP, "application/octet-stream")
            }
            SpreadsheetExpectedFormat.ODS -> {
                if (signature != SpreadsheetSignature.ZIP_OPC) reject(PreflightReason.FORMAT_MISMATCH)
                if (inspectZip(file) != SpreadsheetContainer.ODS_ZIP) reject(PreflightReason.FORMAT_MISMATCH)
                SpreadsheetSession(file, expected, SpreadsheetContainer.ODS_ZIP, "application/vnd.oasis.opendocument.spreadsheet")
            }
            SpreadsheetExpectedFormat.CSV, SpreadsheetExpectedFormat.TSV -> {
                if (signature != SpreadsheetSignature.DELIMITED) reject(PreflightReason.FORMAT_MISMATCH)
                validateDelimiter(file, expected)
                SpreadsheetSession(file, expected, SpreadsheetContainer.DELIMITED, if (expected == SpreadsheetExpectedFormat.CSV) "text/csv" else "text/tab-separated-values")
            }
            SpreadsheetExpectedFormat.UNKNOWN -> when (signature) {
                SpreadsheetSignature.XML -> SpreadsheetSession(file, expected, SpreadsheetContainer.XML, "application/xml")
                else -> reject(PreflightReason.UNSUPPORTED_SIGNATURE)
            }
        }
    }

    private fun sniff(file: File): SpreadsheetSignature {
        val prefix = ByteArray(minOf(SpreadsheetLimits.SNIFF_BYTES.toLong(), file.length().coerceAtLeast(0)).toInt())
        val count = file.inputStream().use { it.read(prefix) }
        if (count < 1) return SpreadsheetSignature.UNKNOWN
        fun starts(vararg bytes: Int) = count >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 255) == bytes[it] }
        if (starts(0x50, 0x4b, 0x03, 0x04) || starts(0x50, 0x4b, 0x05, 0x06) || starts(0x50, 0x4b, 0x07, 0x08)) return SpreadsheetSignature.ZIP_OPC
        if (starts(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return SpreadsheetSignature.CFB
        val text = prefix.copyOf(count).toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val lower = text.lowercase(Locale.ROOT)
        if (lower.startsWith("<?xml") || lower.startsWith("<workbook") || lower.contains("<office:document")) return SpreadsheetSignature.XML
        if (prefix.copyOf(count).none { it == 0.toByte() } && (text.contains('\t') || text.contains(','))) return SpreadsheetSignature.DELIMITED
        return SpreadsheetSignature.UNKNOWN
    }

    private fun validateDelimiter(file: File, expected: SpreadsheetExpectedFormat) {
        val sample = file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(SpreadsheetLimits.SNIFF_BYTES) }
        val delimiter = if (expected == SpreadsheetExpectedFormat.TSV) '\t' else ','
        if (!sample.contains(delimiter)) reject(PreflightReason.FORMAT_MISMATCH)
    }

    private suspend fun inspectZip(file: File): SpreadsheetContainer {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() > SpreadsheetLimits.MAX_ZIP_ENTRIES) reject(PreflightReason.ENTRY_COUNT_LIMIT)
                var inspected = 0L
                val names = HashSet<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    val name = normalize(entry.name) ?: reject(PreflightReason.PATH_TRAVERSAL)
                    names += name
                    if (name.equals("EncryptedPackage", true) || name.equals("EncryptionInfo", true)) reject(PreflightReason.ENCRYPTED)
                    if (entry.size >= 0 && entry.size > SpreadsheetLimits.MAX_ENTRY_BYTES) reject(PreflightReason.ENTRY_SIZE_LIMIT)
                    if (entry.size > SpreadsheetLimits.COMPRESSION_CHECK_BYTES && entry.compressedSize > 0 && entry.size / entry.compressedSize > SpreadsheetLimits.MAX_COMPRESSION_RATIO) reject(PreflightReason.SUSPICIOUS_COMPRESSION)
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var actual = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            actual += read
                            inspected += read
                            if (actual > SpreadsheetLimits.MAX_ENTRY_BYTES) reject(PreflightReason.ENTRY_SIZE_LIMIT)
                            if (inspected > SpreadsheetLimits.MAX_INSPECTED_BYTES) reject(PreflightReason.TOTAL_UNCOMPRESSED_LIMIT)
                        }
                    }
                }
                trace("stage=ZIP_DIRECTORY entries=${zip.size()}")
                return if (names.contains("META-INF/manifest.xml")) SpreadsheetContainer.ODS_ZIP else SpreadsheetContainer.OPC_ZIP
            }
        } catch (error: SpreadsheetPreflightException) {
            throw error
        } catch (_: ZipException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        } catch (_: java.io.IOException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        }
    }

    private fun normalize(raw: String): String? {
        val value = raw.replace('\\', '/')
        if (value.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(value)) return null
        val out = ArrayDeque<String>()
        for (part in value.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(part)
        }
        return out.joinToString("/")
    }

    private fun reject(reason: PreflightReason): Nothing {
        throw when (reason) {
            PreflightReason.INPUT_SIZE_LIMIT -> SpreadsheetPreflightException.TooLarge()
            PreflightReason.ENCRYPTED -> SpreadsheetPreflightException.Encrypted()
            PreflightReason.CORRUPTED_CONTAINER -> SpreadsheetPreflightException.Corrupted()
            PreflightReason.UNSUPPORTED_SIGNATURE -> SpreadsheetPreflightException.Unsupported()
            PreflightReason.FORMAT_MISMATCH -> SpreadsheetPreflightException.FormatMismatch()
            PreflightReason.WRONG_OLE_DOCUMENT -> SpreadsheetPreflightException.WrongOleDocument()
            PreflightReason.UNSUPPORTED_BIFF -> SpreadsheetPreflightException.UnsupportedBiff()
            else -> SpreadsheetPreflightException.Safety(reason)
        }
    }

    private fun safeCode(error: Exception) = (error as? SpreadsheetPreflightException)?.reason?.name ?: "PROCESSING_FAILURE"
    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun trace(message: String) { if (BuildConfig.DEBUG) runCatching { Log.i("SpreadsheetTrace", message) } }
}

internal data class CfbXlsResult(val workbookName: String, val biffVersion: BiffVersion)

/**
 * A deliberately narrow CFB reader. It validates FAT/DIFAT, directory and stream chains,
 * then inspects only the first bounded BIFF records. SheetJS remains the final workbook parser.
 */
internal class CfbXlsInspector(private val file: File) {
    private lateinit var raf: RandomAccessFile
    private var sectorSize = 0
    private var miniSectorSize = 0
    private var sectorCount = 0
    private var miniCutoff = 4096L
    private var majorVersion = 0
    private lateinit var fat: IntArray
    private var steps = 0

    suspend fun inspect(): CfbXlsResult {
        try {
            RandomAccessFile(file, "r").use { input ->
                raf = input
                val header = ByteArray(512)
                input.readFully(header)
                validateHeader(header)
                val ints = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val fatSectorCount = ints.getInt(44)
                val firstDirectory = ints.getInt(48)
                miniCutoff = uint(ints.getInt(56))
                if (miniCutoff != 4096L) reject(PreflightReason.CORRUPTED_CONTAINER)
                val firstMiniFat = ints.getInt(60)
                val miniFatCount = ints.getInt(64)
                val firstDifat = ints.getInt(68)
                val difatCount = ints.getInt(72)
                val fatSectors = readDifat(header, fatSectorCount, firstDifat, difatCount)
                fat = readFat(fatSectors)
                val directoryBytes = readRegularChainToEnd(firstDirectory, SpreadsheetLimits.MAX_DIRECTORY_ENTRIES * 128L)
                val entries = parseDirectory(directoryBytes)
                val names = entries.map { it.name }.toSet()
                if ("EncryptedPackage" in names && "EncryptionInfo" in names) reject(PreflightReason.ENCRYPTED)
                if ("WordDocument" in names || "PowerPoint Document" in names) reject(PreflightReason.WRONG_OLE_DOCUMENT)
                val workbook = entries.firstOrNull { it.type == 2 && (it.name == "Workbook" || it.name == "Book") }
                    ?: reject(PreflightReason.WRONG_OLE_DOCUMENT)
                val root = entries.firstOrNull { it.type == 5 } ?: reject(PreflightReason.CORRUPTED_CONTAINER)
                if (workbook.size <= 0 || workbook.size > SpreadsheetLimits.MAX_WORKBOOK_INSPECTION_BYTES) reject(PreflightReason.ENTRY_SIZE_LIMIT)
                val workbookBytes = if (workbook.size < miniCutoff) {
                    val miniFat = readMiniFat(firstMiniFat, miniFatCount)
                    val rootStream = readRegularChain(root.startSector, minOf(root.size, SpreadsheetLimits.MAX_INPUT_BYTES))
                    readMiniChain(workbook.startSector, workbook.size, miniFat, rootStream)
                } else readRegularChain(workbook.startSector, workbook.size)
                val biff = inspectBiff(workbookBytes)
                coroutineContext.ensureActive()
                if (BuildConfig.DEBUG) Log.i("SpreadsheetTrace", "stage=CFB_COMPLETE sectors=$sectorCount directoryEntries=${entries.size} workbookStream=${workbook.name} biff=$biff")
                return CfbXlsResult(workbook.name, biff)
            }
        } catch (error: SpreadsheetPreflightException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: EOFException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        } catch (_: java.io.IOException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        } catch (_: ArithmeticException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        } catch (_: IndexOutOfBoundsException) {
            reject(PreflightReason.CORRUPTED_CONTAINER)
        }
    }

    private fun validateHeader(header: ByteArray) {
        if (!header.copyOfRange(0, 8).contentEquals(MAGIC)) reject(PreflightReason.CORRUPTED_CONTAINER)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(28).toInt() and 0xffff != 0xfffe) reject(PreflightReason.CORRUPTED_CONTAINER)
        val sectorShift = b.getShort(30).toInt() and 0xffff
        val miniShift = b.getShort(32).toInt() and 0xffff
        majorVersion = b.getShort(26).toInt() and 0xffff
        if ((majorVersion == 3 && sectorShift != 9) || (majorVersion == 4 && sectorShift != 12) || majorVersion !in 3..4) reject(PreflightReason.CORRUPTED_CONTAINER)
        if (sectorShift !in setOf(9, 12) || miniShift != 6) reject(PreflightReason.CORRUPTED_CONTAINER)
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniShift
        if (file.length() < sectorSize.toLong() * 2 || (file.length() - sectorSize) % sectorSize != 0L) reject(PreflightReason.CORRUPTED_CONTAINER)
        sectorCount = ((file.length() - sectorSize) / sectorSize).toInt()
        if (sectorCount !in 1..SpreadsheetLimits.MAX_CFB_SECTORS) reject(PreflightReason.ENTRY_COUNT_LIMIT)
    }

    private suspend fun readDifat(header: ByteArray, declaredFat: Int, firstDifat: Int, declaredDifat: Int): IntArray {
        if (declaredFat !in 1..sectorCount || declaredDifat !in 0..sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
        val result = ArrayList<Int>(declaredFat)
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        repeat(109) { val value = h.getInt(76 + it * 4); if (value >= 0) result += checkedSector(value) else if (value != FREE_SECTOR) reject(PreflightReason.CORRUPTED_CONTAINER) }
        var current = firstDifat
        val visited = HashSet<Int>()
        repeat(declaredDifat) {
            coroutineContext.ensureActive()
            if (!visited.add(checkedSector(current))) reject(PreflightReason.CORRUPTED_CONTAINER)
            val bytes = readSector(current)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sectorSize / 4 - 1) { val value = bb.int; if (value >= 0) result += checkedSector(value) else if (value != FREE_SECTOR) reject(PreflightReason.CORRUPTED_CONTAINER) }
            current = bb.int
            step()
        }
        if (declaredDifat == 0 && firstDifat != END_OF_CHAIN && firstDifat != FREE_SECTOR) reject(PreflightReason.CORRUPTED_CONTAINER)
        if (declaredDifat > 0 && current != END_OF_CHAIN) reject(PreflightReason.CORRUPTED_CONTAINER)
        if (result.size != declaredFat || result.distinct().size != result.size) reject(PreflightReason.CORRUPTED_CONTAINER)
        return result.toIntArray()
    }

    private suspend fun readFat(sectors: IntArray): IntArray {
        val values = ArrayList<Int>(sectors.size * sectorSize / 4)
        for (sector in sectors) {
            coroutineContext.ensureActive()
            val bb = ByteBuffer.wrap(readSector(sector)).order(ByteOrder.LITTLE_ENDIAN)
            repeat(sectorSize / 4) { values += bb.int }
            step()
        }
        if (values.size < sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
        return values.toIntArray()
    }

    private suspend fun readRegularChain(start: Int, declaredSize: Long): ByteArray {
        if (declaredSize < 0 || declaredSize > SpreadsheetLimits.MAX_INPUT_BYTES) reject(PreflightReason.ENTRY_SIZE_LIMIT)
        val needed = Math.addExact(declaredSize, sectorSize - 1L) / sectorSize
        if (needed > sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
        val output = ByteArray(Math.multiplyExact(needed.toInt(), sectorSize))
        var offset = 0
        walkChain(start, fat, needed.toInt()) { sector ->
            coroutineContext.ensureActive()
            val bytes = readSector(sector)
            bytes.copyInto(output, offset)
            offset += bytes.size
        }
        return output.copyOf(declaredSize.toInt())
    }

    private suspend fun readRegularChainToEnd(start: Int, maximumSize: Long): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var current = start
        val visited = HashSet<Int>()
        while (current != END_OF_CHAIN) {
            coroutineContext.ensureActive()
            if (current < 0 || current >= fat.size || !visited.add(current)) reject(PreflightReason.CORRUPTED_CONTAINER)
            val chunk = readSector(current)
            total = Math.addExact(total, chunk.size.toLong())
            if (total > maximumSize) reject(PreflightReason.ENTRY_COUNT_LIMIT)
            chunks += chunk
            current = fat[current]
            if (current == FREE_SECTOR || current == FAT_SECTOR || current == DIFAT_SECTOR) reject(PreflightReason.CORRUPTED_CONTAINER)
            step()
        }
        if (chunks.isEmpty()) reject(PreflightReason.CORRUPTED_CONTAINER)
        return ByteArray(total.toInt()).also { output ->
            var offset = 0
            chunks.forEach { it.copyInto(output, offset); offset += it.size }
        }
    }

    private suspend fun readMiniFat(start: Int, count: Int): IntArray {
        if (count !in 1..sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
        val bytes = readRegularChain(start, Math.multiplyExact(count.toLong(), sectorSize.toLong()))
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 4) { bb.int }
    }

    private suspend fun readMiniChain(start: Int, size: Long, miniFat: IntArray, root: ByteArray): ByteArray {
        val needed = Math.addExact(size, miniSectorSize - 1L) / miniSectorSize
        val output = ByteArray(Math.multiplyExact(needed.toInt(), miniSectorSize))
        var offset = 0
        walkChain(start, miniFat, needed.toInt()) { sector ->
            val source = Math.multiplyExact(sector, miniSectorSize)
            if (source < 0 || source + miniSectorSize > root.size) reject(PreflightReason.CORRUPTED_CONTAINER)
            root.copyInto(output, offset, source, source + miniSectorSize)
            offset += miniSectorSize
        }
        return output.copyOf(size.toInt())
    }

    private suspend fun walkChain(start: Int, table: IntArray, expected: Int, consume: suspend (Int) -> Unit) {
        if (expected <= 0) return
        var current = start
        val visited = HashSet<Int>()
        var count = 0
        while (current != END_OF_CHAIN) {
            coroutineContext.ensureActive()
            if (current < 0 || current >= table.size || !visited.add(current)) reject(PreflightReason.CORRUPTED_CONTAINER)
            consume(current)
            count++
            if (count > expected || count > sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
            current = table[current]
            if (current == FREE_SECTOR || current == FAT_SECTOR || current == DIFAT_SECTOR) reject(PreflightReason.CORRUPTED_CONTAINER)
            step()
        }
        if (count != expected) reject(PreflightReason.CORRUPTED_CONTAINER)
    }

    private fun parseDirectory(bytes: ByteArray): List<CfbDirectoryEntry> {
        if (bytes.size % 128 != 0) reject(PreflightReason.CORRUPTED_CONTAINER)
        val entries = ArrayList<CfbDirectoryEntry>()
        for (offset in bytes.indices step 128) {
            if (entries.size >= SpreadsheetLimits.MAX_DIRECTORY_ENTRIES) reject(PreflightReason.ENTRY_COUNT_LIMIT)
            val bb = ByteBuffer.wrap(bytes, offset, 128).order(ByteOrder.LITTLE_ENDIAN)
            val nameLength = bb.getShort(offset + 64).toInt() and 0xffff
            val type = bytes[offset + 66].toInt() and 0xff
            if (type == 0) continue
            if (type !in 1..5 || nameLength !in 2..64 || nameLength % 2 != 0) reject(PreflightReason.CORRUPTED_CONTAINER)
            val chars = bytes.copyOfRange(offset, offset + nameLength - 2)
            if (chars.size % 2 != 0) reject(PreflightReason.CORRUPTED_CONTAINER)
            val name = chars.toString(Charsets.UTF_16LE)
            if (name.isBlank() || name.any { it == '\u0000' } || !name.toByteArray(Charsets.UTF_16LE).contentEquals(chars)) reject(PreflightReason.CORRUPTED_CONTAINER)
            val start = bb.getInt(offset + 116)
            val rawSize = bb.getLong(offset + 120)
            if (rawSize < 0 || (majorVersion == 3 && rawSize ushr 32 != 0L)) reject(PreflightReason.CORRUPTED_CONTAINER)
            val size = rawSize
            if (size > SpreadsheetLimits.MAX_INPUT_BYTES || size > file.length()) reject(PreflightReason.ENTRY_SIZE_LIMIT)
            entries += CfbDirectoryEntry(name, type, start, size)
        }
        return entries
    }

    internal fun inspectBiffForTest(bytes: ByteArray): BiffVersion = inspectBiff(bytes)

    private fun inspectBiff(bytes: ByteArray): BiffVersion {
        if (bytes.size < 8) reject(PreflightReason.CORRUPTED_CONTAINER)
        var offset = 0
        var version: BiffVersion? = null
        var sawWorkbookGlobals = false
        var records = 0
        while (offset + 4 <= bytes.size && offset < SpreadsheetLimits.MAX_WORKBOOK_INSPECTION_BYTES) {
            val id = ushort(bytes, offset)
            val length = ushort(bytes, offset + 2)
            val end = Math.addExact(offset, Math.addExact(4, length))
            if (end > bytes.size) reject(PreflightReason.CORRUPTED_CONTAINER)
            if (id == FILEPASS) reject(PreflightReason.ENCRYPTED)
            if (id == BOF) {
                if (length < 4) reject(PreflightReason.CORRUPTED_CONTAINER)
                val rawVersion = ushort(bytes, offset + 4)
                val type = ushort(bytes, offset + 6)
                val detected = when (rawVersion) {
                    0x0600 -> BiffVersion.BIFF8
                    0x0500 -> BiffVersion.BIFF5
                    else -> reject(PreflightReason.UNSUPPORTED_BIFF)
                }
                if (version == null) version = detected else if (version != detected) reject(PreflightReason.CORRUPTED_CONTAINER)
                if (type == 0x0005) sawWorkbookGlobals = true
            }
            offset = end
            if (++records > 100_000) reject(PreflightReason.ENTRY_COUNT_LIMIT)
            if (sawWorkbookGlobals && id == EOF_RECORD) break
        }
        if (!sawWorkbookGlobals || version == null) reject(PreflightReason.UNSUPPORTED_BIFF)
        return version
    }

    private fun readSector(index: Int): ByteArray {
        checkedSector(index)
        val offset = Math.addExact(sectorSize.toLong(), Math.multiplyExact(index.toLong(), sectorSize.toLong()))
        if (offset < sectorSize || offset + sectorSize > raf.length()) reject(PreflightReason.CORRUPTED_CONTAINER)
        return ByteArray(sectorSize).also { raf.seek(offset); raf.readFully(it) }
    }

    private fun checkedSector(value: Int): Int {
        if (value !in 0 until sectorCount) reject(PreflightReason.CORRUPTED_CONTAINER)
        return value
    }

    private fun step() { if (++steps > SpreadsheetLimits.MAX_CFB_STEPS) reject(PreflightReason.ENTRY_COUNT_LIMIT) }
    private fun uint(value: Int) = value.toLong() and 0xffffffffL
    private fun ushort(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun reject(reason: PreflightReason): Nothing = throw when (reason) {
        PreflightReason.INPUT_SIZE_LIMIT -> SpreadsheetPreflightException.TooLarge()
        PreflightReason.ENCRYPTED -> SpreadsheetPreflightException.Encrypted()
        PreflightReason.CORRUPTED_CONTAINER -> SpreadsheetPreflightException.Corrupted()
        PreflightReason.UNSUPPORTED_SIGNATURE -> SpreadsheetPreflightException.Unsupported()
        PreflightReason.FORMAT_MISMATCH -> SpreadsheetPreflightException.FormatMismatch()
        PreflightReason.WRONG_OLE_DOCUMENT -> SpreadsheetPreflightException.WrongOleDocument()
        PreflightReason.UNSUPPORTED_BIFF -> SpreadsheetPreflightException.UnsupportedBiff()
        else -> SpreadsheetPreflightException.Safety(reason)
    }

    private data class CfbDirectoryEntry(val name: String, val type: Int, val startSector: Int, val size: Long)

    companion object {
        private val MAGIC = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
        private const val FREE_SECTOR = -1
        private const val END_OF_CHAIN = -2
        private const val FAT_SECTOR = -3
        private const val DIFAT_SECTOR = -4
        private const val BOF = 0x0809
        private const val FILEPASS = 0x002f
        private const val EOF_RECORD = 0x000a
    }
}
