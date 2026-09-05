package com.droidnova.allfilereader.navigation

import org.junit.Assert.*
import org.junit.Test

class RecentSearchCoordinatorTest {
    @Test fun `request remains pending for a late destination collector`() {
        val coordinator = RecentSearchCoordinator()
        val id = coordinator.request(SearchActivationSource.Home)
        assertEquals(SearchActivationRequest(id, SearchActivationSource.Home), coordinator.pending.value)
    }

    @Test fun `acknowledgement is id safe and consumes exactly once`() {
        val coordinator = RecentSearchCoordinator()
        val first = coordinator.request(SearchActivationSource.Home)
        coordinator.acknowledge(first + 1)
        assertEquals(first, coordinator.pending.value?.id)
        coordinator.acknowledge(first)
        assertNull(coordinator.pending.value)
        coordinator.acknowledge(first)
        assertNull(coordinator.pending.value)
    }

    @Test fun `each activation has a unique focus request id`() {
        val coordinator = RecentSearchCoordinator()
        val first = coordinator.request(SearchActivationSource.Recent)
        val second = coordinator.request(SearchActivationSource.Recent)
        assertTrue(second > first)
    }
}
