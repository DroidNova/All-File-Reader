package com.droidnova.allfilereader.data.powerpoint

import android.content.ContentResolver
import android.net.Uri
import java.io.*
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

sealed class PptxPreflightException : IOException() {
    class Encrypted : PptxPreflightException(); class Corrupt : PptxPreflightException()
    class Unsafe : PptxPreflightException(); class Unsupported : PptxPreflightException()
}
data class PptxSession(val file: File)

class PptxPreflight(private val resolver: ContentResolver, private val cacheDir: File) {
    fun copyAndValidate(uri: Uri): PptxSession {
        val dir = File(cacheDir, "pptx_sessions").apply { mkdirs() }
        val out = File.createTempFile("presentation-", ".pptx", dir)
        try {
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { output ->
                val buffer=ByteArray(64*1024); var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;if(total>MAX_ARCHIVE)throw PptxPreflightException.Unsafe();output.write(buffer,0,n)}
            }} ?: throw FileNotFoundException()
            validate(out); return PptxSession(out)
        } catch (e: Throwable) { out.delete(); throw e }
    }

    internal fun validate(file: File) {
        FileInputStream(file).use { input ->
            val header=ByteArray(8); if(input.read(header)<4)throw PptxPreflightException.Corrupt()
            if(header.contentEquals(byteArrayOf(0xD0.toByte(),0xCF.toByte(),0x11,0xE0.toByte(),0xA1.toByte(),0xB1.toByte(),0x1A,0xE1.toByte()))) throw PptxPreflightException.Encrypted()
            if(!(header[0]==0x50.toByte()&&header[1]==0x4b.toByte()&&header[2] in byteArrayOf(3,5,7)&&header[3] in byteArrayOf(4,6,8)))throw PptxPreflightException.Corrupt()
        }
        try { ZipFile(file).use { zip ->
            val seen=HashSet<String>(); var count=0;var total=0L;var media=0L
            val entries=zip.entries();while(entries.hasMoreElements()){
                val e=entries.nextElement();count++;if(count>MAX_ENTRIES)throw PptxPreflightException.Unsafe()
                val name=e.name.replace('\\','/');if(name.startsWith('/')||name.split('/').any{it==".."}||!seen.add(name))throw PptxPreflightException.Unsafe()
                if(e.size<0||e.size>MAX_ENTRY)throw PptxPreflightException.Unsafe();total+=e.size;if(total>MAX_TOTAL)throw PptxPreflightException.Unsafe()
                if(name.startsWith("ppt/media/")){media+=e.size;if(media>MAX_MEDIA)throw PptxPreflightException.Unsafe()}
            }
            for(required in listOf("[Content_Types].xml","ppt/presentation.xml")){val entry=zip.getEntry(required)?:throw PptxPreflightException.Corrupt();zip.getInputStream(entry).use(::parseXml)}
            if(zip.getEntry("EncryptionInfo")!=null||zip.getEntry("EncryptedPackage")!=null)throw PptxPreflightException.Encrypted()
        }} catch(e:PptxPreflightException){throw e}catch(e:ZipException){throw PptxPreflightException.Corrupt()}catch(e:Exception){throw PptxPreflightException.Corrupt()}
    }
    private fun parseXml(input:InputStream){val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.isXIncludeAware=false;f.isExpandEntityReferences=false;f.newDocumentBuilder().parse(input)}
    companion object { const val MAX_ENTRIES=4000;const val MAX_ENTRY=32L*1024*1024;const val MAX_TOTAL=256L*1024*1024;const val MAX_MEDIA=192L*1024*1024;const val MAX_ARCHIVE=256L*1024*1024 }
}
