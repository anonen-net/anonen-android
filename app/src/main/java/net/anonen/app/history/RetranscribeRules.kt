package net.anonen.app.history

import net.anonen.app.cloud.AnonenCloudEngine
import net.anonen.app.core.AccessControl
import net.anonen.app.core.ModelIds
import net.anonen.app.pipeline.PipelineOutcome
import java.util.Locale

object RetranscribeRules {
    const val BUSY = "他の録音を文字起こし中です"
    const val NO_AUDIO = "この記録には録音が残っていません"
    const val NEEDS_ACCESS = "ログインと契約が必要です"
    const val INTERNAL_ERROR = "うまくいきませんでした"
    const val EMPTY = "言葉が聞き取れませんでした"
    const val INTERRUPTED = "文字起こしをやめました"

    const val ROW_GONE = "この記録は削除されていたので、文字は残していません"

    const val TOO_LONG = AnonenCloudEngine.AUDIO_TOO_LONG_MESSAGE

    fun tooLongForCloud(
        audioMs: Long,
        limitSeconds: Int?,
    ): Boolean {
        if (limitSeconds == null) return false
        val seconds = (audioMs + 999) / 1000
        return seconds > limitSeconds
    }

    const val USAGE_CONFIRM = "もう一度文字起こしすると、\n使用量に加算されます。"
    const val USAGE_CONFIRM_GO = "文字起こしする"
    const val USAGE_CONFIRM_STOP = "やめる"

    const val USAGE_CONFIRM_QUIET_MS = 3 * 60_000L

    fun needsUsageConfirm(
        selectedModel: String,
        allowed: Boolean,
        confirmedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (!selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX) || !allowed) return false
        val at = confirmedAtMs ?: return true
        return (nowMs - at) !in 0 until USAGE_CONFIRM_QUIET_MS
    }

    sealed interface Start {
        data object Ready : Start

        data class Refused(val message: String) : Start
    }

    fun canStart(
        running: Boolean,
        gate: AccessControl.AccessGate,
        hasAudio: Boolean,
    ): Start =
        when {
            running -> Start.Refused(BUSY)
            gate != AccessControl.AccessGate.ALLOWED -> Start.Refused(NEEDS_ACCESS)
            !hasAudio -> Start.Refused(NO_AUDIO)
            else -> Start.Ready
        }

    data class Result(
        val message: String,
        val updatedText: String?,
        val updateRequired: Boolean = false,
    )

    fun resultOf(
        outcome: PipelineOutcome,
        elapsedMs: Long,
    ): Result =
        when (outcome) {
            is PipelineOutcome.Delivered ->
                Result("文字起こししました（${seconds(elapsedMs)}秒）", outcome.text)

            is PipelineOutcome.AsrFailed -> Result(outcome.error.userMessage, null, outcome.error.updateRequired)
            is PipelineOutcome.EmptyTranscription -> Result(EMPTY, null)

            is PipelineOutcome.Cancelled -> Result(INTERRUPTED, null)
        }

    private fun seconds(elapsedMs: Long): String =
        String.format(Locale.US, "%.1f", elapsedMs.coerceAtLeast(0L) / 1000.0)
}
