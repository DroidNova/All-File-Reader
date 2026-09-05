package com.droidnova.allfilereader.data.pdf
import java.io.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class PdfSourcePreparerTest{
 @Test fun copyPolicyReservesSpaceAndHasAbsoluteCap(){assertEquals(0,PdfCopyPolicy.copyLimit(PdfCopyPolicy.RESERVED_FREE_BYTES));assertEquals(1,PdfCopyPolicy.copyLimit(PdfCopyPolicy.RESERVED_FREE_BYTES+1));assertEquals(PdfCopyPolicy.ABSOLUTE_COPY_CAP_BYTES,PdfCopyPolicy.copyLimit(Long.MAX_VALUE));assertTrue(PdfCopyPolicy.canCopy(-1,100));assertFalse(PdfCopyPolicy.canCopy(101,100))}
 @Test fun preflightAcceptsLeadingBytesIncrementalAndTrailingData(){for(bytes in listOf("%PDF-1.7\nbody".toByteArray(),ByteArray(30)+"%PDF-1.4\n%%EOF trailing".toByteArray()))validatePrefix(ByteArrayInputStream(bytes))}
 @Test fun preflightRejectsEmptyShortAndNonPdf(){for(bytes in listOf(byteArrayOf(),"%PD".toByteArray(),"not pdf".toByteArray()))assertTrue(runCatching{validatePrefix(ByteArrayInputStream(bytes))}.isFailure)}
 @Test fun preflightReadsOnlyBoundedPrefix(){var read=0;val input=object:InputStream(){override fun read():Int{read++;return if(read<6)"%PDF-"[read-1].code else 'x'.code}};validatePrefix(input);assertTrue(read<=PdfCopyPolicy.PREFIX_BYTES+1)}
 @Test fun streamCopyCountsActualBytesAndCleansFailures()=runBlocking{val data=ByteArray(32769);val ok=temp();try{assertEquals(data.size.toLong(),copyPdfStream(ByteArrayInputStream(data),ok,data.size.toLong()))}finally{ok.delete()};val over=temp();assertTrue(runCatching{copyPdfStream(ByteArrayInputStream(ByteArray(11)),over,10)}.exceptionOrNull() is PdfPreparationException.InsufficientStorage);assertFalse(over.exists());val cancelled=temp();assertTrue(runCatching{copyPdfStream(object:InputStream(){override fun read():Int=throw CancellationException()},cancelled,10)}.exceptionOrNull() is CancellationException);assertFalse(cancelled.exists())}
 @Test fun sessionCleanupIsContainedAndPreservesActive(){val root=createTempDir(prefix="pdf_sessions_");val outside=File.createTempFile("outside_",".pdf");val stale=File(root,"stale.pdf").apply{writeText("x");setLastModified(1)};val active=File(root,"active.pdf").apply{writeText("x");setLastModified(1)};try{assertEquals(1,PdfSessionFiles.cleanStale(root,PdfSessionFiles.STALE_AFTER_MS+2,active));assertFalse(stale.exists());assertTrue(active.exists());assertFalse(PdfSessionFiles.deleteOwned(outside,root))}finally{root.deleteRecursively();outside.delete()}}
 private fun temp()=File.createTempFile("pdf_copy_",".pdf").apply{delete()}
}
