package net.anonen.app.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.asr.EngineProvider
import net.anonen.app.cloud.TranscribeCancelController
import net.anonen.app.core.AccessControl
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.inject.DeliveryOutcome
import net.anonen.app.pipeline.TranscriptionPipeline

class Retranscriber(
    private val app: AnonenApp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _runningEntryId = MutableStateFlow<Long?>(null)

    val runningEntryId: StateFlow<Long?> = _runningEntryId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    private var runningJob: Job? = null

    private var runningCancel: TranscribeCancelController? = null

    init {

        scope.launch {
            app.entitlement.gate.collect { gate ->
                if (gate != AccessControl.AccessGate.ALLOWED) cancelRunning()
            }
        }
    }

    private fun cancelRunning() {
        if (_runningEntryId.value == null) return
        runningCancel?.requestCancel()
        runningJob?.cancel()
        _message.value = RetranscribeRules.NEEDS_ACCESS
    }

    fun retranscribe(
        entry: HistoryEntry,
        appendAsNewEntry: Boolean = false,
    ) {
        val start =
            RetranscribeRules.canStart(
                running = _runningEntryId.value != null,
                gate = app.entitlement.gate.value,
                hasAudio = entry.audioFileName != null,
            )
        if (start is RetranscribeRules.Start.Refused) {
            _message.value = start.message
            return
        }
        _runningEntryId.value = entry.id

        val cancel = TranscribeCancelController()
        runningCancel = cancel
        runningJob =
            scope.launch {
                try {
                    val wav = withContext(Dispatchers.IO) { app.historyRepository.readAudio(entry) }
                    if (wav == null || wav.isEmpty()) {
                        _message.value = RetranscribeRules.NO_AUDIO
                        return@launch
                    }

                    if (app.entitlement.gate.value != AccessControl.AccessGate.ALLOWED) {
                        _message.value = RetranscribeRules.NEEDS_ACCESS
                        return@launch
                    }

                    lastModelUsed = null

                    launch { runCatching { app.enclaveKeys.prefetch() } }
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    val outcome = pipeline(appendAsNewEntry, cancel).run(wav)
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    val result = RetranscribeRules.resultOf(outcome, elapsed)

                    var written = true
                    if (!appendAsNewEntry) {
                        result.updatedText?.let {
                            written = app.historyRepository.updateText(entry.id, it, lastModelUsed)
                        }
                    }

                    DiagnosticsLog.log(
                        "再転写 id=${entry.id} 更新=${result.updatedText != null} " +
                            "書き戻し=$written ${elapsed}ms",
                    )
                    _message.value = if (written) result.message else RetranscribeRules.ROW_GONE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.logThrowable("retranscribe", e)
                    _message.value = RetranscribeRules.INTERNAL_ERROR
                } finally {
                    _runningEntryId.value = null
                    runningCancel = null
                }
            }
    }

    private var lastModelUsed: String? = null

    private fun pipeline(
        appendAsNewEntry: Boolean,
        cancel: TranscribeCancelController,
    ) = TranscriptionPipeline(
        settingsProvider = { app.settingsRepository.current() },
        engineFactory = { s ->
            EngineProvider.create(
                app,
                s,
                onUsageUpdate = { app.cloudUsage.updateFromTranscribe(it) },
                onModelUsed = { id -> lastModelUsed = id },
                cancel = cancel,
            )
        },
        deliver = { DeliveryOutcome.INJECTED },
        saveHistory = { text, duration, retention, wav ->

            if (appendAsNewEntry) {
                app.historyRepository.add(text, duration, retention.limit, wav, lastModelUsed, retention.audioLimit)
            }
        },
        modelNameResolver = { s -> EngineProvider.resolvedModelName(s) },
        onAsrMeasured = { modelId, asrMs -> app.modelStats.record(modelId, asrMs) },
        isCancelled = { cancel.isCancelled },
        savesUntranscribedAudio = appendAsNewEntry,
    )
}
