package com.droidnova.allfilereader.data.powerpoint

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PptxSessionStoreTest {
    @Test fun staleCleanupOnlyDeletesOwnedEligibleInactiveFiles() {
        val cache = createTempDir(prefix = "pptx-cache-")
        val store = PptxSessionStore(cache)
        val activeId = store.newId()
        val active = store.create(activeId).apply { writeText("active"); setLastModified(1) }
        val staleId = store.newId()
        val stale = store.create(staleId).apply { writeText("stale"); setLastModified(1) }
        store.release(staleId, null)
        val unrelated = File(cache, "unrelated.tmp").apply { writeText("keep"); setLastModified(1) }

        assertEquals(1, store.removeStaleSessions(PptxSessionStore.STALE_AGE_MILLIS + 2))
        assertTrue(active.exists())
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
    }
    @Test fun releaseIsContainedAndIdempotent() {
        val cache = createTempDir(prefix = "pptx-cache-")
        val store = PptxSessionStore(cache)
        val id = store.newId()
        val owned = store.create(id).apply { writeText("data") }
        val outside = File(cache.parentFile, "outside.pptx").apply { writeText("keep") }
        assertFalse(store.release(id, outside))
        assertTrue(outside.exists())
        assertTrue(store.release(id, owned))
        assertTrue(store.release(id, owned))
    }
}
