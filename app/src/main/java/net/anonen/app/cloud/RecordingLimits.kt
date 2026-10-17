package net.anonen.app.cloud

import net.anonen.app.core.LocalModelInfo

class RecordingLimits {
    @Volatile
    private var gatewayMaxRequestSeconds: Int? = null

    val maxRecordingSeconds: Int
        get() = gatewayMaxRequestSeconds ?: FALLBACK_MAX_RECORDING_SECONDS

    fun limitSecondsFor(selectedModel: String): Int? =
        if (LocalModelInfo.fromId(selectedModel) != null) null else maxRecordingSeconds

    fun recordingLimitSecondsFor(selectedModel: String): Int =
        limitSecondsFor(selectedModel) ?: LOCAL_MAX_RECORDING_SECONDS

    fun update(maxRequestSeconds: Int?) {
        gatewayMaxRequestSeconds = maxRequestSeconds?.takeIf { it > 0 }
    }

    companion object {
        const val FALLBACK_MAX_RECORDING_SECONDS = 240

        const val LOCAL_MAX_RECORDING_SECONDS = 600
    }
}
