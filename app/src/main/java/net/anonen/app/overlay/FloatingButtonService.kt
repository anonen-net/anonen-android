package net.anonen.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.R
import net.anonen.app.asr.EngineProvider
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.core.AccessControl
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.LocalModelInfo
import net.anonen.app.core.ModelIds
import net.anonen.app.core.OverlayVisibility
import net.anonen.app.core.RecordingAudioSource
import net.anonen.app.core.ThemeMode
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.inject.AnonenAccessibilityService
import net.anonen.app.inject.DeliveryOutcome
import net.anonen.app.inject.TextInjector
import net.anonen.app.pipeline.PipelineOutcome
import net.anonen.app.pipeline.TranscriptionPipeline
import net.anonen.app.record.RecordingLimitTracker
import net.anonen.app.record.RecordingResult
import net.anonen.app.record.SileroVad
import net.anonen.app.record.WavRecorder
import net.anonen.app.settings.AnonenSettings
import net.anonen.app.ui.MainActivity

class FloatingButtonService : Service() {
    private val crashGuard =
        CoroutineExceptionHandler { _, e ->
            AnonenApp.from(this).logThrowable("service-coroutine", e)
            runCatching {
                if (::notifier.isInitialized) {
                    notifier.notifyResult(OverlayFeedback.of(FeedbackEvent.INTERNAL_ERROR).text)
                }

                if (::view.isInitialized) {
                    view.post {
                        if (state == ButtonState.TRANSCRIBING) setState(ButtonState.IDLE)
                    }
                }
            }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashGuard)

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: OverlayMessageView

    @Volatile
    private var modelUsed: String? = null
    private lateinit var view: FloatingButtonView
    private lateinit var notifier: AnonenNotifier
    private lateinit var sounds: FeedbackSounds
    private lateinit var injector: TextInjector
    private lateinit var pipeline: TranscriptionPipeline

    private val recorder = WavRecorder()
    private var sileroVad: SileroVad? = null
    private var state = ButtonState.IDLE
    private var feedbackEnabled = true
    private var feedbackVolume = 1.0f
    private var recordingAudioSource = RecordingAudioSource.MIC
    private var themeMode = ThemeMode.SYSTEM

    private var selectedModel = ""

    private var entitled = false

    private var recordingAccount: String? = null

    private var recordingStartedAt = 0L

    private var hapticEnabled = true

    private var heldDiscard: WavRecorder.HeldRecording? = null
    private var heldDiscardJob: Job? = null

    private var processingJob: Job? = null

    private var countdownJob: Job? = null

    private var processingBubbleShown = false

    private var buttonOnScreen = false

    private var firstUseHintJob: Job? = null

    private var firstUseHintShown = false

    private var recordingHintJob: Job? = null

    private var recordingHintShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        val app = AnonenApp.from(this)
        notifier = AnonenNotifier(this)
        sounds = FeedbackSounds(this)
        injector = TextInjector(this)
        pipeline =
            TranscriptionPipeline(
                settingsProvider = { app.settingsRepository.current() },
                engineFactory = { s ->

                    modelUsed = null
                    EngineProvider.create(
                        app,
                        s,
                        onUsageUpdate = { snapshot ->

                            scope.launch {
                                if (app.cloudUsage.updateFromTranscribe(snapshot)) {
                                    notifier.notifyWarning(
                                        OverlayFeedback.of(FeedbackEvent.LOW_REMAINING).text,
                                        getString(
                                            R.string.notif_cloud_low_remaining_text,
                                            snapshot.remainingS / 60,
                                        ),
                                    )
                                    say(FeedbackEvent.LOW_REMAINING)
                                }
                            }
                        },
                        onCloudModelInvalid = {
                            refreshCloudCatalogAndNotify()
                        },
                        onModelUsed = { id -> modelUsed = id },
                        onCloudModelsVersion = { version ->

                            if (app.cloudModels.noteModelsVersion(version)) {
                                refreshCloudCatalogAndNotify()
                            }
                        },
                    )
                },
                deliver = { text -> injector.deliver(text) },
                saveHistory = { text, duration, retention, wav ->
                    app.historyRepository.add(text, duration, retention.limit, wav, modelUsed, retention.audioLimit)
                },
                modelNameResolver = { s -> EngineProvider.resolvedModelName(s) },
                onAsrMeasured = { modelId, asrMs -> app.modelStats.record(modelId, asrMs) },
                isCancelled = { app.transcribeCancel.isCancelled },
            )

