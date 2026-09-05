package com.droidnova.allfilereader.ui.screens.powerpoint

import org.junit.Assert.*
import org.junit.Test

class RendererProgressWatchdogTest {
    @Test fun lackOfProgressStallsOnlyAfterBoundary() {
        val watchdog = RendererProgressWatchdog(100, 1_000)
        assertFalse(watchdog.isStalled(1_100))
        assertTrue(watchdog.isStalled(1_101))
    }
    @Test fun validProgressResetsInactivityClockButDuplicatesDoNot() {
        val watchdog = RendererProgressWatchdog(100, 1_000)
        assertTrue(watchdog.observe("render_started", 0, 1_050))
        assertFalse(watchdog.observe("render_started", 0, 1_090))
        assertTrue(watchdog.isStalled(1_151))
        assertTrue(watchdog.observe("first_slide_rendered", 1, 1_151))
        assertFalse(watchdog.isStalled(1_200))
        assertFalse(watchdog.observe("untrusted", 99, 1_250))
    }
    @Test fun successOrFailureStopWatchdog() {
        listOf("render_complete", "render_failed").forEach { terminal ->
            val watchdog = RendererProgressWatchdog(10, 0)
            watchdog.observe(terminal, 1, 1)
            watchdog.stop()
            assertFalse(watchdog.isStalled(Long.MAX_VALUE))
        }
    }
}
