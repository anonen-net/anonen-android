package net.anonen.app.inject

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.setSensitivePlainText

enum class DeliveryOutcome { INJECTED, CLIPBOARD_FALLBACK, BLOCKED_PASSWORD }

class TextInjector(private val context: Context) {
    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val cleanup =
        ClipboardCleanup(
            read = { runCatching { clipboard.primaryClip }.getOrNull() },
            holds = { clip, text -> clip.isOurs(text) },
            restore = { previous -> runCatching { clipboard.setPrimaryClip(previous) }.isSuccess },
            clear = ::clearClipboard,
            post = { delayMs, block -> Handler(Looper.getMainLooper()).postDelayed({ block() }, delayMs) },
        )

    fun deliver(
        text: String,
        allowPasswordFields: Boolean = false,
        target: AccessibilityNodeInfo? = null,
    ): DeliveryOutcome {
        val service = AnonenAccessibilityService.instance
        val focus = service?.findInputFocus()
        if (target != null) {
            val why = "isPassword=${target.isPassword} inputType=0x${target.inputType.toString(16)}"
            when (passwordVerdict(allowPasswordFields, isBlockedPasswordNode(target.isPassword, target.inputType))) {
                PasswordVerdict.BLOCK -> {
                    DiagnosticsLog.log("止めた時点の欄がパスワード欄なので配送しない: $why")
                    @Suppress("DEPRECATION")
                    focus?.recycle()
                    return DeliveryOutcome.BLOCKED_PASSWORD
                }
                PasswordVerdict.ALLOWED_BY_SETTING ->
                    DiagnosticsLog.log("止めた時点の欄はパスワード欄と判定したが、設定に従って入れる: $why")
                PasswordVerdict.NOT_PASSWORD -> Unit
            }
            val match = TextInjectionPolicy.targetMatch(true, focus != null, focus != null && focus == target)
            if (match == TargetMatch.MOVED) {
                DiagnosticsLog.log("止めた時点の欄から移っていたので、入れずにコピーだけ")
                @Suppress("DEPRECATION")
                focus?.recycle()
                copyToClipboard(text)
                return DeliveryOutcome.CLIPBOARD_FALLBACK
            }
        }
        if (focus != null) {
            try {
                val why = "isPassword=${focus.isPassword} inputType=0x${focus.inputType.toString(16)}"
                when (passwordVerdict(allowPasswordFields, isBlockedPasswordNode(focus.isPassword, focus.inputType))) {
                    PasswordVerdict.BLOCK -> {
                        DiagnosticsLog.log("パスワード欄と判定して配送しない: $why")
                        return DeliveryOutcome.BLOCKED_PASSWORD
                    }
                    PasswordVerdict.ALLOWED_BY_SETTING ->
                        DiagnosticsLog.log("パスワード欄と判定したが、設定に従って入れる: $why")
                    PasswordVerdict.NOT_PASSWORD -> Unit
                }
                val outcome = injectIntoNode(focus, text)
                if (outcome != null) {
                    return outcome
                }
            } finally {
                @Suppress("DEPRECATION")
                focus.recycle()
            }
        }
        if (service != null) {
            when (passwordVerdict(allowPasswordFields, service.isCurrentInputPassword())) {
                PasswordVerdict.BLOCK -> {
                    DiagnosticsLog.log("パスワード欄と判定して配送しない: 入力接続の inputType")
                    return DeliveryOutcome.BLOCKED_PASSWORD
                }
                PasswordVerdict.ALLOWED_BY_SETTING ->
                    DiagnosticsLog.log("パスワード欄と判定したが、設定に従って入れる: 入力接続の inputType")
                PasswordVerdict.NOT_PASSWORD -> Unit
            }
            if (service.commitTextThroughInputMethod(text)) {
                return DeliveryOutcome.INJECTED
            }
        }
        copyToClipboard(text)
        return DeliveryOutcome.CLIPBOARD_FALLBACK
    }

