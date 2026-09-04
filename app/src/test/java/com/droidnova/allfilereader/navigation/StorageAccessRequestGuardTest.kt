package com.droidnova.allfilereader.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessRequestGuardTest {
    @Test fun initialStateIsChecking() {
        assertEquals(StorageAccessState.Checking, StorageAccessRequestGuard().state)
    }

    @Test fun missingPermissionShowsGateAndOnlyOneLaunchCanBeClaimed() {
        val guard = StorageAccessRequestGuard()
        guard.update(granted = false)
        assertEquals(StorageAccessState.NotGranted, guard.state)
        assertTrue(guard.begin())
        assertFalse(guard.begin())
        assertEquals(StorageAccessState.Requesting, guard.state)
    }

    @Test fun returningWithoutGrantRestoresButtonAndWaitsForAnotherTap() {
        val guard = StorageAccessRequestGuard()
        guard.update(false)
        guard.begin()
        guard.dispatched()
        assertEquals(StorageAccessState.NotGranted, guard.state)
        assertTrue(guard.begin())
    }

    @Test fun returningWithGrantDismissesGate() {
        val guard = StorageAccessRequestGuard()
        guard.update(false)
        guard.begin()
        guard.update(true)
        assertEquals(StorageAccessState.Granted, guard.state)
        assertFalse(guard.begin())
    }
}
