package com.droidnova.allfilereader.domain.reader

import com.droidnova.allfilereader.domain.model.DocumentClassification
import com.droidnova.allfilereader.domain.model.DocumentClassifier
import com.droidnova.allfilereader.domain.model.DocumentFile
import com.droidnova.allfilereader.domain.model.OpenCapability

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
    fun resolve(document: DocumentFile): DocumentOpenResult = resolve(DocumentClassifier.classificationOf(document))

    fun resolve(classification: DocumentClassification?): DocumentOpenResult {
        val format = when (classification) {
            is DocumentClassification.Recognized -> classification.format
            is DocumentClassification.UnsupportedDocument -> return DocumentOpenResult.Unsupported
            DocumentClassification.Unknown, null -> return DocumentOpenResult.Unsupported
        }
        return when (format.openCapability) {
            OpenCapability.ExternalFallback -> DocumentOpenResult.LegacyPowerPoint
            OpenCapability.Unsupported -> DocumentOpenResult.Unsupported
            OpenCapability.Internal -> when (format.id) {
                "pdf" -> DocumentOpenResult.Internal(DocumentReaderDestination.Pdf)
                "txt" -> DocumentOpenResult.Internal(DocumentReaderDestination.PlainText)
                "docx" -> DocumentOpenResult.Internal(DocumentReaderDestination.Docx)
                "xls", "xlsx", "ods", "csv", "tsv" -> DocumentOpenResult.Internal(DocumentReaderDestination.Spreadsheet)
                "pptx" -> DocumentOpenResult.Internal(DocumentReaderDestination.PowerPoint)
                else -> DocumentOpenResult.Unsupported
            }
        }
    }
}
