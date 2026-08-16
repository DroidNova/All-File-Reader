package com.droidnova.allfilereader.data.word

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.droidnova.allfilereader.domain.model.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.coroutineContext

class InvalidDocxException : Exception()
class EncryptedDocxException : Exception()
class UnsafeDocxException : Exception()

data class DocxParseResult(val blockCount: Int, val images: Map<String, Bitmap>)

/** Secure, streaming OOXML parser. It never extracts archive paths to the filesystem. */
class DocxParser {
    suspend fun parse(file: File, onBatch: suspend (List<WordBlock>) -> Unit): DocxParseResult {
        try {
            ZipFile(file).use { zip ->
                validatePackage(zip)
                val styles = parseStyles(zip)
                val numbering = parseNumbering(zip)
                val relationships = parseRelationships(zip)
                val parsed = parseDocument(zip, styles, numbering, onBatch)
                val images = decodeImages(zip, relationships, parsed.second)
                return DocxParseResult(parsed.first, images)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: UnsafeDocxException) { throw error }
        catch (error: EncryptedDocxException) { throw error }
        catch (error: InvalidDocxException) { throw error }
        catch (_: ZipException) { throw InvalidDocxException() }
        catch (_: org.xmlpull.v1.XmlPullParserException) { throw InvalidDocxException() }
    }

    private fun validatePackage(zip: ZipFile) {
        if (zip.size() == 0 || zip.size() > MAX_ENTRIES) throw UnsafeDocxException()
        var declaredTotal = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name.replace('\\', '/')
            if (name.startsWith('/') || name.split('/').any { it == ".." }) throw UnsafeDocxException()
            if (entry.size > MAX_PART_BYTES || entry.size < -1L) throw UnsafeDocxException()
            if (entry.size > 0) declaredTotal += entry.size
            if (declaredTotal > MAX_TOTAL_BYTES) throw UnsafeDocxException()
        }
        if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry(DOCUMENT_PART) == null) throw InvalidDocxException()
        validateContentTypes(zip)
    }

    private fun validateContentTypes(zip: ZipFile) {
        val entry = zip.getEntry("[Content_Types].xml") ?: throw InvalidDocxException()
        var valid = false
        zip.getInputStream(entry).use { stream ->
            val xml = parser(stream)
            while (xml.next() != XmlPullParser.END_DOCUMENT) if (xml.eventType == XmlPullParser.START_TAG && xml.name == "Override") {
                if (xml.attr("PartName") == "/word/document.xml" && xml.attr("ContentType") == DOCX_MAIN_CONTENT_TYPE) valid = true
            }
        }
        if (!valid) throw InvalidDocxException()
    }

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
        setInput(LimitedInputStream(input, MAX_XML_BYTES), "UTF-8")
    }

    private suspend fun parseStyles(zip: ZipFile): Map<String, Int> {
        val entry = zip.getEntry("word/styles.xml") ?: return emptyMap()
        val headings = mutableMapOf<String, Int>()
        zip.getInputStream(entry).use { stream ->
            val xml = parser(stream); var styleId: String? = null; var heading = 0
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                coroutineContext.ensureActive()
                if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                    "style" -> { styleId = xml.attr("styleId"); heading = 0 }
                    "name" -> heading = headingLevel(xml.attr("val"))
                    "outlineLvl" -> heading = (xml.attr("val")?.toIntOrNull()?.plus(1) ?: heading).coerceIn(1, 6)
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == "style") {
                    if (styleId != null && heading > 0) headings[styleId!!] = heading
                    styleId = null
                }
            }
        }
        return headings
    }

    private data class Numbering(val numToAbstract: Map<Int, Int>, val formats: Map<Pair<Int, Int>, String>)
    private suspend fun parseNumbering(zip: ZipFile): Numbering {
        val entry = zip.getEntry("word/numbering.xml") ?: return Numbering(emptyMap(), emptyMap())
        val links = mutableMapOf<Int, Int>(); val formats = mutableMapOf<Pair<Int, Int>, String>()
        zip.getInputStream(entry).use { stream ->
            val xml = parser(stream); var abstractId: Int? = null; var numId: Int? = null; var level = 0
            while (xml.next() != XmlPullParser.END_DOCUMENT) { coroutineContext.ensureActive(); if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                "abstractNum" -> abstractId = xml.attr("abstractNumId")?.toIntOrNull()
                "lvl" -> level = xml.attr("ilvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
                "numFmt" -> abstractId?.let { formats[it to level] = xml.attr("val").orEmpty() }
                "num" -> numId = xml.attr("numId")?.toIntOrNull()
                "abstractNumId" -> if (numId != null) xml.attr("val")?.toIntOrNull()?.let { links[numId!!] = it }
            } }
        }
        return Numbering(links, formats)
    }

    private data class Relationship(val target: String, val external: Boolean)
    private suspend fun parseRelationships(zip: ZipFile): Map<String, Relationship> {
        val entry = zip.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
        val result = mutableMapOf<String, Relationship>()
        zip.getInputStream(entry).use { stream ->
            val xml = parser(stream)
            while (xml.next() != XmlPullParser.END_DOCUMENT) { coroutineContext.ensureActive(); if (xml.eventType == XmlPullParser.START_TAG && xml.name == "Relationship") {
                val id = xml.attr("Id") ?: continue
                val target = xml.attr("Target") ?: continue
                result[id] = Relationship(target, xml.attr("TargetMode").equals("External", true))
            } }
        }
        return result
    }

    private suspend fun parseDocument(
        zip: ZipFile, styles: Map<String, Int>, numbering: Numbering, onBatch: suspend (List<WordBlock>) -> Unit
    ): Pair<Int, Set<String>> {
        val batch = ArrayList<WordBlock>(BATCH_SIZE); var blockCount = 0; val referencedImages = linkedSetOf<String>()
        var nextId = 0L; var runCount = 0; var runs = mutableListOf<WordRun>(); var text = StringBuilder(); var inRun = false
        var bold=false;var italic=false;var underline=false;var strike=false;var size:Float?=null;var color:Long?=null;var baseline=WordBaseline.Normal
        var styleId:String?=null;var alignment=WordAlignment.Start;var numId:Int?=null;var level=0;var paragraphIndent=0
        var imageIds=mutableListOf<Pair<String,String?>>();var pendingImageDescription:String?=null
        var tableRows:MutableList<List<String>>?=null;var row:MutableList<String>?=null;var cell:StringBuilder?=null
        val counters = mutableMapOf<Pair<Int,Int>,Int>()
        suspend fun emit(block: WordBlock) {
            if (++blockCount > MAX_BLOCKS) throw UnsafeDocxException()
            if (block is WordBlock.Image) referencedImages += block.relationshipId
            batch += block
            if (batch.size >= BATCH_SIZE) { onBatch(batch.toList()); batch.clear() }
        }
        zip.getInputStream(zip.getEntry(DOCUMENT_PART)).use { stream ->
            val xml = parser(stream)
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                coroutineContext.ensureActive()
                if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                    "tbl" -> tableRows = mutableListOf()
                    "tr" -> if (tableRows != null) row = mutableListOf()
                    "tc" -> if (row != null) cell = StringBuilder()
                    "p" -> { runs=mutableListOf();styleId=null;alignment=WordAlignment.Start;numId=null;level=0;paragraphIndent=0;imageIds=mutableListOf() }
                    "pStyle" -> styleId=xml.attr("val")
                    "jc" -> alignment=when(xml.attr("val")){"center"->WordAlignment.Center;"right","end"->WordAlignment.End;"both","distribute"->WordAlignment.Justify;else->WordAlignment.Start}
                    "numId" -> numId=xml.attr("val")?.toIntOrNull()
                    "ilvl" -> level=xml.attr("val")?.toIntOrNull()?.coerceIn(0,8)?:0
                    "ind" -> paragraphIndent=((xml.attr("left") ?: xml.attr("start"))?.toIntOrNull()?.div(720) ?: 0).coerceIn(0, 8)
                    "r" -> { inRun=true;text=StringBuilder();bold=false;italic=false;underline=false;strike=false;size=null;color=null;baseline=WordBaseline.Normal }
                    "b" -> if(inRun)bold=xml.attr("val")!="0"
                    "i" -> if(inRun)italic=xml.attr("val")!="0"
                    "u" -> if(inRun)underline=xml.attr("val") !in setOf("none","0")
                    "strike","dstrike" -> if(inRun)strike=true
                    "sz" -> if(inRun)size=(xml.attr("val")?.toFloatOrNull()?.div(2f))?.coerceIn(8f,40f)
                    "color" -> if(inRun)color=parseColor(xml.attr("val"))
                    "vertAlign" -> if(inRun)baseline=when(xml.attr("val")){"superscript"->WordBaseline.Superscript;"subscript"->WordBaseline.Subscript;else->WordBaseline.Normal}
                    "t" -> if(inRun)text.append(xml.nextText())
                    "tab" -> if(inRun)text.append('\t')
                    "br","cr" -> if(inRun)text.append('\n')
                    "docPr" -> pendingImageDescription=xml.attr("descr") ?: xml.attr("name")
                    "blip" -> xml.attr("embed")?.let { imageIds += it to pendingImageDescription;pendingImageDescription=null }
                } else if (xml.eventType == XmlPullParser.END_TAG) when (xml.name) {
                    "r" -> { if(++runCount>MAX_RUNS)throw UnsafeDocxException();runs+=WordRun(text.toString(),bold,italic,underline,strike,size,color,baseline);inRun=false }
                    "p" -> {
                        val plain=runs.joinToString(""){it.text}
                        if(cell!=null){if(cell!!.isNotEmpty())cell!!.append('\n');cell!!.append(plain)} else {
                            val heading=styles[styleId]?:headingLevel(styleId)
                            val block=if(heading>0)WordBlock.Heading(nextId++,heading,runs.toList(),alignment) else if(numId!=null){
                                val key=numId!! to level;val abstract=numbering.numToAbstract[numId!!];val format=abstract?.let{numbering.formats[it to level]}.orEmpty()
                                val marker=if(format.contains("bullet",true))"•" else "${counters.merge(key,1,Int::plus)}."
                                WordBlock.ListItem(nextId++,marker,level,runs.toList())
                            }else WordBlock.Paragraph(nextId++,runs.toList(),alignment,paragraphIndent)
                            emit(block);for((id,alt)in imageIds)emit(WordBlock.Image(nextId++,id,alt))
                        }
                    }
                    "tc" -> { row?.add(cell?.toString().orEmpty());cell=null }
                    "tr" -> { if(row!=null)tableRows?.add(row!!.toList());row=null }
                    "tbl" -> { tableRows?.let{emit(WordBlock.Table(nextId++,it.toList()))};tableRows=null }
                }
            }
        }
        if(batch.isNotEmpty())onBatch(batch.toList())
        return blockCount to referencedImages
    }

    private suspend fun decodeImages(zip: ZipFile, relationships: Map<String, Relationship>, ids: Set<String>): Map<String, Bitmap> {
        val result=mutableMapOf<String,Bitmap>();var total=0L
        for(id in ids){coroutineContext.ensureActive();val rel=relationships[id]?:continue;if(rel.external||rel.target.startsWith('/')||rel.target.replace('\\','/').split('/').any{it==".."})continue
            val normalized=("word/"+rel.target).replace("word/../","").replace('\\','/')
            if(!normalized.startsWith("word/media/"))continue
            val entry=zip.getEntry(normalized)?:continue;if(entry.size !in 1..MAX_IMAGE_BYTES)continue
            total+=entry.size;if(total>MAX_IMAGES_TOTAL_BYTES)break
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};zip.getInputStream(entry).use{BitmapFactory.decodeStream(it,null,options)}
            if(options.outWidth<=0||options.outHeight<=0)continue
            var sample=1;while((options.outWidth/sample).toLong()*(options.outHeight/sample).toLong()>MAX_IMAGE_PIXELS||options.outWidth/sample>MAX_IMAGE_DIMENSION||options.outHeight/sample>MAX_IMAGE_DIMENSION)sample*=2
            val decode=BitmapFactory.Options().apply{inSampleSize=sample};zip.getInputStream(entry).use{BitmapFactory.decodeStream(it,null,decode)}?.let{result[id]=it}
        }
        return result
    }

    private fun XmlPullParser.attr(local: String): String? { for(i in 0 until attributeCount)if(getAttributeName(i)==local)return getAttributeValue(i);return null }
    private fun headingLevel(value:String?):Int{val match=Regex("(?i)heading[^0-9]*([1-6])").find(value.orEmpty());return match?.groupValues?.get(1)?.toIntOrNull()?:0}
    private fun parseColor(value:String?):Long?=value?.takeIf{it.length==6&&it.all(Char::isLetterOrDigit)}?.toLongOrNull(16)?.let{0xFF000000L or it}

    companion object {
        const val MAX_ENTRIES=2048;const val MAX_PART_BYTES=32L*1024*1024;const val MAX_XML_BYTES=16L*1024*1024
        const val MAX_TOTAL_BYTES=128L*1024*1024;const val MAX_IMAGE_BYTES=12L*1024*1024;const val MAX_IMAGES_TOTAL_BYTES=32L*1024*1024
        const val MAX_IMAGE_PIXELS=16_000_000;const val MAX_IMAGE_DIMENSION=4096;const val MAX_BLOCKS=50_000;const val MAX_RUNS=200_000
        const val BATCH_SIZE=32;private const val DOCUMENT_PART="word/document.xml"
        private const val DOCX_MAIN_CONTENT_TYPE="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    }
}

private class LimitedInputStream(input:InputStream,private val limit:Long):FilterInputStream(input){private var count=0L
    override fun read():Int{val value=super.read();if(value>=0)check(1);return value}
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int{val read=super.read(buffer,offset,length);if(read>0)check(read.toLong());return read}
    private fun check(amount:Long){count+=amount;if(count>limit)throw UnsafeDocxException()}
}
