package net.anonen.app.overlay

import android.view.HapticFeedbackConstants

enum class FeedbackEvent {
    NO_AUDIO,

    NO_SPEECH,

    COPIED,

    PASSWORD,

    NOT_TRANSCRIBED,

    TRANSCRIBE_CANCELLED,

    RECORDING_DISCARDED,

    ACCESS_REQUIRED,

    NO_MODEL,

    LOCAL_MODEL_MISSING,

    MODEL_RETIRED,

    ACCOUNT_CHANGED,

    MIC_FAILED,

    MIC_DENIED,

    INTERNAL_ERROR,

    LIMIT_WARNING,

    LIMIT_STOPPED,

    LOW_REMAINING,

    PROCESSING,

    PROCESSING_SLOW,

    ACCESSIBILITY_OFF,

    BUTTON_STOPPED,

    FIRST_USE_HINT,

    RECORDING_HINT,

    RECORDING_HINT_PTT,
}

enum class FeedbackAction(
    val label: String?,
) {
    NONE(null),
    CANCEL_TRANSCRIBE("やめる"),
    OPEN_APP("アプリを開く"),

    CHOOSE_MODEL("モデルを選ぶ"),
    UNDO_DISCARD("元に戻す"),
}

data class Feedback(
    val text: String,
    val action: FeedbackAction = FeedbackAction.NONE,
    val error: Boolean = false,
)

object OverlayFeedback {
    private const val KEPT = "録音は記録に残しました"

    fun of(
        event: FeedbackEvent,
        audioKept: Boolean = false,
        accessibilityOn: Boolean = true,
        seconds: Int = 0,
    ): Feedback =
        when (event) {
            FeedbackEvent.NO_AUDIO -> Feedback("マイクから音が入りませんでした", error = true)
            FeedbackEvent.NO_SPEECH ->
                Feedback(if (audioKept) "声が聞こえませんでした。$KEPT" else "声が聞こえませんでした")
            FeedbackEvent.COPIED -> Feedback(if (accessibilityOn) "入れられなかったので、コピーしました" else "コピーしました")
            FeedbackEvent.PASSWORD -> Feedback("パスワードの欄には入れません", error = true)
            FeedbackEvent.NOT_TRANSCRIBED ->
                Feedback(if (audioKept) "文字起こしできませんでした。$KEPT" else "文字起こしできませんでした", error = true)
            FeedbackEvent.TRANSCRIBE_CANCELLED -> Feedback(if (audioKept) "やめました。$KEPT" else "やめました")
            FeedbackEvent.RECORDING_DISCARDED ->
                Feedback("録音を削除しました", if (audioKept) FeedbackAction.UNDO_DISCARD else FeedbackAction.NONE)
            FeedbackEvent.ACCESS_REQUIRED ->
                Feedback("ログインと契約が必要です", FeedbackAction.OPEN_APP, error = true)
            FeedbackEvent.NO_MODEL ->
                Feedback("モデルが選ばれていません", FeedbackAction.CHOOSE_MODEL, error = true)
            FeedbackEvent.LOCAL_MODEL_MISSING ->
                Feedback("モデルが入っていません", FeedbackAction.OPEN_APP, error = true)
            FeedbackEvent.MODEL_RETIRED ->
                Feedback("このモデルはもう使えません", FeedbackAction.OPEN_APP, error = true)
            FeedbackEvent.ACCOUNT_CHANGED -> Feedback("ログインが変わったので、この声は送りませんでした", error = true)
            FeedbackEvent.MIC_FAILED -> Feedback("マイクを開けませんでした", error = true)
            FeedbackEvent.MIC_DENIED ->
                Feedback("マイクを使えません", FeedbackAction.OPEN_APP, error = true)
            FeedbackEvent.INTERNAL_ERROR -> Feedback("うまくいきませんでした", error = true)
            FeedbackEvent.LIMIT_WARNING -> Feedback("あと $seconds 秒で止まります")
            FeedbackEvent.LIMIT_STOPPED -> Feedback("長さの上限で止めました")
            FeedbackEvent.LOW_REMAINING -> Feedback("使える時間があと少しです")
            FeedbackEvent.PROCESSING -> Feedback("文字起こし中…", FeedbackAction.CANCEL_TRANSCRIBE)
            FeedbackEvent.PROCESSING_SLOW -> Feedback("時間がかかっています", FeedbackAction.CANCEL_TRANSCRIBE)
            FeedbackEvent.ACCESSIBILITY_OFF -> Feedback("文字が自動で入らなくなりました", FeedbackAction.OPEN_APP)
            FeedbackEvent.BUTTON_STOPPED -> Feedback("ボタンが止まっています")
            FeedbackEvent.FIRST_USE_HINT -> Feedback("このボタンを押すと録音が始まります")
            FeedbackEvent.RECORDING_HINT -> Feedback("もう一度押すと止まります。やめるときは長押し")
            FeedbackEvent.RECORDING_HINT_PTT -> Feedback("離すと止まります")
        }
}

const val FIRST_USE_HINT_TIMES = 2

fun OverlayFeedback.showsFirstUseHint(
    shownCount: Int,
    state: ButtonState,
    hasModel: Boolean,
): Boolean = shownCount < FIRST_USE_HINT_TIMES && state == ButtonState.IDLE && hasModel

const val RECORDING_HINT_TIMES = 2

val RECORDING_HINTS: Set<FeedbackEvent> = setOf(FeedbackEvent.RECORDING_HINT, FeedbackEvent.RECORDING_HINT_PTT)

fun OverlayFeedback.recordingHint(
    shownCount: Int,
    state: ButtonState,
    pushToTalk: Boolean,
): FeedbackEvent? =
    when {
        shownCount >= RECORDING_HINT_TIMES || state != ButtonState.RECORDING -> null
        pushToTalk -> FeedbackEvent.RECORDING_HINT_PTT
        else -> FeedbackEvent.RECORDING_HINT
    }

fun OverlayFeedback.onScreen(
    event: FeedbackEvent,
    notificationsAllowed: Boolean,
): Boolean =
    when (event) {
        FeedbackEvent.RECORDING_DISCARDED, FeedbackEvent.TRANSCRIBE_CANCELLED -> !notificationsAllowed
        else -> true
    }

enum class Buzz { START, STOP, ERROR }

fun hapticConstant(
    buzz: Buzz,
    sdk: Int,
): Int =
    when (buzz) {
        Buzz.START ->
            when {
                sdk >= 34 -> HapticFeedbackConstants.TOGGLE_ON
                sdk >= 30 -> HapticFeedbackConstants.CONFIRM
                else -> HapticFeedbackConstants.VIRTUAL_KEY
            }
        Buzz.STOP -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.VIRTUAL_KEY
        Buzz.ERROR -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
