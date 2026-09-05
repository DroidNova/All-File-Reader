package com.droidnova.allfilereader.ui.screens.powerpoint

internal class RendererProgressWatchdog(private val inactivityLimitMillis: Long, startedAtMillis: Long) {
    private var lastSignature: String? = null
    private var lastProgressAtMillis = startedAtMillis
    private var running = true
    fun observe(stage: String, progress: Long, nowMillis: Long): Boolean {
        if (!running || stage !in VALID_RENDERER_STAGES) return false
        val signature = "$stage:$progress"
        if (signature == lastSignature) return false
        lastSignature = signature
        lastProgressAtMillis = nowMillis
        return true
    }
    fun stop() { running = false }
    fun isStalled(nowMillis: Long): Boolean = running && nowMillis - lastProgressAtMillis > inactivityLimitMillis
    companion object {
        val VALID_RENDERER_STAGES = setOf("viewer_loaded", "document_fetch_started", "document_fetch_complete", "render_started", "first_slide_rendered", "render_complete", "render_failed")
    }
}
