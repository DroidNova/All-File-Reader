package com.droidnova.allfilereader.data.excel

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal object XlsxLimits {
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    const val MAX_ENTRIES = 8_192
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_INSPECTED_BYTES = 256L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 100L
    const val MAX_WORKSHEETS = 100
    const val MAX_ROWS = 100_000
    const val MAX_COLUMNS = 1_000
    const val MAX_CELLS = 2_000_000L
}

internal sealed class XlsxPreflightException : Exception() {
    class Invalid : XlsxPreflightException()
    class Unsafe : XlsxPreflightException()
    class Encrypted : XlsxPreflightException()
}

/** Makes one bounded copy in app-private cache and fully reads every ZIP entry to verify CRCs. */
internal class XlsxPreflight(private val resolver: ContentResolver, private val cacheDir: File) {
    suspend fun copyAndValidate(uri: Uri): File {
        val target = File.createTempFile("xlsx_session_", ".xlsx", cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > XlsxLimits.MAX_FILE_BYTES) throw XlsxPreflightException.Unsafe()
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw FileNotFoundException()
            if (target.length() == 0L) throw XlsxPreflightException.Invalid()
            inspect(target)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private suspend fun inspect(file: File) {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() !in 1..XlsxLimits.MAX_ENTRIES) throw XlsxPreflightException.Unsafe()
                val contentTypes = zip.getEntry("[Content_Types].xml") ?: throw XlsxPreflightException.Invalid()
                zip.getEntry("xl/workbook.xml") ?: throw XlsxPreflightException.Invalid()
                var sheets = 0
                var inspected = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(name) || name.split('/').any { it == ".." }) throw XlsxPreflightException.Unsafe()
                    if (name.matches(Regex("xl/worksheets/sheet[^/]*\\.xml", RegexOption.IGNORE_CASE))) sheets++
                    if (name.equals("EncryptedPackage", true) || name.equals("EncryptionInfo", true)) throw XlsxPreflightException.Encrypted()
                    if (entry.size < 0 || entry.compressedSize < 0 || entry.size > XlsxLimits.MAX_ENTRY_BYTES) throw XlsxPreflightException.Unsafe()
                    if ((entry.size > 0 && entry.compressedSize == 0L) || (entry.compressedSize > 0 && entry.size > entry.compressedSize * XlsxLimits.MAX_COMPRESSION_RATIO)) throw XlsxPreflightException.Unsafe()
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryRead = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            entryRead += count; inspected += count
                            if (entryRead > XlsxLimits.MAX_ENTRY_BYTES || inspected > XlsxLimits.MAX_INSPECTED_BYTES) throw XlsxPreflightException.Unsafe()
                        }
                    }
                }
                if (sheets !in 1..XlsxLimits.MAX_WORKSHEETS) throw XlsxPreflightException.Unsafe()
                val types = zip.getInputStream(contentTypes).bufferedReader().use { it.readText() }
                val normal = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
                if (normal !in types || "macroEnabled" in types || "vbaProject" in types || "application/vnd.ms-excel.sheet.binary" in types) throw XlsxPreflightException.Invalid()
            }
        } catch (error: XlsxPreflightException) { throw error }
        catch (_: ZipException) { throw XlsxPreflightException.Invalid() }
        catch (_: java.io.IOException) { throw XlsxPreflightException.Invalid() }
    }
}
