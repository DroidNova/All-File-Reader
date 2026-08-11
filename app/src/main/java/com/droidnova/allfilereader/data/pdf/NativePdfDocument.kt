package com.droidnova.allfilereader.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.droidnova.allfilereader.domain.model.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the native PDF resources for one document and serializes page rendering. */
class NativePdfDocument @Inject constructor(
    @ApplicationContext private val context: Context
) : AutoCloseable {
    private val resourceLock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    suspend fun open(document: DocumentFile): Int = withContext(Dispatchers.IO) {
        synchronized(resourceLock) {
            closeLocked()
            val uri = Uri.parse(document.uri)
            val openedDescriptor = if (uri.scheme == "file") {
                ParcelFileDescriptor.open(File(URI(document.uri)), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            } ?: throw PdfFileNotFoundException()
            try {
                val openedRenderer = PdfRenderer(openedDescriptor)
                if (openedRenderer.pageCount == 0) {
                    openedRenderer.close()
                    openedDescriptor.close()
                    throw EmptyPdfException()
                }
                descriptor = openedDescriptor
                renderer = openedRenderer
                openedRenderer.pageCount
            } catch (exception: Exception) {
                runCatching { openedDescriptor.close() }
                throw exception
            }
        }
    }

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        synchronized(resourceLock) {
            val activeRenderer = renderer ?: throw PdfNotOpenException()
            val page = activeRenderer.openPage(index)
            try {
                val width = targetWidth.coerceIn(MIN_RENDER_WIDTH, MAX_RENDER_DIMENSION)
                val height = (width.toFloat() * page.height / page.width)
                    .toInt().coerceIn(1, MAX_RENDER_DIMENSION)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        synchronized(resourceLock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
    }

    private companion object {
        const val MIN_RENDER_WIDTH = 320
        const val MAX_RENDER_DIMENSION = 4096
    }
}

class PdfFileNotFoundException : Exception()
class EmptyPdfException : Exception()
class PdfNotOpenException : Exception()
