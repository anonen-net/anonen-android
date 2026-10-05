package net.anonen.app.pipeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.anonen.app.asr.TranscriptionEngine
import net.anonen.app.core.AsrError
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.HistoryRetention
import net.anonen.app.core.LanguageHints
import net.anonen.app.core.TranscriptionRequest
import net.anonen.app.core.TranscriptionResult
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.history.LegacyHistoryLabels
import net.anonen.app.inject.DeliveryOutcome
import net.anonen.app.settings.AnonenSettings
import net.anonen.app.textproc.applyCustomWords
import net.anonen.app.textproc.filterTranscriptionOutput

sealed interface PipelineOutcome {
    data class Delivered(
        val delivery: DeliveryOutcome,
        val text: String,
    ) : PipelineOutcome

    data class AsrFailed(
        val error: AsrError,
        val audioSavedToHistory: Boolean = false,
    ) : PipelineOutcome

    data class EmptyTranscription(
        val audioSavedToHistory: Boolean = false,
    ) : PipelineOutcome

    data class Cancelled(val audioSavedToHistory: Boolean) : PipelineOutcome
}

class TranscriptionPipeline(
    private val settingsProvider: suspend () -> AnonenSettings,
    private val engineFactory: (AnonenSettings) -> TranscriptionEngine,
    private val deliver: (String) -> DeliveryOutcome,
    private val saveHistory:
        suspend (text: String, durationMs: Long, retention: HistoryRetention, wavBytes: ByteArray) -> Unit =
        { _, _, _, _ -> },
    private val modelNameResolver: (AnonenSettings) -> String = { it.selectedModel },
    private val onAsrMeasured: suspend (modelId: String, asrMs: Long) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
    private val savesUntranscribedAudio: Boolean = true,
) {
    suspend fun run(wavBytes: ByteArray): PipelineOutcome {
        val settings = settingsProvider()
        val languageHint = LanguageHints.normalize(settings.languageHint)

        return when (val result = transcribe(settings, wavBytes, languageHint)) {
            is TranscriptionResult.Failure -> {
                if (isCancelled()) {
                    val saved = preserveCancelledAudio(settings, wavBytes)
                    DiagnosticsLog.log("転写キャンセル（ユーザー操作）履歴保存=$saved")
                    return PipelineOutcome.Cancelled(saved)
                }
                DiagnosticsLog.log(
                    "転写失敗 ${result.error.kind} http=${result.error.httpStatus ?: "-"}: " +
                        "${result.error.userMessage} | ${result.error.detail.take(120)}",
                )
                val (error, saved) = preserveFailedAudio(result.error, settings, wavBytes)
                PipelineOutcome.AsrFailed(error, saved)
            }
            is TranscriptionResult.Success -> {
                var text =
                    applyCustomWords(
                        result.text,
                        settings.customWords,
                        settings.wordCorrectionThreshold,
                    )
                text =
                    filterTranscriptionOutput(
                        text,
                        languageHint.ifBlank { "auto" },
                        settings.customFillerWords,
                    )
                if (text.isBlank()) {
                    DiagnosticsLog.log("転写は空（補正後に文字なし。元 ${result.text.length}文字）")

                    val saved = preserveEmptyAudio(settings, wavBytes)
                    DiagnosticsLog.log("転写が空: 履歴保存=$saved")
                    return PipelineOutcome.EmptyTranscription(saved)
                }
                if (settings.appendTrailingSpace) text += " "

                val delivery = deliver(text)
                DiagnosticsLog.log("転写成功 ${text.length}文字 → 配送=$delivery")
                if (settings.historyEnabled) {
                    runCatching {
                        saveHistory(text, result.durationMs, settings.historyRetention, wavBytes)
                    }.onFailure {
                        DiagnosticsLog.log("履歴に残せず（${it.javaClass.simpleName}）配送は成功")
                    }
                }
                PipelineOutcome.Delivered(delivery, text)
            }
        }
    }

    private suspend fun preserveCancelledAudio(
        settings: AnonenSettings,
        wavBytes: ByteArray,
    ): Boolean {
        if (!savesUntranscribedAudio || !settings.historyEnabled) return false
        return runCatching {
            saveHistory(CANCELLED_HISTORY_TEXT, 0L, settings.historyRetention, wavBytes)
        }.isSuccess
    }

    private suspend fun preserveFailedAudio(
        error: AsrError,
        settings: AnonenSettings,
        wavBytes: ByteArray,
    ): Pair<AsrError, Boolean> {
        if (!savesUntranscribedAudio || !settings.historyEnabled) return error to false
        val saved =
            runCatching {
                saveHistory(failedHistoryText(error), 0L, settings.historyRetention, wavBytes)
            }.isSuccess
        if (!saved) return error to false
        DiagnosticsLog.log("転写失敗: 録音 WAV を履歴に保存（全損にしない）")

        if (error.httpStatus == 401 || error.httpStatus == 402) return error to true
        return error.copy(userMessage = error.userMessage + AUDIO_SAVED_SUFFIX) to true
    }

    private suspend fun preserveEmptyAudio(
        settings: AnonenSettings,
        wavBytes: ByteArray,
    ): Boolean {
        if (!savesUntranscribedAudio || !settings.historyEnabled) return false
        return runCatching {
            saveHistory(EMPTY_HISTORY_TEXT, 0L, settings.historyRetention, wavBytes)
        }.isSuccess
    }

    private fun failedHistoryText(error: AsrError): String =
        when {
            error.httpStatus == 413 -> AUDIO_TOO_LONG_HISTORY_TEXT
            error.isCapExceeded -> CAP_EXCEEDED_HISTORY_TEXT
            else -> FAILED_HISTORY_TEXT
        }

    private suspend fun transcribe(
        settings: AnonenSettings,
        wavBytes: ByteArray,
        languageHint: String,
    ): TranscriptionResult {
        val engine = engineFactory(settings)
        val modelName = modelNameResolver(settings)
        DiagnosticsLog.log("転写リクエスト model=$modelName wav=${wavBytes.size}B")

        val asrStart = android.os.SystemClock.elapsedRealtime()
        val result =
            withContext(Dispatchers.IO) {
                engine.transcribe(TranscriptionRequest(wavBytes, languageHint, isCancelled))
            }
        val asrMs = android.os.SystemClock.elapsedRealtime() - asrStart
        DiagnosticsLog.log("転写応答 ${asrMs}ms")

        if (result is TranscriptionResult.Success) {
            onAsrMeasured(settings.selectedModel, asrMs)
            return result
        }

        if (isCancelled()) return result

        val fallbackId =
            DevFeaturesProvider.instance.fallbackModelId(settings.selectedModel)
                ?: return result
        DiagnosticsLog.log("フォールバック $modelName → $fallbackId")

        val fbSettings = settings.copy(selectedModel = fallbackId)
        val fbEngine = engineFactory(fbSettings)
        val fbStart = android.os.SystemClock.elapsedRealtime()
        val fbResult =
            withContext(Dispatchers.IO) {
                fbEngine.transcribe(TranscriptionRequest(wavBytes, languageHint, isCancelled))
            }
        val fbMs = android.os.SystemClock.elapsedRealtime() - fbStart
        DiagnosticsLog.log("フォールバック応答 ${fbMs}ms")

        if (fbResult is TranscriptionResult.Success) onAsrMeasured(fallbackId, fbMs)
        return fbResult
    }

    companion object {
        const val AUDIO_TOO_LONG_HISTORY_TEXT = "（長すぎて、文字起こししていません）"

        const val CAP_EXCEEDED_HISTORY_TEXT = "（使える分を使い切ったので、文字起こししていません）"

        const val FAILED_HISTORY_TEXT = "（文字起こしできませんでした）"

        const val CANCELLED_HISTORY_TEXT = "（やめたので、文字起こししていません）"

        const val EMPTY_HISTORY_TEXT = "（言葉が聞き取れませんでした）"

        const val NO_SPEECH_HISTORY_TEXT = "（声が聞こえませんでした）"

        private val UNTRANSCRIBED_HISTORY_TEXTS =
            setOf(
                AUDIO_TOO_LONG_HISTORY_TEXT,
                CAP_EXCEEDED_HISTORY_TEXT,
                FAILED_HISTORY_TEXT,
                CANCELLED_HISTORY_TEXT,
                EMPTY_HISTORY_TEXT,
                NO_SPEECH_HISTORY_TEXT,
            )

        fun isUntranscribedHistoryText(text: String): Boolean =
            text in UNTRANSCRIBED_HISTORY_TEXTS || text in LegacyHistoryLabels.TO_CURRENT

        fun historyDisplayText(text: String): String = LegacyHistoryLabels.TO_CURRENT[text] ?: text

        const val AUDIO_SAVED_SUFFIX = "。録音は記録に残しました"
    }
}
