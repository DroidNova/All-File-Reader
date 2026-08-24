package com.droidnova.allfilereader.data.excel

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
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
}

enum class SpreadsheetSignature { ZIP_OPC, CFB, XML, DELIMITED, UNKNOWN }
enum class PreflightReason { INPUT_SIZE_LIMIT, ENTRY_COUNT_LIMIT, ENTRY_SIZE_LIMIT, TOTAL_UNCOMPRESSED_LIMIT, SUSPICIOUS_COMPRESSION, PATH_TRAVERSAL, CORRUPTED_CONTAINER, ENCRYPTED, UNSUPPORTED_SIGNATURE }
internal sealed class SpreadsheetPreflightException(val reason: PreflightReason) : Exception() {
    class TooLarge : SpreadsheetPreflightException(PreflightReason.INPUT_SIZE_LIMIT)
    class Safety(reason: PreflightReason) : SpreadsheetPreflightException(reason)
    class Corrupted : SpreadsheetPreflightException(PreflightReason.CORRUPTED_CONTAINER)
    class Encrypted : SpreadsheetPreflightException(PreflightReason.ENCRYPTED)
    class Unsupported : SpreadsheetPreflightException(PreflightReason.UNSUPPORTED_SIGNATURE)
}
data class SpreadsheetSession(val file: File, val signature: SpreadsheetSignature, val mediaType: String)

