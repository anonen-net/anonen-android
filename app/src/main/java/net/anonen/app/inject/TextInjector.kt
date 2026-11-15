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
                DiagnosticsLog.log(InjectionVerifier.logLine(InjectionRoute.COPY, null, null))
                return DeliveryOutcome.CLIPBOARD_FALLBACK
            }
        }

        try {
            if (focus != null) {
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
                val outcome = injectIntoNode(focus, text, allowPasswordFields)
                if (outcome != null) {
                    return outcome
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
                val outcome = commitThroughInputMethod(service, focus, text)
                if (outcome != null) {
                    return outcome
                }
            }
            copyToClipboard(text)
            DiagnosticsLog.log(InjectionVerifier.logLine(InjectionRoute.COPY, null, focus?.isStandard()))
            return DeliveryOutcome.CLIPBOARD_FALLBACK
        } finally {
            @Suppress("DEPRECATION")
            focus?.recycle()
        }
    }

    private fun injectIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
        allowPasswordFields: Boolean,
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
                val before = node.snapshot()
                if (AnonenAccessibilityService.instance?.commitTextThroughInputMethod(text) == true) {
                    return settle(verify(InjectionRoute.INPUT_CONNECTION, node, before, last = true), text)
                }
            }
            InjectionPlan.STANDARD_SET_TEXT -> {
                val before = node.snapshot()
                val sent = setTextIntoNode(node, text)
                if (sent != null) {
                    val check = setTextThenOthers(node, before, text, sent, allowPasswordFields)
                    return settle(check, text)
                }
                if (node.hasAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return pasteAndVerify(node, text, last = true)?.let { settle(it, text) }
                }
            }
            InjectionPlan.ACCESSIBILITY_PASTE -> {
                return pasteAndVerify(node, text, last = true)?.let { settle(it, text) }
            }
            InjectionPlan.CLIPBOARD_FALLBACK -> return null
        }
        return null
    }

    private fun setTextThenOthers(
        node: AccessibilityNodeInfo,
        before: FieldSnapshot?,
        text: String,
        sent: SetTextSent,
        allowPasswordFields: Boolean,
    ): InjectionCheck {
        val service = AnonenAccessibilityService.instance
        val inputMethodReady = service != null && service.isInputMethodReady()

        val verdict =
            if (inputMethodReady) {
                passwordVerdict(allowPasswordFields, service.isCurrentInputPassword())
            } else {
                PasswordVerdict.NOT_PASSWORD
            }
        val alternate =
            InjectionChain.alternateAfterSetText(
                inputMethodReady = inputMethodReady,
                inputMethodPasswordBlocked = verdict == PasswordVerdict.BLOCK,
                hasPaste = node.hasAction(AccessibilityNodeInfo.ACTION_PASTE),
            )
        val port =
            object : SetTextFollowUp {
                override fun verify(
                    route: InjectionRoute,
                    before: FieldSnapshot?,
                    last: Boolean,
                ) = verify(route, node, before, last)

                override fun logRoute(
                    route: InjectionRoute,
                    check: InjectionCheck?,
                ) = DiagnosticsLog.log(InjectionVerifier.logLine(route, check, node.isStandard()))

                override fun logNotSent(route: InjectionRoute) =
                    DiagnosticsLog.log(InjectionVerifier.notSentLine(route, node.isStandard()))

                override fun logMoved() = DiagnosticsLog.log("届け始めたときの欄から移っていた（か、いまの欄が取れない）ので、代わりは入れずにコピーだけ")

                override fun logPasswordAllowed() = DiagnosticsLog.log("パスワード欄と判定したが、設定に従って入れる: 入力接続の inputType")

                override fun moveCursor() = moveCursor(node, sent.cursor)

                override fun sameFieldFocused(): Boolean {
                    val now = service?.findInputFocus() ?: return false
                    val same = now == node
                    @Suppress("DEPRECATION")
                    now.recycle()
                    return same
                }

                override fun setSelection(
                    start: Int,
                    end: Int,
                ) = setSelection(node, start, end)

                override fun read() = node.takeIf { runCatching { it.refresh() }.getOrDefault(false) }?.snapshot()

                override fun sleep(ms: Long) = sleepUnlessInterrupted(ms)

                override fun commit() = service?.commitTextThroughInputMethod(text) == true

                override fun paste(before: FieldSnapshot?) = pasteAndVerify(node, text, last = true, before = before)
            }
        return InjectionChain.afterSetText(
            before,
            sent.sameText,
            alternate,
            port,
            passwordAllowedBySetting = verdict == PasswordVerdict.ALLOWED_BY_SETTING,
        )
    }

    private fun pasteAndVerify(
        node: AccessibilityNodeInfo,
        text: String,
        last: Boolean,
        before: FieldSnapshot? = node.snapshot(),
    ): InjectionCheck? {
        val previous = cleanup.beginPaste()
        clipboard.setSensitivePlainText(CLIP_LABEL, text)
        val pasted =
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
                .getOrDefault(false)

        cleanup.afterPaste(previous, text, pasted)
        return if (pasted) verify(InjectionRoute.PASTE, node, before, last) else null
    }

    private fun commitThroughInputMethod(
        service: AnonenAccessibilityService,
        node: AccessibilityNodeInfo?,
        text: String,
    ): DeliveryOutcome? {
        val before = node?.snapshot()
        if (!service.commitTextThroughInputMethod(text)) return null
        return settle(verify(InjectionRoute.INPUT_CONNECTION, node, before, last = true), text)
    }

    private fun verify(
        route: InjectionRoute,
        node: AccessibilityNodeInfo?,
        before: FieldSnapshot?,
        last: Boolean,
    ): InjectionCheck {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val schedule = InjectionVerifier.scheduleFor(route, last, onMain)
        val check =
            if (schedule == null) {
                InjectionCheck.UNREADABLE
            } else {
                InjectionVerifier.await(
                    before = before,
                    read = { node?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }?.snapshot() },
                    sleep = ::sleepUnlessInterrupted,
                    schedule = schedule,
                    cursorCounts = InjectionVerifier.cursorCounts(route),
                )
            }
        DiagnosticsLog.log(InjectionVerifier.logLine(route, check, node?.isStandard()))
        return check
    }

    private fun settle(
        check: InjectionCheck,
        text: String,
    ): DeliveryOutcome {
        if (check == InjectionCheck.UNCHANGED) {
            copyToClipboard(text)
            DiagnosticsLog.log(InjectionVerifier.logLine(InjectionRoute.COPY, null, null))
            return DeliveryOutcome.CLIPBOARD_FALLBACK
        }
        return DeliveryOutcome.INJECTED
    }

    private fun sleepUnlessInterrupted(ms: Long): Boolean =
        try {
            Thread.sleep(ms)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun AccessibilityNodeInfo.snapshot(): FieldSnapshot? =
        runCatching { FieldSnapshot.of(text, isShowingHintText, textSelectionStart, textSelectionEnd) }.getOrNull()

    private fun AccessibilityNodeInfo.isStandard(): Boolean = TextInjectionPolicy.isStandardEditText(className)

    private fun setTextIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
    ): SetTextSent? {
        if (!node.hasAction(AccessibilityNodeInfo.ACTION_SET_TEXT)) return null

        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val plan = InjectionChain.planSetText(current, node.textSelectionStart, node.textSelectionEnd, text)
        val setArgs =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    plan.newText,
                )
            }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return null
        return SetTextSent(cursor = plan.cursor, sameText = plan.sameText)
    }

    private class SetTextSent(
        val cursor: Int,
        val sameText: Boolean,
    )

    private fun moveCursor(
        node: AccessibilityNodeInfo,
        cursor: Int,
    ) = setSelection(node, cursor, cursor)

    private fun setSelection(
        node: AccessibilityNodeInfo,
        start: Int,
        end: Int,
    ) {
        val selArgs =
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
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
