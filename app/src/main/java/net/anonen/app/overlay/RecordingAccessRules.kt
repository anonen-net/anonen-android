package net.anonen.app.overlay

import net.anonen.app.core.AccessControl.AccessGate

object RecordingAccessRules {
    enum class Abort {
        NONE,

        DISCARD_RECORDING,

        CANCEL_TRANSCRIBE,
    }

    fun abortOn(
        gate: AccessGate,
        state: ButtonState,
    ): Abort =
        if (gate == AccessGate.ALLOWED) {
            Abort.NONE
        } else {
            when (state) {
                ButtonState.RECORDING -> Abort.DISCARD_RECORDING
                ButtonState.TRANSCRIBING -> Abort.CANCEL_TRANSCRIBE
                ButtonState.IDLE, ButtonState.ERROR -> Abort.NONE
            }
        }

    fun canTranscribe(
        gate: AccessGate,
        startedBy: String?,
        current: String?,
    ): Boolean = refusalOf(gate, startedBy, current) == null

    enum class Refusal {
        NEEDS_ACCESS,

        ACCOUNT_CHANGED,
    }

    fun refusalOf(
        gate: AccessGate,
        startedBy: String?,
        current: String?,
    ): Refusal? =
        when {
            gate != AccessGate.ALLOWED -> Refusal.NEEDS_ACCESS
            startedBy != current -> Refusal.ACCOUNT_CHANGED
            else -> null
        }
}
