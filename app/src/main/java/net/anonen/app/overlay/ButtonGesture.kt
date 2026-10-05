package net.anonen.app.overlay

import kotlin.math.roundToInt

enum class TapAction {
    START,

    STOP,

    TOO_SOON,

    OFFER_CANCEL,
}

object ButtonGesture {
    const val MIN_RECORDING_MS = 800L

    const val DRAG_SLOP_DP = 16

    fun tapAction(
        state: ButtonState,
        msSinceRecordingStart: Long,
    ): TapAction =
        when (state) {
            ButtonState.IDLE, ButtonState.ERROR -> TapAction.START
            ButtonState.RECORDING ->
                if (msSinceRecordingStart < MIN_RECORDING_MS) TapAction.TOO_SOON else TapAction.STOP
            ButtonState.TRANSCRIBING -> TapAction.OFFER_CANCEL
        }

    fun dragSlopPx(
        systemSlopPx: Int,
        density: Float,
    ): Int = maxOf(systemSlopPx, (DRAG_SLOP_DP * density).roundToInt())
}
