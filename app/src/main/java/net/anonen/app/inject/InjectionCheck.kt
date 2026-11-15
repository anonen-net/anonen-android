package net.anonen.app.inject

internal enum class InjectionRoute {
    SET_TEXT,

    PASTE,

    INPUT_CONNECTION,

    COPY,
}

internal enum class InjectionCheck {
    CHANGED,

    UNCHANGED,

    UNREADABLE,
}

internal class FieldSnapshot private constructor(
    private val textLength: Int?,
    private val textHash: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    val readable: Boolean get() = textLength != null

    fun sameTextAs(other: FieldSnapshot): Boolean = textLength == other.textLength && textHash == other.textHash

    val hasSelection: Boolean get() = selectionStart >= 0 && selectionEnd >= 0

    override fun toString(): String = "FieldSnapshot(readable=$readable)"

    companion object {
        fun of(
            text: CharSequence?,
            showingHint: Boolean,
            selectionStart: Int,
            selectionEnd: Int,
        ): FieldSnapshot {
            val content = if (showingHint) "" else text?.toString()
            return FieldSnapshot(content?.length, content?.hashCode() ?: 0, selectionStart, selectionEnd)
        }
    }
}

internal object InjectionVerifier {
    val SCHEDULE_MS: List<Long> = (0L..1000L step 100L).toList()

    val NO_WAIT_MS: List<Long> = listOf(0L)

    fun cursorCounts(route: InjectionRoute): Boolean = route != InjectionRoute.SET_TEXT

    fun judge(
        before: FieldSnapshot?,
        after: FieldSnapshot?,
        cursorCounts: Boolean = true,
    ): InjectionCheck {
        if (before == null || after == null) return InjectionCheck.UNREADABLE
        val cursorMoved =
            before.hasSelection && after.hasSelection &&
                (before.selectionStart != after.selectionStart || before.selectionEnd != after.selectionEnd)
        return when {
            before.readable && after.readable && !before.sameTextAs(after) -> InjectionCheck.CHANGED
            !before.readable && after.readable -> InjectionCheck.CHANGED
            cursorCounts && cursorMoved -> InjectionCheck.CHANGED
            before.readable && after.readable -> InjectionCheck.UNCHANGED
            else -> InjectionCheck.UNREADABLE
        }
    }

    fun await(
        before: FieldSnapshot?,
        read: () -> FieldSnapshot?,
        sleep: (Long) -> Boolean,
        schedule: List<Long> = SCHEDULE_MS,
        cursorCounts: Boolean = true,
    ): InjectionCheck {
        if (before == null) return InjectionCheck.UNREADABLE
        var now = 0L
        var seen: InjectionCheck? = null
        for (at in schedule) {
            if (at > now) {
                if (!sleep(at - now)) return InjectionCheck.UNREADABLE
                now = at
            }
            val after = read() ?: continue
            val check = judge(before, after, cursorCounts)
            if (check != InjectionCheck.UNCHANGED) return check
            seen = check
        }
        return seen ?: InjectionCheck.UNREADABLE
    }

    fun logLine(
        route: InjectionRoute,
        check: InjectionCheck?,
        standardField: Boolean?,
    ): String {
        val verdict =
            when (check) {
                null -> "なし"
                InjectionCheck.CHANGED -> "入った"
                InjectionCheck.UNCHANGED -> "変わらない→コピーを残す"
                InjectionCheck.UNREADABLE -> "読めない→成功を信じる"
            }
        val standard = standardField?.let { if (it) "yes" else "no" } ?: "?"
        return "配送の経路=$route 確かめ=$verdict 標準の欄=$standard"
    }
}