        if (!startForegroundCompat(recording = false)) {
            notifier.notifyResult(getString(R.string.notif_fgs_start_denied))
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = FloatingButtonView(this, windowManager)
        view.onTap = { onButtonTap() }
        view.onPressStart = { if (state == ButtonState.IDLE) startRecording() }
        view.onPressEnd = { if (state == ButtonState.RECORDING) stopAndTranscribe() }

        view.onPressCancel = { cancelRecording("ドラッグ離脱", feedback = false) }

        view.onLongPressCancel = { cancelRecording("長押し", feedback = true) }
        view.onPositionChanged = { x, y ->

            dismissFirstUseHint()
            dismissRecordingHint()
            scope.launch {
                app.settingsRepository.update { it.copy(buttonPosX = x, buttonPosY = y) }
            }
        }
        AnonenAccessibilityService.instance?.refreshInputVisibility()
        updateOverlayVisibility()
        bubble = OverlayMessageView(this, windowManager)
        bubble.darkTheme = isDarkTheme()
        try {
            windowManager.addView(view, view.windowParams)
        } catch (e: Exception) {
            app.logThrowable("overlay-addview", e)
            notifier.notifyResult(getString(R.string.notif_overlay_failed))
            stopSelf()
            return
        }

        recorder.onAutoStop = {
            scope.launch {
                val fb = OverlayFeedback.of(FeedbackEvent.LIMIT_STOPPED)
                notifier.notifyWarning(fb.text)
                stopAndTranscribe()
                show(fb)
            }
        }

        recorder.onAutoStopWarning = {
            scope.launch {
                val fb = say(FeedbackEvent.LIMIT_WARNING, seconds = RecordingLimitTracker.WARNING_LEAD_SECONDS)
                notifier.notifyWarning(fb.text)
                startCountdown(RecordingLimitTracker.WARNING_LEAD_SECONDS)
            }
        }

        scope.launch(Dispatchers.Default) {
            val vad = SileroVad.fromAsset(this@FloatingButtonService)
            if (vad != null) {
                sileroVad = vad
                recorder.vad = vad
                DiagnosticsLog.log("Silero VAD 準備完了")
            } else {
                DiagnosticsLog.log("Silero VAD 無効（VAD なしで録音）")
            }
        }

        scope.launch {
            OverlayVisibility.inputActive.collectLatest { active ->
                if (!active) delay(INPUT_LINGER_MS)
                updateOverlayVisibility()
            }
        }

        scope.launch {
            var wasConnected = OverlayVisibility.accessibilityConnected.value
            OverlayVisibility.accessibilityConnected.collect { connected ->

                if (wasConnected && !connected) say(FeedbackEvent.ACCESSIBILITY_OFF)
                wasConnected = connected
                updateOverlayVisibility()
            }
        }

        scope.launch {
            app.entitlement.gate.collect { g ->
                val nowEntitled = g == AccessControl.AccessGate.ALLOWED
                val lost = entitled && !nowEntitled
                entitled = nowEntitled
                abortForLostAccess(app, g)

                if (lost && state == ButtonState.IDLE) say(FeedbackEvent.ACCESS_REQUIRED)
                updateOverlayVisibility()
            }
        }

        scope.launch {
            app.settingsRepository.settings.collect { s ->
                selectedModel = s.selectedModel
                view.pushToTalk = s.pushToTalk
                themeMode = s.themeMode
                view.darkTheme = isDarkTheme()
                view.applyAppearance(s.buttonSizeDp, s.buttonAlpha, s.buttonPosX, s.buttonPosY)
                feedbackEnabled = s.audioFeedback
                feedbackVolume = s.audioFeedbackVolume
                hapticEnabled = s.hapticFeedback
                recordingAudioSource = s.recordingAudioSource
                recorder.spectralEqEnabled = s.spectralEqEnabled
                updateOverlayVisibility()
            }
        }

        warmCloudConnection("常駐開始")
    }

