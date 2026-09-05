package com.droidnova.allfilereader.domain.model

import java.util.Locale

object DocumentClassifier {
    /** Single product policy for metadata that may be exposed by document UIs. */
    fun isVisibleDocument(category: DocumentCategory): Boolean = category == DocumentCategory.Pdf ||
        category == DocumentCategory.Word || category == DocumentCategory.Excel ||
        category == DocumentCategory.PowerPoint || category == DocumentCategory.Text

    fun isVisibleDocument(document: DocumentFile): Boolean = isVisibleDocument(
        classify(document.mimeType, document.extension ?: extensionOf(document.displayName))
    )
    /** Formats intentionally exposed to external ACTION_VIEW callers. */
    val incomingMimeTypes: Set<String> = setOf(
        "application/pdf", "text/plain",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel", "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.ms-excel.sheet.binary.macroenabled.12",
        "application/vnd.oasis.opendocument.spreadsheet", "text/csv",
        "text/tab-separated-values",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    val incomingExtensions: Set<String> = setOf(
        "pdf", "txt", "docx", "xlsx", "xls", "xlsm", "xlsb", "ods", "csv", "tsv", "pptx"
    )

    fun extensionOf(displayName: String): String? = displayName
        .substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf(String::isNotEmpty)

    fun classify(mimeType: String?, extension: String?): DocumentCategory {
        val normalizedMimeType = mimeType?.lowercase(Locale.ROOT)
        val normalizedExtension = extension?.lowercase(Locale.ROOT)

        return when {
            normalizedMimeType == "application/pdf" || normalizedExtension == "pdf" ->
                DocumentCategory.Pdf
            normalizedMimeType in wordMimeTypes || normalizedExtension in setOf("doc", "docx") ->
                DocumentCategory.Word
            normalizedMimeType in excelMimeTypes || normalizedExtension in spreadsheetExtensions ->
                DocumentCategory.Excel
            normalizedMimeType in powerPointMimeTypes || normalizedExtension in setOf("ppt", "pptx", "pptm", "pps", "ppsx") ->
                DocumentCategory.PowerPoint
            normalizedMimeType == "text/plain" || normalizedExtension == "txt" -> DocumentCategory.Text
            else -> DocumentCategory.Other
        }
    }

    private val wordMimeTypes = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    private val spreadsheetExtensions = setOf("xls", "xlsx", "xlsm", "xlsb", "ods", "csv", "tsv")
    private val excelMimeTypes = setOf(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.ms-excel.sheet.binary.macroenabled.12",
        "application/vnd.oasis.opendocument.spreadsheet",
        "text/csv", "text/tab-separated-values"
    )
    private val powerPointMimeTypes = setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
    )
}
