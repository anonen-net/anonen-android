package net.anonen.app.ui

internal const val SETUP_RUN_MS = 10 * 60 * 1000L

internal fun setupRunActive(
    startedAtMs: Long,
    nowMs: Long,
): Boolean = startedAtMs > 0 && nowMs >= startedAtMs && nowMs - startedAtMs <= SETUP_RUN_MS

private val IN_APP_STEPS =
    setOf(HomePrimaryAction.GRANT_MICROPHONE, HomePrimaryAction.CHOOSE_MODEL, HomePrimaryAction.SHOW_BUTTON)

internal fun autoAdvanceTo(
    launched: HomePrimaryAction?,
    now: HomePrimaryAction,
    runActive: Boolean,
): HomePrimaryAction? =
    if (runActive && launched != null && now.ordinal > launched.ordinal && now in IN_APP_STEPS) now else null

internal enum class MicDenial {
    EXPLAIN,

    OPEN_SETTINGS,
}

internal fun micDenialNext(rationale: Boolean): MicDenial =
    if (rationale) MicDenial.EXPLAIN else MicDenial.OPEN_SETTINGS
