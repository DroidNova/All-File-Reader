package com.droidnova.allfilereader.data.pdf

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

enum class PdfSourceStrategy { DirectDescriptor, PrivateCopy }
sealed class PdfPreparationException:Exception(){class Empty:PdfPreparationException();class Invalid:PdfPreparationException();class InsufficientStorage:PdfPreparationException()}
data class PreparedPdfSource(val uri:Uri,val strategy:PdfSourceStrategy,val ownedFile:File?=null,val actualBytes:Long?=null)

/** Disk-only policy for non-seekable providers; AndroidX PDF continues to own all page memory. */
object PdfCopyPolicy {
    const val RESERVED_FREE_BYTES=128L*1024*1024
    const val ABSOLUTE_COPY_CAP_BYTES=2L*1024*1024*1024
    const val PREFIX_BYTES=1_024
    fun copyLimit(usableBytes:Long):Long = when {
        usableBytes<=RESERVED_FREE_BYTES -> 0
        else -> minOf(ABSOLUTE_COPY_CAP_BYTES,usableBytes-RESERVED_FREE_BYTES)
    }
    fun canCopy(declaredBytes:Long?,limit:Long)=limit>0&&(declaredBytes==null||declaredBytes<0||declaredBytes<=limit)
}

internal object PdfSessionFiles {
    const val STALE_AFTER_MS=24L*60*60*1000
    fun deleteOwned(file:File?,directory:File)=runCatching{file!=null&&file.isFile&&file.canonicalFile.parentFile==directory.canonicalFile&&file.delete()}.getOrDefault(false)
    fun cleanStale(directory:File,now:Long,active:File?=null)=directory.listFiles().orEmpty().count{it!=active&&it.isFile&&now-it.lastModified()>STALE_AFTER_MS&&deleteOwned(it,directory)}
}

class PdfSourcePreparer(private val resolver:ContentResolver,private val sessionDirectory:File){
    suspend fun prepare(uri:Uri,declaredSize:Long?):PreparedPdfSource {
        require(uri.scheme==ContentResolver.SCHEME_CONTENT||uri.scheme==ContentResolver.SCHEME_FILE)
        if(uri.scheme==ContentResolver.SCHEME_FILE){validatePrefix(resolver.openInputStream(uri)?:throw FileNotFoundException());return PreparedPdfSource(uri,PdfSourceStrategy.DirectDescriptor)}
        if(isSeekable(uri)){validatePrefix(resolver.openInputStream(uri)?:throw FileNotFoundException());return PreparedPdfSource(uri,PdfSourceStrategy.DirectDescriptor)}
        val limit=PdfCopyPolicy.copyLimit(sessionDirectory.usableSpace);if(!PdfCopyPolicy.canCopy(declaredSize,limit))throw PdfPreparationException.InsufficientStorage()
        val target=File.createTempFile("pdf_session_",".pdf",sessionDirectory)
        try{val input=resolver.openInputStream(uri)?:throw FileNotFoundException();val copied=copyPdfStream(input,target,limit);validatePrefix(target.inputStream());return PreparedPdfSource(Uri.fromFile(target),PdfSourceStrategy.PrivateCopy,target,copied)}catch(c:CancellationException){target.delete();throw c}catch(e:Exception){target.delete();throw e}
    }
    private fun isSeekable(uri:Uri):Boolean=try{resolver.openFileDescriptor(uri,"r")?.use{Os.lseek(it.fileDescriptor,0,OsConstants.SEEK_CUR);true}?:false}catch(_:Exception){false}
}

internal fun validatePrefix(input:InputStream){input.use{source->val prefix=ByteArray(PdfCopyPolicy.PREFIX_BYTES);val count=source.read(prefix);if(count<=0)throw PdfPreparationException.Empty();val start=(0 until minOf(count,PdfCopyPolicy.PREFIX_BYTES-4)).firstOrNull{i->prefix[i]=='%'.code.toByte()&&prefix[i+1]=='P'.code.toByte()&&prefix[i+2]=='D'.code.toByte()&&prefix[i+3]=='F'.code.toByte()&&prefix[i+4]=='-'.code.toByte()};if(start==null)throw PdfPreparationException.Invalid()}}
internal suspend fun copyPdfStream(input:InputStream,destination:File,limit:Long):Long{var complete=false;try{val copied=input.buffered().use{source->destination.outputStream().buffered().use{output->val buffer=ByteArray(DEFAULT_BUFFER_SIZE);var total=0L;while(true){coroutineContext.ensureActive();val count=source.read(buffer);if(count<0)break;total=try{Math.addExact(total,count.toLong())}catch(_:ArithmeticException){throw PdfPreparationException.InsufficientStorage()};if(total>limit)throw PdfPreparationException.InsufficientStorage();output.write(buffer,0,count)};output.flush();total}};complete=true;return copied}finally{if(!complete)destination.delete()}}
