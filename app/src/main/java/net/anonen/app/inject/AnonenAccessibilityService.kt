package net.anonen.app.inject

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.anonen.app.AnonenApp
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.OverlayVisibility
import net.anonen.app.overlay.AnonenNotifier
import net.anonen.app.overlay.FloatingButtonService
import net.anonen.app.ui.bringAppToFront
import net.anonen.app.ui.setupRunActive

class AnonenAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var wantsButton = false
    private val reviveTries = ArrayDeque<Long>()
    private var stoppedNoticeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayVisibility.setAccessibilityConnected(true)
        refreshInputVisibility()
        val app = AnonenApp.from(this)
        scope.launch {
            val s = app.settingsRepository.current()
            if (setupRunActive(s.accessibilitySetupStartedAt, System.currentTimeMillis())) {
                app.settingsRepository.update { it.copy(accessibilitySetupStartedAt = 0L) }
                bringAppToFront(this@AnonenAccessibilityService)
            }
        }
        scope.launch { app.settingsRepository.settings.collect { wantsButton = it.autoStartOnBoot } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> refreshInputVisibility()
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        if (instance === this) instance = null
        OverlayVisibility.setAccessibilityConnected(false)
        OverlayVisibility.setInputActive(false)
        super.onDestroy()
    }

    fun findInputFocus(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun commitTextThroughInputMethod(text: CharSequence): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            commitTextThroughInputMethodApi33(text)
        } else {
            false
        }

    fun currentInputPackageName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentInputPackageNameApi33()
        } else {
            null
        }

    fun isCurrentInputPassword(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isCurrentInputPasswordApi33()
        } else {
            false
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun commitTextThroughInputMethodApi33(text: CharSequence): Boolean {
        return runCatching {
            val method = inputMethod ?: return@runCatching false
            if (!method.currentInputStarted) return@runCatching false
            val connection = method.currentInputConnection ?: return@runCatching false
            connection.commitText(text, 1, null)
            true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun currentInputPackageNameApi33(): String? =
        runCatching { inputMethod?.currentInputEditorInfo?.packageName }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isCurrentInputPasswordApi33(): Boolean =
        runCatching {
            val method = inputMethod ?: return@runCatching false
            val info = method.currentInputEditorInfo ?: return@runCatching false

            isBlockedPasswordInputType(info.inputType)
        }.getOrDefault(false)

    fun refreshInputVisibility() {
        val active = runCatching { isImeVisible() }.getOrDefault(false)
        OverlayVisibility.setInputActive(active)
        if (active) maybeReviveButton()
    }

    private fun maybeReviveButton() {
        if (!wantsButton || FloatingButtonService.isRunning || !Settings.canDrawOverlays(this)) return
        val now = SystemClock.elapsedRealtime()
        if (!reviveAllowed(reviveTries, now)) return
        reviveTries.addLast(now)
        while (reviveTries.size > REVIVE_MAX) reviveTries.removeFirst()
        runCatching { FloatingButtonService.start(this) }.onFailure {
            DiagnosticsLog.log("ボタンを出し直せない: ${it.javaClass.simpleName}")
            if (stoppedNoticeAt == 0L || now - stoppedNoticeAt > DAY_MS) {
                stoppedNoticeAt = now
                AnonenNotifier(this).notifyButtonStopped()
            }
        }
    }

    private fun isImeVisible(): Boolean = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    companion object {
        @Volatile
        var instance: AnonenAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null

        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

internal const val REVIVE_MAX = 3
internal const val REVIVE_WINDOW_MS = 10 * 60 * 1000L

internal fun reviveAllowed(
    tries: Collection<Long>,
    now: Long,
): Boolean = tries.count { now - it < REVIVE_WINDOW_MS } < REVIVE_MAX
