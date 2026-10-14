package net.anonen.app.cloud

import net.anonen.app.core.LocalModelInfo

class RecordingLimits {
    @Volatile
    private var gatewayMaxRequestSeconds: Int? = null

    val maxRecordingSeconds: Int
        get() = gatewayMaxRequestSeconds ?: FALLBACK_MAX_RECORDING_SECONDS

    fun limitSecondsFor(selectedModel: String): Int? =
        if (LocalModelInfo.fromId(selectedModel) != null) null else maxRecordingSeconds

    fun update(maxRequestSeconds: Int?) {
        gatewayMaxRequestSeconds = maxRequestSeconds?.takeIf { it > 0 }
    }

    companion object {
        const val FALLBACK_MAX_RECORDING_SECONDS = 240
    }
}
