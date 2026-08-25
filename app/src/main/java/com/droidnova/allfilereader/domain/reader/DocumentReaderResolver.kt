package com.droidnova.allfilereader.domain.reader

import com.droidnova.allfilereader.domain.model.DocumentCategory
import com.droidnova.allfilereader.domain.model.DocumentFile

/** The single routing decision used by every document-list entry point. */
enum class DocumentReaderDestination { Pdf, PlainText, Docx, LegacyWord, Spreadsheet, FutureOffice, Unsupported }

object DocumentReaderResolver {
    fun resolve(document: DocumentFile): DocumentReaderDestination = when (document.category) {
        DocumentCategory.Pdf -> DocumentReaderDestination.Pdf
        DocumentCategory.Text -> DocumentReaderDestination.PlainText
        DocumentCategory.Word -> if (document.extension.equals("docx", ignoreCase = true))
            DocumentReaderDestination.Docx else DocumentReaderDestination.LegacyWord
        DocumentCategory.Excel -> DocumentReaderDestination.Spreadsheet
        DocumentCategory.PowerPoint -> DocumentReaderDestination.FutureOffice
        DocumentCategory.Folder, DocumentCategory.Other -> DocumentReaderDestination.Unsupported
    }
}
