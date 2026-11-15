package net.anonen.app.inject

internal interface SetTextFollowUp {
    fun verify(
        route: InjectionRoute,
        before: FieldSnapshot?,
        last: Boolean,
    ): InjectionCheck

    fun logRoute(
        route: InjectionRoute,
        check: InjectionCheck?,
    )

    fun logNotSent(route: InjectionRoute)

    fun logMoved()

    fun logPasswordAllowed()

    fun moveCursor()

    fun sameFieldFocused(): Boolean

    fun setSelection(
        start: Int,
        end: Int,
    )

    fun read(): FieldSnapshot?

    fun sleep(ms: Long): Boolean

    fun commit(): Boolean

    fun paste(before: FieldSnapshot?): InjectionCheck?
}

internal object InjectionChain {
    val RESTORE_SCHEDULE_MS: List<Long> = listOf(0L, 50L, 100L, 150L, 200L)

    fun alternateAfterSetText(
        inputMethodReady: Boolean,
        inputMethodPasswordBlocked: Boolean,
        hasPaste: Boolean,
    ): InjectionRoute? =
        when {
            inputMethodPasswordBlocked -> null
            inputMethodReady -> InjectionRoute.INPUT_CONNECTION
            hasPaste -> InjectionRoute.PASTE
            else -> null
        }

    class SetTextPlan(
        val newText: String,
        val cursor: Int,
        val sameText: Boolean,
    )

    fun planSetText(
        current: String,
        selStart: Int,
        selEnd: Int,
        text: String,
    ): SetTextPlan {
        val start = if (selStart < 0 || selStart > current.length) current.length else selStart
        val end = if (selEnd < start || selEnd > current.length) start else selEnd
        val newText = current.substring(0, start) + text + current.substring(end)
        return SetTextPlan(newText, start + text.length, newText == current)
    }

    internal fun setTextLanded(
        before: FieldSnapshot?,
        now: FieldSnapshot?,
    ): Boolean = InjectionVerifier.judge(before, now, cursorCounts = false) == InjectionCheck.CHANGED

    fun afterSetText(
        before: FieldSnapshot?,
        unverifiable: Boolean,
        alternate: InjectionRoute?,
        port: SetTextFollowUp,
        passwordAllowedBySetting: Boolean = false,
    ): InjectionCheck {
        if (unverifiable) {
            port.logRoute(InjectionRoute.SET_TEXT, InjectionCheck.UNREADABLE)
            port.moveCursor()
            return InjectionCheck.UNREADABLE
        }
        val first = port.verify(InjectionRoute.SET_TEXT, before, last = alternate == null)
        if (first != InjectionCheck.UNCHANGED) {
            port.moveCursor()
            return first
        }
        if (alternate == null) return InjectionCheck.UNCHANGED
        if (!port.sameFieldFocused()) return moved(port)
        val pre = port.read()
        if (setTextLanded(before, pre)) return landedLate(port)
        val base =
            if (before != null && before.hasSelection) {
                port.setSelection(before.selectionStart, before.selectionEnd)
                val waited = awaitSelection(port, before.selectionStart, before.selectionEnd)
                if (waited.interrupted) {
                    port.logRoute(InjectionRoute.SET_TEXT, InjectionCheck.UNREADABLE)
                    return InjectionCheck.UNREADABLE
                }
                waited.snapshot
            } else {
                pre
            }
        if (setTextLanded(before, base)) return landedLate(port)
        if (!port.sameFieldFocused()) return moved(port)
        return when (alternate) {
            InjectionRoute.INPUT_CONNECTION -> {
                if (passwordAllowedBySetting) port.logPasswordAllowed()
                if (port.commit()) {
                    port.verify(InjectionRoute.INPUT_CONNECTION, base, last = true)
                } else {
                    port.logNotSent(InjectionRoute.INPUT_CONNECTION)
                    InjectionCheck.UNCHANGED
                }
            }
            InjectionRoute.PASTE ->
                port.paste(base) ?: InjectionCheck.UNCHANGED.also { port.logNotSent(InjectionRoute.PASTE) }
            InjectionRoute.SET_TEXT, InjectionRoute.COPY -> InjectionCheck.UNCHANGED
        }
    }

    private fun moved(port: SetTextFollowUp): InjectionCheck {
        port.logMoved()
        return InjectionCheck.UNCHANGED
    }

    private fun landedLate(port: SetTextFollowUp): InjectionCheck {
        port.logRoute(InjectionRoute.SET_TEXT, InjectionCheck.CHANGED)
        port.moveCursor()
        return InjectionCheck.CHANGED
    }

    internal class Awaited(
        val snapshot: FieldSnapshot?,
        val interrupted: Boolean,
    )

    internal fun awaitSelection(
        port: SetTextFollowUp,
        start: Int,
        end: Int,
    ): Awaited {
        var now = 0L
        var last: FieldSnapshot? = null
        for (at in RESTORE_SCHEDULE_MS) {
            if (at > now) {
                if (!port.sleep(at - now)) return Awaited(last, interrupted = true)
                now = at
            }
            val snap = port.read() ?: continue
            last = snap
            if (snap.selectionStart == start && snap.selectionEnd == end) return Awaited(snap, interrupted = false)
        }
        return Awaited(last, interrupted = false)
    }
}
