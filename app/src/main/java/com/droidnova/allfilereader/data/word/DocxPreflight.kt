package com.droidnova.allfilereader.data.word

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal enum class DocxFailureReason { INVALID, MISSING_PARTS, ENCRYPTED, TOO_LARGE, TOO_COMPLEX, UNSAFE_ARCHIVE }
internal sealed class DocxPreflightException(val reason:DocxFailureReason):Exception(){
 class Invalid:DocxPreflightException(DocxFailureReason.INVALID);class MissingParts:DocxPreflightException(DocxFailureReason.MISSING_PARTS)
 class Unsupported:DocxPreflightException(DocxFailureReason.ENCRYPTED);class TooLarge:DocxPreflightException(DocxFailureReason.TOO_LARGE)
 class TooComplex:DocxPreflightException(DocxFailureReason.TOO_COMPLEX);class Unsafe:DocxPreflightException(DocxFailureReason.UNSAFE_ARCHIVE)
}
internal data class DocxSession(val file:File,val actualBytes:Long,val entryCount:Int,val totalUncompressedBytes:Long)

internal class DocxPreflight(private val resolver:ContentResolver?,private val sessionDirectory:File){
 suspend fun copyAndValidate(uri:Uri,declaredSize:Long?,budget:DocxRenderBudget):DocxSession{
  if(declaredSize!=null&&declaredSize>=0&&declaredSize>budget.maxCompressedBytes)throw DocxPreflightException.TooLarge()
  val target=File.createTempFile("docx_session_",".docx",sessionDirectory)
  try{val actual=resolver?.openInputStream(uri)?.use{copyDocxStream(it,target,budget.maxCompressedBytes)}?:throw FileNotFoundException();if(actual==0L)throw DocxPreflightException.Invalid();val inspection=inspectZip(target,budget);return DocxSession(target,actual,inspection.first,inspection.second)}catch(c:CancellationException){target.delete();throw c}catch(e:Exception){target.delete();throw e}
 }
 internal suspend fun validateSessionFile(file:File,budget:DocxRenderBudget)=inspectZip(file,budget)
 private suspend fun inspectZip(file:File,budget:DocxRenderBudget):Pair<Int,Long>{
  try{ZipFile(file).use{zip->
   if(zip.size() !in 1..budget.maxEntryCount)throw DocxPreflightException.Unsafe()
   val names=HashSet<String>();var total=0L;var documentXml:ByteArray?=null;var contentTypes:ByteArray?=null;val entries=zip.entries();var count=0
   while(entries.hasMoreElements()){coroutineContext.ensureActive();val entry=entries.nextElement();count++;val name=normalize(entry.name)?:throw DocxPreflightException.Unsafe();if(!names.add(name))throw DocxPreflightException.Unsafe();if(name.equals("EncryptionInfo",true)||name.equals("EncryptedPackage",true))throw DocxPreflightException.Unsupported();val limit=when{ name=="word/document.xml"||name.endsWith(".xml")||name.endsWith(".rels")->budget.maxXmlPartBytes;name.startsWith("word/media/")->budget.maxMediaEntryBytes;else->budget.maxEntryBytes};if(entry.size>limit)throw DocxPreflightException.Unsafe();if(entry.size>0&&entry.compressedSize==0L||entry.size>0&&entry.compressedSize>0&&entry.size/entry.compressedSize>budget.maxCompressionRatio)throw DocxPreflightException.Unsafe();val capture=if(name=="word/document.xml"||name=="[Content_Types].xml")ByteArrayOutputStream()else null;zip.getInputStream(entry).use{stream->val buffer=ByteArray(DEFAULT_BUFFER_SIZE);var readEntry=0L;while(true){coroutineContext.ensureActive();val n=stream.read(buffer);if(n<0)break;readEntry=checkedAdd(readEntry,n.toLong());total=checkedAdd(total,n.toLong());if(readEntry>limit||total>budget.maxTotalUncompressedBytes)throw DocxPreflightException.Unsafe();capture?.write(buffer,0,n)}};if(capture!=null){if(name=="word/document.xml")documentXml=capture.toByteArray() else contentTypes=capture.toByteArray()}
   }
   if("[Content_Types].xml" !in names||"_rels/.rels" !in names||"word/document.xml" !in names)throw DocxPreflightException.MissingParts()
   val types=contentTypes?.toString(Charsets.UTF_8)?:throw DocxPreflightException.MissingParts();if(types.contains("macroEnabled",true)||types.contains("vbaProject",true))throw DocxPreflightException.Unsupported();inspectComplexity(documentXml?:throw DocxPreflightException.MissingParts(),budget)
   return count to total
  }}catch(e:DocxPreflightException){throw e}catch(c:CancellationException){throw c}catch(_:ZipException){throw DocxPreflightException.Invalid()}catch(_:java.io.IOException){throw DocxPreflightException.Invalid()}catch(_:ArithmeticException){throw DocxPreflightException.Unsafe()}
 }
 private fun inspectComplexity(xml:ByteArray,budget:DocxRenderBudget){
  val text=xml.toString(Charsets.UTF_8);if(text.contains("<!DOCTYPE",true)||text.contains("<!ENTITY",true))throw DocxPreflightException.Unsafe()
  fun count(local:String,limit:Int){val pattern=Regex("<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?$local(?=[\\s/>])");var total=0;for(match in pattern.findAll(text)){if(++total>limit)throw DocxPreflightException.TooComplex()}}
  count("p",budget.maxParagraphCount);count("tc",budget.maxTableCellCount);count("(?:drawing|pict|blip)",budget.maxDrawingReferenceCount)
 }
 private fun normalize(raw:String):String?{val value=raw.replace('\\','/');if(value.isBlank()||value.startsWith('/')||Regex("^[A-Za-z]:/").containsMatchIn(value))return null;val parts=ArrayDeque<String>();for(part in value.split('/'))when(part){"","."->Unit;".."->if(parts.isEmpty())return null else parts.removeLast();else->parts.addLast(part)};val normalized=parts.joinToString("/");return normalized.takeIf{it==value}}
 private fun checkedAdd(current:Long,added:Long)=Math.addExact(current,added)
}

internal suspend fun copyDocxStream(input:InputStream,destination:File,limit:Long):Long{var complete=false;try{val total=input.buffered().use{source->destination.outputStream().buffered().use{output->val buffer=ByteArray(DEFAULT_BUFFER_SIZE);var copied=0L;while(true){coroutineContext.ensureActive();val n=source.read(buffer);if(n<0)break;copied=try{Math.addExact(copied,n.toLong())}catch(_:ArithmeticException){throw DocxPreflightException.TooLarge()};if(copied>limit)throw DocxPreflightException.TooLarge();output.write(buffer,0,n)};output.flush();copied}};complete=true;return total}finally{if(!complete)destination.delete()}}
