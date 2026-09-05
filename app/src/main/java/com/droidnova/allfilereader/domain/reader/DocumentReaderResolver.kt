package com.droidnova.allfilereader.domain.reader

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile

/** The single routing decision used by every document-list entry point. */
enum class DocumentReaderDestination { Pdf, PlainText, Docx, Spreadsheet, PowerPoint }

/** Exhaustive, presentation-independent result of attempting to route a discovered file. */
sealed interface DocumentOpenResult {
    data class Internal(val destination: DocumentReaderDestination) : DocumentOpenResult
    data object LegacyPowerPoint : DocumentOpenResult
    data object Unsupported : DocumentOpenResult
    data object AccessFailure : DocumentOpenResult
    data object FormatMismatch : DocumentOpenResult
}

object DocumentReaderResolver {
    fun resolve(document: DocumentFile): DocumentOpenResult {
        val extension = document.extension?.lowercase(java.util.Locale.ROOT)
        return when (document.category) {
        DocumentCategory.Pdf -> DocumentOpenResult.Internal(DocumentReaderDestination.Pdf)
        DocumentCategory.Text -> DocumentOpenResult.Internal(DocumentReaderDestination.PlainText)
        DocumentCategory.Word -> if (extension == "docx")
            DocumentOpenResult.Internal(DocumentReaderDestination.Docx) else DocumentOpenResult.Unsupported
        DocumentCategory.Excel -> DocumentOpenResult.Internal(DocumentReaderDestination.Spreadsheet)
        DocumentCategory.PowerPoint -> when (extension) {
            "ppt" -> DocumentOpenResult.LegacyPowerPoint
            "pptx" -> DocumentOpenResult.Internal(DocumentReaderDestination.PowerPoint)
            else -> DocumentOpenResult.Unsupported
        }
        DocumentCategory.Folder, DocumentCategory.Other -> DocumentOpenResult.Unsupported
        }
    }
}
