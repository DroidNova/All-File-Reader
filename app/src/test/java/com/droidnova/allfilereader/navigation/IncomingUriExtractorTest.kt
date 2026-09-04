package com.droidnova.allfilereader.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingUriExtractorTest {
    private val uri = "content://provider/document/1"

    @Test fun `data has first priority`() = assertSelected(IncomingUriSource.Data, uri, null, false, null)
    @Test fun `single clip URI is accepted`() = assertSelected(IncomingUriSource.ClipData, null, listOf(uri), false, null)
    @Test fun `single stream URI is compatibility fallback`() = assertSelected(IncomingUriSource.ExtraStream, null, null, true, uri)
    @Test fun `same URI in all locations is one document`() = assertSelected(IncomingUriSource.Data, uri, listOf(uri), true, uri)

    @Test fun `conflicting locations are ambiguous`() {
        assertTrue(IncomingUriExtractor.select(uri, listOf("content://provider/document/2"), false, null) is IncomingUriExtractor.UriSelection.Ambiguous)
    }

    @Test fun `multiple clip items are ambiguous`() {
        assertTrue(IncomingUriExtractor.select(null, listOf(uri, uri), false, null) is IncomingUriExtractor.UriSelection.Ambiguous)
    }

    @Test fun `absent URI is missing`() {
        assertTrue(IncomingUriExtractor.select(null, null, false, null) is IncomingUriExtractor.UriSelection.Missing)
    }

    @Test fun `non URI stream parcelable is ambiguous`() {
        assertTrue(IncomingUriExtractor.select(null, null, true, null) is IncomingUriExtractor.UriSelection.Ambiguous)
    }

    private fun assertSelected(source: IncomingUriSource, data: String?, clip: List<String?>?, hasStream: Boolean, stream: String?) {
        assertEquals(IncomingUriExtractor.UriSelection.Selected(source), IncomingUriExtractor.select(data, clip, hasStream, stream))
    }
}