    private fun isDarkTheme(): Boolean =
        when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::view.isInitialized) view.darkTheme = isDarkTheme()
        if (::bubble.isInitialized) bubble.darkTheme = isDarkTheme()
    }

    private fun updateOverlayVisibility() {
        if (!::view.isInitialized) return

        val show =
            OverlayVisibility.buttonVisible(
                entitled = entitled,
                idle = state == ButtonState.IDLE,
                inputActive = OverlayVisibility.inputActive.value,
                accessibilityConnected = OverlayVisibility.accessibilityConnected.value,
            )
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !buttonOnScreen) offerFirstUseHint()
        if (!show) dismissFirstUseHint()
        if (!show) dismissRecordingHint()
        buttonOnScreen = show
    }

    private fun offerFirstUseHint() {
        firstUseHintJob?.cancel()
        firstUseHintJob =
            scope.launch {
                delay(FIRST_USE_HINT_DELAY_MS)
                val app = AnonenApp.from(this@FloatingButtonService)
                val s = app.settingsRepository.current()
                if (!buttonOnScreen) return@launch
                if (!OverlayFeedback.showsFirstUseHint(s.firstUseHintsShown, state, s.hasModelSelected)) return@launch
                say(FeedbackEvent.FIRST_USE_HINT)

                withContext(NonCancellable) {
                    app.settingsRepository.update { it.copy(firstUseHintsShown = it.firstUseHintsShown + 1) }
                }
            }
    }

    private fun dismissFirstUseHint() {
        firstUseHintJob?.cancel()
        firstUseHintJob = null
        if (firstUseHintShown && ::bubble.isInitialized) bubble.detach()
        firstUseHintShown = false
    }

    private fun offerRecordingHint() {
        recordingHintJob?.cancel()
        recordingHintJob =
            scope.launch {
                delay(RECORDING_HINT_DELAY_MS)
                val app = AnonenApp.from(this@FloatingButtonService)
                val s = app.settingsRepository.current()
                val hint = OverlayFeedback.recordingHint(s.recordingHintsShown, state, view.pushToTalk)
                if (hint == null) return@launch
                say(hint)

                withContext(NonCancellable) {
                    app.settingsRepository.update { it.copy(recordingHintsShown = it.recordingHintsShown + 1) }
                }
            }
    }

    private fun dismissRecordingHint() {
        recordingHintJob?.cancel()
        recordingHintJob = null
        if (recordingHintShown && ::bubble.isInitialized) bubble.detach()
        recordingHintShown = false
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CANCEL_RECORDING -> cancelRecording("通知アクション", feedback = true)
            ACTION_CANCEL_TRANSCRIBE -> cancelTranscription()
            ACTION_UNDO_DISCARD -> undoDiscard()
        }
        return START_NOT_STICKY
    }

    private fun cancelTranscription() {
        if (state != ButtonState.TRANSCRIBING) return
        DiagnosticsLog.log("転写キャンセル要求（通知アクション）")
        AnonenApp.from(this).transcribeCancel.requestCancel()
    }

    override fun onDestroy() {
        isRunning = false
        dropHeldDiscard()
        recorder.cancel()
        sileroVad?.close()
        sileroVad = null
        if (::bubble.isInitialized) bubble.detach()
        if (::view.isInitialized && view.isAttachedToWindow) {
            view.release()
            windowManager.removeView(view)
        }
        if (::sounds.isInitialized) sounds.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun onButtonTap() {
        val sinceStart = android.os.SystemClock.elapsedRealtime() - recordingStartedAt
        when (ButtonGesture.tapAction(state, sinceStart)) {
            TapAction.START -> startRecording()
            TapAction.STOP -> stopAndTranscribe()

            TapAction.TOO_SOON -> Unit
            TapAction.OFFER_CANCEL -> say(FeedbackEvent.PROCESSING)
        }
    }

    private fun startRecording() {
        dropHeldDiscard()
        val app = AnonenApp.from(this)

        if (app.entitlement.gate.value != AccessControl.AccessGate.ALLOWED) {
            DiagnosticsLog.log("録音ブロック: 認証/契約が必要 (${app.entitlement.gate.value})")
            notifier.notifyAccessRequired()
            app.userNotices.post(getString(R.string.notice_access_required))
            fail(FeedbackEvent.ACCESS_REQUIRED)
            return
        }

        recordingAccount = app.cloudAuth.status().email

        if (ModelIds.isNoneSelected(selectedModel)) {
            DiagnosticsLog.log("録音ブロック: モデル未選択")
            notifier.notifyNoModelSelected()
            app.userNotices.post(getString(R.string.notice_no_model_selected))
            fail(FeedbackEvent.NO_MODEL)
            return
        }

        LocalModelInfo.fromId(selectedModel)?.let { local ->
            if (!app.localModelManager.isReady(local)) {
                DiagnosticsLog.log("録音ブロック: 端末内モデルが未取得 ($selectedModel)")
                notifier.notifyResult(fail(FeedbackEvent.LOCAL_MODEL_MISSING).text)
                app.userNotices.post(getString(R.string.notice_local_model_missing))
                return
            }
        }

        if (app.cloudModels.isSelectionRetired(selectedModel)) {
            DiagnosticsLog.log("録音ブロック: 選択モデルが提供終了 ($selectedModel)")
            scope.launch { handleRetiredSelection(app, app.settingsRepository.current()) }
            fail(FeedbackEvent.MODEL_RETIRED)
            return
        }
        if (!startForegroundCompat(recording = true)) {
            startForegroundCompat(recording = false)
            notifier.notifyResult(fail(FeedbackEvent.MIC_DENIED).text)
            return
        }

        warmCloudConnection("録音開始")

        setState(ButtonState.RECORDING)
        recordingStartedAt = android.os.SystemClock.elapsedRealtime()
        if (feedbackEnabled) sounds.playStart(feedbackVolume)
        if (hapticEnabled) view.buzz(Buzz.START)
        view.post {
            if (state != ButtonState.RECORDING) return@post
            recorder.audioSource = recordingAudioSource
            recorder.maxSeconds = recordingLimitSeconds(app)
            if (!recorder.start()) {
                startForegroundCompat(recording = false)
                DiagnosticsLog.log("録音開始に失敗（マイクを開けない）")
                notifier.notifyResult(fail(FeedbackEvent.MIC_FAILED).text)
            } else {
                DiagnosticsLog.log("録音開始")

                offerRecordingHint()
            }
        }
    }

    private fun recordingLimitSeconds(app: AnonenApp): Int? =
        if (LocalModelInfo.fromId(selectedModel) != null) {
            null
        } else {
            app.recordingLimits.maxRecordingSeconds
        }

    private fun warmCloudConnection(trigger: String) {
        val app = AnonenApp.from(this)
        scope.launch(Dispatchers.IO) {
            val settings = app.settingsRepository.current()
            if (!settings.selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) return@launch
            if (!app.cloudAuth.isSignedIn()) {
                DiagnosticsLog.log("warm skip 未サインイン（$trigger）")
                return@launch
            }
            val model =
                settings.selectedModel
                    .removePrefix(ModelIds.ANONEN_CLOUD_PREFIX)
                    .ifBlank { null }

            launch { runCatching { app.enclaveKeys.prefetch() } }
            val startMs = android.os.SystemClock.elapsedRealtime()
            runCatching { app.cloudClient.warm(app.cloudAuth.getAccessToken(), model) }
                .onSuccess {
                    val ms = android.os.SystemClock.elapsedRealtime() - startMs
                    DiagnosticsLog.log("warm 完了 ${ms}ms（$trigger）")
                }
                .onFailure {
                    DiagnosticsLog.log("warm 失敗 ${it.javaClass.simpleName}（$trigger）")
                }
        }
    }

    private fun refreshCloudCatalogAndNotify() {
        val app = AnonenApp.from(this)
        scope.launch {
            app.cloudModels.refresh(force = true)
            val settings = app.settingsRepository.current()
            if (app.cloudModels.isSelectionRetired(settings.selectedModel)) {
                handleRetiredSelection(app, settings)
            }
        }
    }

    private suspend fun handleRetiredSelection(
        app: AnonenApp,
        settings: AnonenSettings,
    ) {
        val target =
            app.cloudModels.firstAvailablePreviouslySelected(settings.previouslySelectedCloudModels) {
                settings.allowsOneTapSwitchTo(it.id, it.dataPolicy().fingerprint)
            }
        if (target != null) {
            val name = target.displayName.ifBlank { target.id }
            notifier.notifyModelRetired(switchTargetId = target.id, switchTargetName = name)
            app.userNotices.post(getString(R.string.notice_model_retired_can_switch, name))
            DiagnosticsLog.log("提供終了: 過去選択 ${target.id} への切替を提示")
        } else {
            app.settingsRepository.update { it.withNoModelSelected() }
            notifier.notifyNoModelSelected()
            app.userNotices.post(getString(R.string.notice_model_retired_deselected))
            DiagnosticsLog.log("提供終了: ワンタップで切り替えられる過去選択が無いため選択なしに")
        }
    }

    private fun cancelRecording(
        reason: String,
        feedback: Boolean,
    ) {
        if (state != ButtonState.RECORDING) return
        DiagnosticsLog.log("録音キャンセル（$reason）")
        val held = if (feedback) recorder.stopAndHold() else null
        if (!feedback) recorder.cancel()
        startForegroundCompat(recording = false)
        setState(ButtonState.IDLE)
        if (feedback) {
            view.flashCancel()
            if (hapticEnabled) view.buzz(Buzz.STOP)
            offerUndo(held)
        }
    }

    private fun offerUndo(held: WavRecorder.HeldRecording?) {
        dropHeldDiscard()
        val holdMs =
            if (OverlayFeedback.onScreen(FeedbackEvent.RECORDING_DISCARDED, notifier.canPost())) {
                val fb = say(FeedbackEvent.RECORDING_DISCARDED, audioKept = held != null)
                bubbleDurationMs(fb.text, hasAction = true)
            } else {
                notifier.notifyRecordingDiscarded(undoable = held != null, timeoutMs = UNDO_HOLD_MS)
                UNDO_HOLD_MS
            }
        if (held == null) return
        heldDiscard = held
        heldDiscardJob =
            scope.launch {
                delay(holdMs)
                heldDiscard = null
                notifier.cancelRecordingDiscarded()
            }
    }

    private fun undoDiscard() {
        val held = heldDiscard

        dropHeldDiscard()
        if (held == null) return

        if (state != ButtonState.IDLE && state != ButtonState.ERROR) return
        DiagnosticsLog.log("消した録音を元に戻す")
        val refusal = transcriptionRefusal()
        if (refusal != null) {
            refuseTranscription(refusal)
            return
        }
        beginTranscribing { recorder.finish(held) }
    }

    private fun dropHeldDiscard() {
        heldDiscardJob?.cancel()
        heldDiscardJob = null
        heldDiscard = null
        if (::notifier.isInitialized) notifier.cancelRecordingDiscarded()
    }

    private fun abortForLostAccess(
        app: AnonenApp,
        gate: AccessControl.AccessGate,
    ) {
        when (RecordingAccessRules.abortOn(gate, state)) {
            RecordingAccessRules.Abort.NONE -> return
            RecordingAccessRules.Abort.DISCARD_RECORDING -> {
                DiagnosticsLog.log("認可喪失で録音を破棄 ($gate)")
                cancelRecording("認可喪失", feedback = false)
            }
            RecordingAccessRules.Abort.CANCEL_TRANSCRIBE -> {
                DiagnosticsLog.log("認可喪失で転写を中断 ($gate)")
                app.transcribeCancel.requestCancel()
            }
        }
        notifier.notifyAccessRequired()
        app.userNotices.post(getString(R.string.notice_access_required))
        say(FeedbackEvent.ACCESS_REQUIRED)
    }

    private fun stopAndTranscribe() {
        if (state != ButtonState.RECORDING) {
            DiagnosticsLog.log("転写要求を無視: 録音中ではない ($state)")
            return
        }
        val refusal = transcriptionRefusal()
        if (refusal != null) {
            recorder.cancel()
            startForegroundCompat(recording = false)
            refuseTranscription(refusal)
            return
        }
        beginTranscribing { recorder.stop() }
    }

    private fun transcriptionRefusal(): RecordingAccessRules.Refusal? {
        val app = AnonenApp.from(this)
        val gate = app.entitlement.gate.value
        val refusal = RecordingAccessRules.refusalOf(gate, recordingAccount, app.cloudAuth.status().email)
        if (refusal != null) {
            DiagnosticsLog.log(
                "転写ブロック: $refusal ($gate " +
                    "アカウント一致=${recordingAccount == app.cloudAuth.status().email})",
            )
        }
        return refusal
    }

    private fun refuseTranscription(refusal: RecordingAccessRules.Refusal) {
        val app = AnonenApp.from(this)
        when (refusal) {
            RecordingAccessRules.Refusal.NEEDS_ACCESS -> {
                notifier.notifyAccessRequired()
                app.userNotices.post(getString(R.string.notice_access_required))
                fail(FeedbackEvent.ACCESS_REQUIRED)
            }

            RecordingAccessRules.Refusal.ACCOUNT_CHANGED ->
                notifier.notifyResult(
                    fail(FeedbackEvent.ACCOUNT_CHANGED).text,
                )
        }
    }

    private fun beginTranscribing(take: () -> RecordingResult?) {
        val app = AnonenApp.from(this)
        if (feedbackEnabled) sounds.playStop(feedbackVolume)
        if (hapticEnabled) view.buzz(Buzz.STOP)
        setState(ButtonState.TRANSCRIBING)
        startProcessingHints()

        app.transcribeCancel.begin()
        startForegroundCompat(recording = false, cancellableTranscribe = true)
        scope.launch(Dispatchers.Default) {
            try {
                runTranscriptionFlow(take)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnonenApp.from(this@FloatingButtonService).logThrowable("transcribe-flow", e)
                withContext(Dispatchers.Main) {
                    notifier.notifyResult(fail(FeedbackEvent.INTERNAL_ERROR).text)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (isRunning && state != ButtonState.RECORDING) {
                        startForegroundCompat(recording = false)
                    }
                }
            }
        }
    }

    private suspend fun runTranscriptionFlow(take: () -> RecordingResult?) {
        val stopT0 = android.os.SystemClock.elapsedRealtime()
        val result = take()
        val stopT1 = android.os.SystemClock.elapsedRealtime()
        if (result == null) {
            withContext(Dispatchers.Main) {
                DiagnosticsLog.log("録音が空（マイク入力なし）")
                notifier.notifyResult(fail(FeedbackEvent.NO_AUDIO).text)
            }
            return
        }
        val wav = result.processedWav
        if (wav == null) {
            val saved = preserveUndetectedAudio(result.rawWav)
            DiagnosticsLog.log("発話を検出できず raw=${result.rawWav.size}B 履歴保存=$saved")
            withContext(Dispatchers.Main) {
                setState(ButtonState.IDLE)
                notifier.notifyResult(say(FeedbackEvent.NO_SPEECH, audioKept = saved).text)
            }
            return
        }
        DiagnosticsLog.log(
            "録音停止 raw=${result.rawWav.size}B processed=${wav.size}B stop=${stopT1 - stopT0}ms",
        )
        DevFeaturesProvider.instance.onRecordingProcessed(
            this@FloatingButtonService,
            result.rawWav,
            wav,
        )
        when (val outcome = pipeline.run(wav)) {
            is PipelineOutcome.Delivered ->
                withContext(Dispatchers.Main) {
                    when (outcome.delivery) {
                        DeliveryOutcome.INJECTED -> setState(ButtonState.IDLE)
                        DeliveryOutcome.CLIPBOARD_FALLBACK -> {
                            setState(ButtonState.IDLE)
                            notifier.notifyResult(say(FeedbackEvent.COPIED).text)
                        }
                        DeliveryOutcome.BLOCKED_PASSWORD -> {
                            notifier.notifyResult(fail(FeedbackEvent.PASSWORD).text)
                        }
                    }
                }
            is PipelineOutcome.AsrFailed ->
                withContext(Dispatchers.Main) {
                    val fb = fail(FeedbackEvent.NOT_TRANSCRIBED, audioKept = outcome.audioSavedToHistory)
                    notifier.notifyResult(fb.text, outcome.error.userMessage)
                }
            is PipelineOutcome.EmptyTranscription ->
                withContext(Dispatchers.Main) {
                    setState(ButtonState.IDLE)
                    notifier.notifyResult(say(FeedbackEvent.NO_SPEECH, audioKept = outcome.audioSavedToHistory).text)
                }
            is PipelineOutcome.Cancelled ->
                withContext(Dispatchers.Main) {
                    notifier.notifyTranscribeCancelled(outcome.audioSavedToHistory)
                    setState(ButtonState.IDLE)
                    if (OverlayFeedback.onScreen(FeedbackEvent.TRANSCRIBE_CANCELLED, notifier.canPost())) {
                        say(FeedbackEvent.TRANSCRIBE_CANCELLED, audioKept = outcome.audioSavedToHistory)
                    }
                }
        }
    }

    private suspend fun preserveUndetectedAudio(rawWav: ByteArray): Boolean {
        val app = AnonenApp.from(this)
        val settings = app.settingsRepository.current()
        if (!settings.historyEnabled) return false
        return runCatching {
            app.historyRepository.add(
                TranscriptionPipeline.NO_SPEECH_HISTORY_TEXT,
                0L,
                settings.historyLimit,
                rawWav,
                null,
                settings.historyAudioLimit,
            )
        }.isSuccess
    }

    private fun setState(newState: ButtonState) {
        state = newState
        view.setState(newState)
        if (newState != ButtonState.TRANSCRIBING) {
            processingJob?.cancel()

            if (processingBubbleShown && ::bubble.isInitialized) bubble.detach()
            processingBubbleShown = false
        }
        if (newState != ButtonState.RECORDING) countdownJob?.cancel()

        if (newState != ButtonState.IDLE) dismissFirstUseHint()

        if (newState != ButtonState.RECORDING) dismissRecordingHint()
        updateOverlayVisibility()
    }

    private fun flashError() {
        setState(ButtonState.ERROR)
        scope.launch {
            delay(2500)
            if (state == ButtonState.ERROR) setState(ButtonState.IDLE)
        }
    }

    private fun fail(
        event: FeedbackEvent,
        audioKept: Boolean = false,
    ): Feedback {
        flashError()
        if (hapticEnabled) view.buzz(Buzz.ERROR)
        return say(event, audioKept = audioKept)
    }

    private fun say(
        event: FeedbackEvent,
        audioKept: Boolean = false,
        seconds: Int = 0,
    ): Feedback {
        val fb = OverlayFeedback.of(event, audioKept, OverlayVisibility.accessibilityConnected.value, seconds)
        show(fb)
        processingBubbleShown = event == FeedbackEvent.PROCESSING || event == FeedbackEvent.PROCESSING_SLOW
        firstUseHintShown = event == FeedbackEvent.FIRST_USE_HINT
        recordingHintShown = event in RECORDING_HINTS
        return fb
    }

    private fun show(fb: Feedback) {
        if (!::bubble.isInitialized || !::view.isInitialized) return

        firstUseHintShown = false
        recordingHintShown = false
        val action: (() -> Unit)? =
            when (fb.action) {
                FeedbackAction.NONE -> null
                FeedbackAction.CANCEL_TRANSCRIBE -> {
                    { cancelTranscription() }
                }
                FeedbackAction.OPEN_APP -> {
                    { openApp() }
                }
                FeedbackAction.CHOOSE_MODEL -> {
                    { openModelChoice() }
                }
                FeedbackAction.UNDO_DISCARD -> {
                    { undoDiscard() }
                }
            }
        bubble.show(
            fb.text,
            view.windowParams.x,
            view.windowParams.y,
            view.windowParams.width,
            fb.action.label,
            action,
        )
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { AnonenApp.from(this).logThrowable("open-app", it) }
    }

    private fun openModelChoice() {
        runCatching { startActivity(MainActivity.chooseModelIntent(this)) }
            .onFailure { AnonenApp.from(this).logThrowable("open-model-choice", it) }
    }

    private fun startProcessingHints() {
        processingJob?.cancel()
        processingJob =
            scope.launch {
                delay(PROCESSING_SLOW_MS)
                if (state == ButtonState.TRANSCRIBING) say(FeedbackEvent.PROCESSING_SLOW)
            }
    }

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob =
            scope.launch {
                var left = seconds
                while (left > 0 && state == ButtonState.RECORDING) {
                    view.setCountdown(left)
                    delay(1_000)
                    left--
                }
            }
    }

    private fun startForegroundCompat(
        recording: Boolean,
        cancellableTranscribe: Boolean = false,
    ): Boolean {
        val id = AnonenNotifier.SERVICE_NOTIFICATION_ID
        val notification =
            notifier.serviceNotification(
                showCancelRecording = recording,
                showCancelTranscribe = cancellableTranscribe,
            )
        return try {
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    val type =
                        if (recording) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        }
                    startForeground(id, notification, type)
                }
                Build.VERSION.SDK_INT >= 30 && recording -> {
                    startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
                else -> startForeground(id, notification)
            }
            true
        } catch (e: Exception) {
            AnonenApp.from(this).logThrowable(if (recording) "fgs-mic" else "fgs-start", e)
            false
        }
    }

    companion object {
        private const val INPUT_LINGER_MS = 200L

        private const val PROCESSING_SLOW_MS = 15_000L

        private const val FIRST_USE_HINT_DELAY_MS = 500L

        private const val RECORDING_HINT_DELAY_MS = 500L

        private const val ACTION_CANCEL_TRANSCRIBE =
            "net.anonen.app.action.CANCEL_TRANSCRIBE"

        private const val ACTION_CANCEL_RECORDING =
            "net.anonen.app.action.CANCEL_RECORDING"

        private const val ACTION_UNDO_DISCARD =
            "net.anonen.app.action.UNDO_DISCARD"

        private const val UNDO_HOLD_MS = 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingButtonService::class.java))
        }

        fun cancelTranscribeIntent(context: Context): Intent =
            Intent(context, FloatingButtonService::class.java)
                .setAction(ACTION_CANCEL_TRANSCRIBE)

        fun cancelRecordingIntent(context: Context): Intent =
            Intent(context, FloatingButtonService::class.java)
                .setAction(ACTION_CANCEL_RECORDING)

        fun undoDiscardIntent(context: Context): Intent =
            Intent(context, FloatingButtonService::class.java)
                .setAction(ACTION_UNDO_DISCARD)

        fun stop(context: Context) {
            isRunning = false
            context.stopService(Intent(context, FloatingButtonService::class.java))
        }
    }
}
