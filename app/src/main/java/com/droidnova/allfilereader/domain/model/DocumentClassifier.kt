package com.droidnova.allfilereader.domain.model

import java.util.Locale

enum class OpenCapability { Internal, ExternalFallback, Unsupported }

data class DocumentFormat(
    val id: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
    val category: DocumentCategory,
    val openCapability: OpenCapability,
    val advertiseForExternalOpen: Boolean
)

sealed interface DocumentClassification {
    val category: DocumentCategory?

    data class Recognized(val format: DocumentFormat) : DocumentClassification {
        override val category: DocumentCategory = format.category
    }

    data class UnsupportedDocument(val format: DocumentFormat) : DocumentClassification {
        override val category: DocumentCategory = format.category
    }

    data object Unknown : DocumentClassification {
        override val category: DocumentCategory? = null
    }
}

/**
 * Authoritative, side-effect-free document policy. Readers remain responsible for validating file
 * signatures and package contents. An explicit recognized extension always wins over MIME metadata.
 */
object DocumentClassifier {
    private fun format(
        id: String,
        extension: String,
        mime: String?,
        category: DocumentCategory,
        capability: OpenCapability,
        advertise: Boolean = capability == OpenCapability.Internal
    ) = DocumentFormat(id, setOf(extension), setOfNotNull(mime), category, capability, advertise)

    val formats: List<DocumentFormat> = listOf(
        format("pdf", "pdf", "application/pdf", DocumentCategory.Pdf, OpenCapability.Internal),
        format("docx", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocumentCategory.Word, OpenCapability.Internal),
        format("doc", "doc", "application/msword", DocumentCategory.Word, OpenCapability.Unsupported, false),
        format("docm", "docm", "application/vnd.ms-word.document.macroenabled.12", DocumentCategory.Word, OpenCapability.Unsupported, false),
        format("xlsx", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", DocumentCategory.Excel, OpenCapability.Internal),
        format("xls", "xls", "application/vnd.ms-excel", DocumentCategory.Excel, OpenCapability.Internal),
        format("xlsm", "xlsm", "application/vnd.ms-excel.sheet.macroenabled.12", DocumentCategory.Excel, OpenCapability.Unsupported, false),
        format("xlsb", "xlsb", "application/vnd.ms-excel.sheet.binary.macroenabled.12", DocumentCategory.Excel, OpenCapability.Unsupported, false),
        // These existing spreadsheet-reader inputs remain available in-app, but are deliberately
        // not advertised as Android document handlers by this product's external-open policy.
        format("ods", "ods", "application/vnd.oasis.opendocument.spreadsheet", DocumentCategory.Excel, OpenCapability.Internal, false),
        format("csv", "csv", "text/csv", DocumentCategory.Excel, OpenCapability.Internal, false),
        format("tsv", "tsv", "text/tab-separated-values", DocumentCategory.Excel, OpenCapability.Internal, false),
        format("pptx", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", DocumentCategory.PowerPoint, OpenCapability.Internal),
        format("ppt", "ppt", "application/vnd.ms-powerpoint", DocumentCategory.PowerPoint, OpenCapability.ExternalFallback, false),
        format("pptm", "pptm", "application/vnd.ms-powerpoint.presentation.macroenabled.12", DocumentCategory.PowerPoint, OpenCapability.Unsupported, false),
        format("pps", "pps", "application/vnd.ms-powerpoint.slideshow.macroenabled.12", DocumentCategory.PowerPoint, OpenCapability.Unsupported, false),
        format("ppsx", "ppsx", "application/vnd.openxmlformats-officedocument.presentationml.slideshow", DocumentCategory.PowerPoint, OpenCapability.Unsupported, false),
        format("txt", "txt", "text/plain", DocumentCategory.Text, OpenCapability.Internal)
    )

    private val byExtension = formats.flatMap { f -> f.extensions.map { it to f } }.toMap()
    private val byMime = formats.flatMap { f -> f.mimeTypes.map { it to f } }.toMap()

    val advertisedMimeTypes: Set<String> = formats.filter(DocumentFormat::advertiseForExternalOpen).flatMapTo(linkedSetOf()) { it.mimeTypes }

    fun normalizedExtension(displayName: String?): String? {
        val name = displayName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun normalizeMimeType(mimeType: String?): String? {
        val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
        return normalized?.takeUnless { it == "application/octet-stream" || it == "*/*" || it.endsWith("/*") }
    }

    fun findByExtension(extension: String?): DocumentFormat? = extension?.trim()?.removePrefix(".")
        ?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)?.let(byExtension::get)

    fun findByMimeType(mimeType: String?): DocumentFormat? = normalizeMimeType(mimeType)?.let(byMime::get)

    fun classify(displayName: String?, mimeType: String?): DocumentClassification {
        val format = findByExtension(normalizedExtension(displayName)) ?: findByMimeType(mimeType)
            ?: return DocumentClassification.Unknown
        return if (format.openCapability == OpenCapability.Unsupported) {
            DocumentClassification.UnsupportedDocument(format)
        } else DocumentClassification.Recognized(format)
    }

    /** Compatibility boundary for models which already persist a separately extracted extension. */
    fun classifyMetadata(mimeType: String?, extension: String?): DocumentClassification {
        val format = findByExtension(extension) ?: findByMimeType(mimeType) ?: return DocumentClassification.Unknown
        return if (format.openCapability == OpenCapability.Unsupported) DocumentClassification.UnsupportedDocument(format)
        else DocumentClassification.Recognized(format)
    }

    fun extensionOf(displayName: String?): String? = normalizedExtension(displayName)
    fun isVisibleDocument(classification: DocumentClassification): Boolean = classification !== DocumentClassification.Unknown
    fun isVisibleDocument(category: DocumentCategory): Boolean = category in visibleCategories
    fun isVisibleDocument(document: DocumentFile): Boolean = isVisibleDocument(classificationOf(document))
    fun classificationOf(document: DocumentFile): DocumentClassification = classifyMetadata(
        document.mimeType,
        document.extension ?: normalizedExtension(document.displayName)
    )

    private val visibleCategories = setOf(
        DocumentCategory.Pdf, DocumentCategory.Word, DocumentCategory.Excel,
        DocumentCategory.PowerPoint, DocumentCategory.Text
    )
}