    private fun injectIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
    ): DeliveryOutcome? {
        when (
            TextInjectionPolicy.planFor(
                className = node.className,
                packageName = node.packageName,
                hasSetText = node.hasAction(AccessibilityNodeInfo.ACTION_SET_TEXT),
                hasPaste = node.hasAction(AccessibilityNodeInfo.ACTION_PASTE),
            )
        ) {
            InjectionPlan.INPUT_CONNECTION_COMMIT -> {
                if (AnonenAccessibilityService.instance?.commitTextThroughInputMethod(text) == true) {
                    return DeliveryOutcome.INJECTED
                }
            }
            InjectionPlan.STANDARD_SET_TEXT -> {
                if (setTextIntoNode(node, text)) return DeliveryOutcome.INJECTED
                if (node.hasAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return pasteIntoNode(node, text)
                }
            }
            InjectionPlan.ACCESSIBILITY_PASTE -> return pasteIntoNode(node, text)
            InjectionPlan.CLIPBOARD_FALLBACK -> return null
        }
        return null
    }

    private fun pasteIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
    ): DeliveryOutcome? {
        val previous = cleanup.beginPaste()
        clipboard.setSensitivePlainText(CLIP_LABEL, text)
        val pasted =
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                .getOrDefault(false)

        cleanup.afterPaste(previous, text, pasted)
        return if (pasted) DeliveryOutcome.INJECTED else null
    }

    private fun setTextIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
        if (!node.hasAction(AccessibilityNodeInfo.ACTION_SET_TEXT)) return false

        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

        var selStart = node.textSelectionStart
        var selEnd = node.textSelectionEnd
        if (selStart < 0 || selStart > current.length) selStart = current.length
        if (selEnd < selStart || selEnd > current.length) selEnd = selStart

        val newText = current.substring(0, selStart) + text + current.substring(selEnd)
        val setArgs =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText,
                )
            }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false

        val cursor = selStart + text.length
        val selArgs =
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }

        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return true
    }

    private fun AccessibilityNodeInfo.hasAction(id: Int): Boolean = actionList.any { it.id == id }

    private fun copyToClipboard(text: String) {
        cleanup.keep()
        clipboard.setSensitivePlainText(CLIP_LABEL, text)
    }

    private fun ClipData.isOurs(text: String): Boolean =
        description?.label?.toString() == CLIP_LABEL &&
            itemCount == 1 &&
            getItemAt(0)?.text?.toString() == text

    private fun clearClipboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { clipboard.clearPrimaryClip() }
        } else {
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }

    private companion object {
        const val CLIP_LABEL = "Anonen"
    }
}

internal class ClipboardCleanup<C : Any>(
    private val read: () -> C?,
    private val holds: (C, String) -> Boolean,
    private val restore: (C) -> Boolean,
    private val clear: () -> Unit,
    private val post: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    private class Pending<C>(val previous: C?, val text: String)

    private val lock = Any()
    private var pending: Pending<C>? = null

    fun beginPaste(): C? =
        synchronized(lock) {
            val current = read()
            val waiting = pending
            pending = null
            when {
                waiting == null -> current

                current == null || holds(current, waiting.text) -> waiting.previous

                else -> current
            }
        }

    fun afterPaste(
        previous: C?,
        text: String,
        pasted: Boolean,
    ) {
        if (!pasted) {
            synchronized(lock) { cleanUp(previous, text) }
            return
        }
        val mine = Pending(previous, text)
        synchronized(lock) { pending = mine }
        post(DELAY_MS) {
            synchronized(lock) {
                if (pending === mine) {
                    pending = null
                    cleanUp(previous, text)
                }
            }
        }
    }

    fun keep() {
        synchronized(lock) { pending = null }
    }

    private fun cleanUp(
        previous: C?,
        text: String,
    ) {
        val current = read()
        if (current != null && !holds(current, text)) return
        if (previous != null && restore(previous)) return
        clear()
    }

    companion object {
        const val DELAY_MS = 1200L
    }
}