/** Copies once to app-private storage. Every validator and WebView request opens an independent reader. */
internal class SpreadsheetPreflight(private val resolver: ContentResolver?, private val cacheDir: File) {
    suspend fun copyAndValidate(uri: Uri, declaredMime: String?, declaredSize: Long?): SpreadsheetSession {
        trace("declaredSize=${if (declaredSize == null || declaredSize < 0) "UNKNOWN" else "KNOWN"}")
        trace("mime=${if (declaredMime.isNullOrBlank() || declaredMime.equals("application/octet-stream", true)) "GENERIC" else "SPECIFIC"}")
        if (declaredSize != null && declaredSize > SpreadsheetLimits.MAX_INPUT_BYTES) reject(PreflightReason.INPUT_SIZE_LIMIT)
        val target = File.createTempFile("spreadsheet_session_", ".bin", cacheDir)
        try {
            resolver?.openInputStream(uri)?.use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L
                while (true) { coroutineContext.ensureActive(); val count=input.read(buffer);if(count<0)break;total+=count;if(total>SpreadsheetLimits.MAX_INPUT_BYTES)reject(PreflightReason.INPUT_SIZE_LIMIT);output.write(buffer,0,count) }
            }} ?: throw FileNotFoundException()
            val signature = sniff(target)
            trace("signature=$signature")
            when (signature) {
                SpreadsheetSignature.ZIP_OPC -> inspectZip(target)
                SpreadsheetSignature.CFB -> inspectCfb(target)
                SpreadsheetSignature.XML, SpreadsheetSignature.DELIMITED -> Unit
                SpreadsheetSignature.UNKNOWN -> reject(PreflightReason.UNSUPPORTED_SIGNATURE)
            }
            trace("preflight=ACCEPTED")
            return SpreadsheetSession(target, signature, mediaType(signature))
        } catch (error: Exception) { target.delete(); throw error }
    }

    internal suspend fun inspectSessionFile(file: File): SpreadsheetSignature {
        val signature=sniff(file);when(signature){SpreadsheetSignature.ZIP_OPC->inspectZip(file);SpreadsheetSignature.CFB->inspectCfb(file);SpreadsheetSignature.XML,SpreadsheetSignature.DELIMITED->Unit;SpreadsheetSignature.UNKNOWN->reject(PreflightReason.UNSUPPORTED_SIGNATURE)};return signature
    }

    private fun sniff(file: File): SpreadsheetSignature {
        val prefix=ByteArray(minOf(SpreadsheetLimits.SNIFF_BYTES,file.length().coerceAtLeast(0).toInt()));val count=file.inputStream().use{it.read(prefix)};if(count<1)return SpreadsheetSignature.UNKNOWN
        fun starts(vararg bytes:Int)=count>=bytes.size&&bytes.indices.all{(prefix[it].toInt() and 255)==bytes[it]}
        if(starts(0x50,0x4b,0x03,0x04)||starts(0x50,0x4b,0x05,0x06)||starts(0x50,0x4b,0x07,0x08))return SpreadsheetSignature.ZIP_OPC
        if(starts(0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1))return SpreadsheetSignature.CFB
        val text=prefix.copyOf(count).toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\t','\r','\n');val lower=text.lowercase(Locale.ROOT)
        if(lower.startsWith("<?xml")||lower.startsWith("<workbook")||lower.contains("<office:document"))return SpreadsheetSignature.XML
        if(prefix.copyOf(count).none{it==0.toByte()} && (text.contains('\t')||text.lineSequence().take(8).count{it.contains(',')}>=2))return SpreadsheetSignature.DELIMITED
        return SpreadsheetSignature.UNKNOWN
    }

    private suspend fun inspectZip(file:File){try{ZipFile(file).use{zip->
        if(zip.size()>SpreadsheetLimits.MAX_ZIP_ENTRIES)reject(PreflightReason.ENTRY_COUNT_LIMIT);trace("entryCount=${zip.size()}")
        var inspected=0L;val names=HashSet<String>();val entries=zip.entries()
        while(entries.hasMoreElements()){coroutineContext.ensureActive();val entry=entries.nextElement();val name=normalize(entry.name)?:reject(PreflightReason.PATH_TRAVERSAL);names+=name
            if(name.equals("EncryptedPackage",true)||name.equals("EncryptionInfo",true))reject(PreflightReason.ENCRYPTED)
            if(entry.size>=0&&entry.size>SpreadsheetLimits.MAX_ENTRY_BYTES)reject(PreflightReason.ENTRY_SIZE_LIMIT)
            if(entry.size>SpreadsheetLimits.COMPRESSION_CHECK_BYTES&&entry.compressedSize>0&&entry.size/entry.compressedSize>SpreadsheetLimits.MAX_COMPRESSION_RATIO)reject(PreflightReason.SUSPICIOUS_COMPRESSION)
            zip.getInputStream(entry).use{stream->val b=ByteArray(DEFAULT_BUFFER_SIZE);var actual=0L;while(true){val n=stream.read(b);if(n<0)break;actual+=n;inspected+=n;if(actual>SpreadsheetLimits.MAX_ENTRY_BYTES)reject(PreflightReason.ENTRY_SIZE_LIMIT);if(inspected>SpreadsheetLimits.MAX_INSPECTED_BYTES)reject(PreflightReason.TOTAL_UNCOMPRESSED_LIMIT)}}
        }
        resolveOfficeTarget(zip)?.let{trace("officeDocumentTarget=$it")}
        // OPC metadata is useful for classification, but optional/producer-specific parts are left to SheetJS.
        if(names.contains("META-INF/manifest.xml")) trace("container=ODS")
    }}catch(e:SpreadsheetPreflightException){throw e}catch(_:ZipException){reject(PreflightReason.CORRUPTED_CONTAINER)}catch(_:java.io.IOException){reject(PreflightReason.CORRUPTED_CONTAINER)}}

    private fun resolveOfficeTarget(zip:ZipFile):String?{val rel=zip.getEntry("_rels/.rels")?:return null;if(rel.size>2L*1024*1024)return null;val xml=zip.getInputStream(rel).bufferedReader().use{it.readText()};val relationship=Regex("<Relationship\\b[^>]*>",RegexOption.IGNORE_CASE);for(m in relationship.findAll(xml)){val tag=m.value;val type=attr(tag,"Type")?:continue;if(type.endsWith("/officeDocument",true)){val target=attr(tag,"Target")?:return null;return normalize(target.removePrefix("/"))}};return null}
    private fun attr(tag:String,name:String)=Regex("\\b$name\\s*=\\s*(['\"])(.*?)\\1",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).find(tag)?.groupValues?.get(2)
    private fun normalize(raw:String):String?{val value=raw.replace('\\','/');if(value.startsWith('/')||Regex("^[A-Za-z]:/").containsMatchIn(value))return null;val out=ArrayDeque<String>();for(part in value.split('/'))when(part){"","."->Unit;".."->if(out.isEmpty())return null else out.removeLast();else->out.addLast(part)};return out.joinToString("/")}
    private fun inspectCfb(file:File){val header=ByteArray(512);val n=file.inputStream().use{it.read(header)};if(n<512)reject(PreflightReason.CORRUPTED_CONTAINER);val ascii=header.toString(Charsets.ISO_8859_1);if(ascii.contains("EncryptedPackage")||ascii.contains("EncryptionInfo"))reject(PreflightReason.ENCRYPTED)}
    private fun mediaType(s:SpreadsheetSignature)=when(s){SpreadsheetSignature.ZIP_OPC->"application/octet-stream";SpreadsheetSignature.CFB->"application/vnd.ms-excel";SpreadsheetSignature.XML->"application/xml";SpreadsheetSignature.DELIMITED->"text/plain";else->"application/octet-stream"}
    private fun reject(reason:PreflightReason):Nothing{trace("preflight=REJECTED reason=$reason");throw when(reason){PreflightReason.INPUT_SIZE_LIMIT->SpreadsheetPreflightException.TooLarge();PreflightReason.ENCRYPTED->SpreadsheetPreflightException.Encrypted();PreflightReason.CORRUPTED_CONTAINER->SpreadsheetPreflightException.Corrupted();PreflightReason.UNSUPPORTED_SIGNATURE->SpreadsheetPreflightException.Unsupported();else->SpreadsheetPreflightException.Safety(reason)}}
    private fun trace(message:String) { runCatching { Log.i("SpreadsheetTrace", message) } }
}
