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

    fun deliver(text: String): DeliveryOutcome {
        val service = AnonenAccessibilityService.instance
        val focus = service?.findInputFocus()
        if (focus != null) {
            try {
                if (isBlockedPasswordNode(focus.isPassword, focus.inputType)) {
                    DiagnosticsLog.log(
                        "パスワード欄と判定して配送しない: " +
                            "isPassword=${focus.isPassword} " +
                            "inputType=0x${focus.inputType.toString(16)}",
                    )
                    return DeliveryOutcome.BLOCKED_PASSWORD
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
            if (service.isCurrentInputPassword()) {
                DiagnosticsLog.log("パスワード欄と判定して配送しない: 入力接続の inputType")
                return DeliveryOutcome.BLOCKED_PASSWORD
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
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        copyToClipboard(text)
        val pasted =
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                .getOrDefault(false)
        if (pasted) {
            cleanUpClipboardLater(previous)
        } else {
            cleanUpClipboard(previous)
        }
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
        clipboard.setSensitivePlainText("Anonen", text)
    }

    private fun cleanUpClipboard(previous: ClipData?) {
        if (previous != null && runCatching { clipboard.setPrimaryClip(previous) }.isSuccess) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { clipboard.clearPrimaryClip() }
        } else {
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }

    private fun cleanUpClipboardLater(previous: ClipData?) {
        Handler(Looper.getMainLooper())
            .postDelayed({ cleanUpClipboard(previous) }, CLIP_CLEANUP_DELAY_MS)
    }

    private companion object {
        const val CLIP_CLEANUP_DELAY_MS = 1200L
    }
}
