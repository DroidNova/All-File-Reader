package com.droidnova.allfilereader.domain.model

import java.util.Locale

object DocumentClassifier {
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
            normalizedMimeType in excelMimeTypes || normalizedExtension in setOf("xls", "xlsx") ->
                DocumentCategory.Excel
            normalizedMimeType in powerPointMimeTypes || normalizedExtension in setOf("ppt", "pptx") ->
                DocumentCategory.PowerPoint
            normalizedMimeType?.startsWith("text/") == true ||
                normalizedExtension in textExtensions -> DocumentCategory.Text
            normalizedMimeType?.startsWith("image/") == true ||
                normalizedExtension in imageExtensions -> DocumentCategory.Image
            else -> DocumentCategory.Other
        }
    }

    private val wordMimeTypes = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    private val excelMimeTypes = setOf(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    private val powerPointMimeTypes = setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )
    private val textExtensions = setOf("txt", "csv", "log", "md", "json", "xml", "rtf")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
}
