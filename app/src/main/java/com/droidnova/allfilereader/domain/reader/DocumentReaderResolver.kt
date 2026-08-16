package com.droidnova.allfilereader.domain.reader

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile

/** The single routing decision used by every document-list entry point. */
enum class DocumentReaderDestination { Pdf, PlainText, FutureOffice, Unsupported }

object DocumentReaderResolver {
    fun resolve(document: DocumentFile): DocumentReaderDestination = when (document.category) {
        DocumentCategory.Pdf -> DocumentReaderDestination.Pdf
        DocumentCategory.Text -> DocumentReaderDestination.PlainText
        DocumentCategory.Word, DocumentCategory.Excel, DocumentCategory.PowerPoint ->
            DocumentReaderDestination.FutureOffice
        DocumentCategory.Folder, DocumentCategory.Other -> DocumentReaderDestination.Unsupported
    }
}
