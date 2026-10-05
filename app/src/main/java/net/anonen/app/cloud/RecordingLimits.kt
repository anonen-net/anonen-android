package net.anonen.app.cloud

class RecordingLimits {
    @Volatile
    private var gatewayMaxRequestSeconds: Int? = null

    val maxRecordingSeconds: Int
        get() = gatewayMaxRequestSeconds ?: FALLBACK_MAX_RECORDING_SECONDS

    fun update(maxRequestSeconds: Int?) {
        gatewayMaxRequestSeconds = maxRequestSeconds?.takeIf { it > 0 }
    }

    companion object {
        const val FALLBACK_MAX_RECORDING_SECONDS = 240
    }
}
