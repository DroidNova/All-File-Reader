package com.droidnova.allfilereader.domain.model

import com.droidnova.allfilereader.domain.reader.DocumentOpenResult
import com.droidnova.allfilereader.domain.reader.DocumentReaderDestination
import com.droidnova.allfilereader.domain.reader.DocumentReaderResolver
import org.junit.Assert.*
import org.junit.Test

class DocumentFormatRegistryTest {
    @Test fun extensionNormalizationIsSafeAndLocaleIndependent() {
        assertEquals("pdf", DocumentClassifier.normalizedExtension("report.pdf"))
        assertEquals("pdf", DocumentClassifier.normalizedExtension("REPORT.PDF"))
        assertEquals("docx", DocumentClassifier.normalizedExtension(" report.final.DoCx "))
        assertEquals("txt", DocumentClassifier.normalizedExtension("資料.TXT"))
        listOf(null, "", "   ", "README", "file.", ".config").forEach {
            assertNull(it, DocumentClassifier.normalizedExtension(it))
        }
        assertEquals("pdf", DocumentClassifier.normalizedExtension("a".repeat(4096) + ".PDF"))
    }

    @Test fun mimeNormalizationAcceptsOnlySpecificExactTypes() {
        assertEquals("application/pdf", DocumentClassifier.normalizeMimeType(" APPLICATION/PDF ; charset=utf-8 "))
        listOf(null, "", " ", "application/octet-stream", "application/*", "*/*").forEach {
            assertNull(it, DocumentClassifier.normalizeMimeType(it))
        }
        listOf("image/jpeg", "audio/mpeg", "video/mp4").forEach {
            assertNull(DocumentClassifier.findByMimeType(it))
        }
    }

    @Test fun classificationUsesExtensionBeforeMimeAndHidesUnrelatedFiles() {
        assertEquals("docx", formatId("report.DOCX", "application/octet-stream"))
        assertEquals("xlsx", formatId("sheet.xlsx", null))
        assertEquals("pptx", formatId("presentation", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals("txt", formatId(".config", "text/plain"))
        assertEquals("pdf", formatId("fake.pdf", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        listOf("photo.jpg", "movie.mp4", "app.apk", "archive.zip").forEach {
            assertSame(DocumentClassification.Unknown, DocumentClassifier.classify(it, null))
        }
        assertSame(DocumentClassification.Unknown, DocumentClassifier.classify(null, "application/octet-stream"))
    }

    @Test fun capabilitiesAndRoutingMatchProductPolicy() {
        val internal = mapOf(
            "pdf" to DocumentReaderDestination.Pdf, "docx" to DocumentReaderDestination.Docx,
            "xls" to DocumentReaderDestination.Spreadsheet, "xlsx" to DocumentReaderDestination.Spreadsheet,
            "pptx" to DocumentReaderDestination.PowerPoint, "txt" to DocumentReaderDestination.PlainText
        )
        internal.forEach { (extension, destination) ->
            assertEquals(DocumentOpenResult.Internal(destination), DocumentReaderResolver.resolve(DocumentClassifier.classify("x.$extension", null)))
            assertTrue(DocumentClassifier.findByExtension(extension)!!.advertiseForExternalOpen)
        }
        assertEquals(DocumentOpenResult.Unsupported, DocumentReaderResolver.resolve(DocumentClassifier.classify("x.doc", null)))
        assertEquals(DocumentOpenResult.LegacyPowerPoint, DocumentReaderResolver.resolve(DocumentClassifier.classify("x.ppt", null)))
        listOf("docm", "xlsm", "xlsb", "pptm", "pps", "ppsx").forEach {
            assertEquals(DocumentOpenResult.Unsupported, DocumentReaderResolver.resolve(DocumentClassifier.classify("x.$it", null)))
            assertFalse(DocumentClassifier.findByExtension(it)!!.advertiseForExternalOpen)
        }
        assertEquals(DocumentOpenResult.Unsupported, DocumentReaderResolver.resolve(null))
    }

    private fun formatId(name: String?, mime: String?): String? = when (val result = DocumentClassifier.classify(name, mime)) {
        is DocumentClassification.Recognized -> result.format.id
        is DocumentClassification.UnsupportedDocument -> result.format.id
        DocumentClassification.Unknown -> null
    }
}
