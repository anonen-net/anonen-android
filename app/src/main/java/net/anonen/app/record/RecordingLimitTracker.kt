package net.anonen.app.record

class RecordingLimitTracker(
    limitSeconds: Int?,
    bytesPerSecond: Int,
) {
    enum class Event { WARNING, AUTO_STOP }

    private val limitBytes: Long? =
        limitSeconds?.let {
            (it.toLong() * bytesPerSecond - AUTO_STOP_MARGIN_MS * bytesPerSecond / 1000)
                .coerceAtLeast(1L)
        }
    private val warningBytes: Long? =
        limitSeconds
            ?.takeIf { it > WARNING_LEAD_SECONDS }
            ?.let { (it - WARNING_LEAD_SECONDS).toLong() * bytesPerSecond }

    private var warned = false
    private var autoStopped = false

    fun onBuffered(totalBytes: Long): Event? {
        val limit = limitBytes ?: return null
        if (autoStopped) return null
        if (totalBytes >= limit) {
            autoStopped = true
            return Event.AUTO_STOP
        }
        val warnAt = warningBytes
        if (!warned && warnAt != null && totalBytes >= warnAt) {
            warned = true
            return Event.WARNING
        }
        return null
    }

    companion object {
        const val WARNING_LEAD_SECONDS = 30

        const val AUTO_STOP_MARGIN_MS = 500L
    }
}
