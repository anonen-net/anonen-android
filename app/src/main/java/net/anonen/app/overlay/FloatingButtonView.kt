package net.anonen.app.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import net.anonen.app.R
import net.anonen.app.settings.AnonenSettings
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ButtonState { IDLE, RECORDING, TRANSCRIBING, ERROR }

@SuppressLint("ViewConstructor")
class FloatingButtonView(
    context: Context,
    private val windowManager: WindowManager,
) : FrameLayout(context) {
    var onTap: (() -> Unit)? = null

    var onLongPressCancel: (() -> Unit)? = null

    var onPressStart: (() -> Unit)? = null
    var onPressEnd: (() -> Unit)? = null
    var onPressCancel: (() -> Unit)? = null

    var onPositionChanged: ((x: Int, y: Int) -> Unit)? = null

    var pushToTalk: Boolean = false

    private var idleAlpha: Float = AnonenSettings.DEFAULT_BUTTON_ALPHA
    private var currentState: ButtonState = ButtonState.IDLE

    private var cancelFlashing = false

    var darkTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                applyStateVisual()
            }
        }

    private val icon = ImageView(context)

    private val countdown =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            visibility = GONE
        }
    private val faceDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    private val dragSlop =
        ButtonGesture.dragSlopPx(ViewConfiguration.get(context).scaledTouchSlop, resources.displayMetrics.density)

    private var pulse: ObjectAnimator? = null
    private var rotate: ObjectAnimator? = null

    private val cancelFlashEnd =
        Runnable {
            cancelFlashing = false
            applyStateVisual()
        }

    private val cancelGesture = RecordingCancelGesture()
    private val longPressRunnable = Runnable { fireLongPressCancel() }

    val windowParams: WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(DEFAULT_SIZE_DP),
            dp(DEFAULT_SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    init {

        background = faceDrawable
        elevation = dp(3).toFloat()

        filterTouchesWhenObscured = true

        isHapticFeedbackEnabled = true
        icon.setImageResource(R.drawable.ic_mic)
        addView(
            icon,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            countdown,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        applyIconPadding(DEFAULT_SIZE_DP)
        setState(ButtonState.IDLE)
    }

    fun applyAppearance(
        sizeDp: Int,
        viewAlpha: Float,
        posX: Int,
        posY: Int,
    ) {
        val clampedDp = sizeDp.coerceIn(AnonenSettings.BUTTON_SIZE_MIN_DP, AnonenSettings.BUTTON_SIZE_MAX_DP)
        windowParams.width = dp(clampedDp)
        windowParams.height = dp(clampedDp)
        applyIconPadding(clampedDp)
        idleAlpha = viewAlpha.coerceIn(AnonenSettings.BUTTON_ALPHA_MIN, AnonenSettings.BUTTON_ALPHA_MAX)

        applyStateVisual()
        val (screenW, screenH) = screenSize()
        val maxX = (screenW - windowParams.width).coerceAtLeast(0)
        val maxY = (screenH - windowParams.height).coerceAtLeast(0)
        windowParams.x =
            if (posX >= 0) {
                posX.coerceIn(0, maxX)
            } else {
                ((screenW * 0.78f) - (windowParams.width / 2f)).roundToInt().coerceIn(0, maxX)
            }
        windowParams.y =
            if (posY >= 0) posY.coerceIn(0, maxY) else (screenH / 3).coerceIn(0, maxY)
        if (isAttachedToWindow) windowManager.updateViewLayout(this, windowParams)
    }

    private fun applyIconPadding(sizeDp: Int) {
        val pad = dp((sizeDp * ICON_PADDING_RATIO).roundToInt())
        icon.setPadding(pad, pad, pad, pad)
        countdown.textSize = sizeDp * COUNTDOWN_TEXT_RATIO
    }

    fun setCountdown(seconds: Int?) {
        if (seconds == null) {
            countdown.visibility = GONE
            icon.visibility = VISIBLE
        } else {
            countdown.text = seconds.toString()
            countdown.visibility = VISIBLE
            icon.visibility = INVISIBLE
        }
    }

    fun buzz(kind: Buzz) {
        performHapticFeedback(hapticConstant(kind, Build.VERSION.SDK_INT))
    }

    fun setState(state: ButtonState) {
        currentState = state
        clearCancelFlash()
        setCountdown(null)
        stopAnimations()
        applyStateVisual()
        when (state) {
            ButtonState.RECORDING -> startPulse()
            ButtonState.TRANSCRIBING -> startRotate()
            else -> Unit
        }
    }

    private fun applyStateVisual() {
        val face = if (darkTheme) DARK_FACE else PAPER_WHITE
        val green = if (darkTheme) DARK_GREEN else ACCENT_GREEN
        val onGreen = if (darkTheme) DARK_ON_GREEN else PAPER_WHITE
        val red = if (darkTheme) DARK_RED else ERROR_RED
        val onRed = if (darkTheme) DARK_ON_RED else PAPER_WHITE

        val faceColor: Int
        val iconColor: Int
        val iconRes: Int
        val label: String
        when (currentState) {
            ButtonState.IDLE -> {
                faceColor = withAlpha(face, idleAlpha)
                iconColor = green
                iconRes = R.drawable.ic_mic
                label = "押して話す"
            }
            ButtonState.RECORDING -> {
                faceColor = green
                iconColor = onGreen
                iconRes = R.drawable.ic_stop
                label = "録音中"
            }
            ButtonState.TRANSCRIBING -> {
                faceColor = face
                iconColor = green
                iconRes = R.drawable.ic_sync
                label = "文字起こし中"
            }
            ButtonState.ERROR -> {
                faceColor = red
                iconColor = onRed
                iconRes = R.drawable.ic_error
                label = "できませんでした"
            }
        }

        faceDrawable.setColor(if (cancelFlashing) red else faceColor)

        faceDrawable.setStroke(dp(2), if (darkTheme) RING_DARK else RING_LIGHT)
        icon.setImageResource(iconRes)
        icon.setColorFilter(if (cancelFlashing) onRed else iconColor)
        countdown.setTextColor(if (cancelFlashing) onRed else iconColor)
        contentDescription = label
        alpha = 1f
    }

    fun flashCancel() {
        cancelFlashing = true
        applyStateVisual()
        removeCallbacks(cancelFlashEnd)
        postDelayed(cancelFlashEnd, CANCEL_FLASH_MS)
    }

    private fun clearCancelFlash() {
        removeCallbacks(cancelFlashEnd)
        cancelFlashing = false
    }

    private fun withAlpha(
        color: Int,
        alpha: Float,
    ): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun release() {
        removeCallbacks(longPressRunnable)
        clearCancelFlash()
        stopAnimations()
    }

    private fun startPulse() {
        pulse =
            ObjectAnimator.ofPropertyValuesHolder(
                icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.28f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.28f),
            ).apply {
                duration = 520
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
    }

    private fun startRotate() {
        rotate =
            ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
                duration = 850
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
    }

    private fun stopAnimations() {
        pulse?.cancel()
        pulse = null
        rotate?.cancel()
        rotate = null
        icon.scaleX = 1f
        icon.scaleY = 1f
        icon.rotation = 0f
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamX = 0
    private var startParamY = 0
    private var dragging = false
    private var pressing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startParamX = windowParams.x
                startParamY = windowParams.y
                dragging = false

                if (cancelGesture.onDown(pushToTalk, currentState == ButtonState.RECORDING)) {
                    postDelayed(longPressRunnable, LONG_PRESS_CANCEL_MS)
                }
                if (pushToTalk) {
                    pressing = true
                    onPressStart?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                    dragging = true

                    cancelGesture.onDrag()
                    removeCallbacks(longPressRunnable)
                    if (pressing) {
                        pressing = false
                        onPressCancel?.invoke()
                    }
                }
                if (dragging) {
                    moveTo((startParamX + dx).roundToInt(), (startParamY + dy).roundToInt())
                    windowManager.updateViewLayout(this, windowParams)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cancelled = cancelGesture.onUp()
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    clampToScreen()
                    onPositionChanged?.invoke(windowParams.x, windowParams.y)
                } else if (pressing) {
                    pressing = false
                    onPressEnd?.invoke()
                } else if (!pushToTalk && !cancelled) {
                    onTap?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelGesture.onUp()
                removeCallbacks(longPressRunnable)
                if (pressing) {
                    pressing = false
                    onPressCancel?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fireLongPressCancel() {
        if (!cancelGesture.onLongPressTimeout(currentState == ButtonState.RECORDING)) return
        onLongPressCancel?.invoke()
    }

    private fun clampToScreen() {
        moveTo(windowParams.x, windowParams.y)
        windowManager.updateViewLayout(this, windowParams)
    }

    private fun moveTo(
        x: Int,
        y: Int,
    ) {
        val (screenW, screenH) = screenSize()
        val maxX = (screenW - windowParams.width).coerceAtLeast(0)
        val maxY = (screenH - windowParams.height).coerceAtLeast(0)
        windowParams.x = x.coerceIn(0, maxX)
        windowParams.y = y.coerceIn(0, maxY)
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    internal companion object {
        const val DEFAULT_SIZE_DP = AnonenSettings.DEFAULT_BUTTON_SIZE_DP

        const val LONG_PRESS_CANCEL_MS = 600L

        const val ICON_PADDING_RATIO = 0.22f

        const val COUNTDOWN_TEXT_RATIO = 0.36f

        const val CANCEL_FLASH_MS = 220L

        const val HAIRLINE_LIGHT = 0x400A0A0A
        const val RING_LIGHT = 0xB30A0A0A.toInt()
        const val ACCENT_GREEN = 0xFF16A34A.toInt()
        const val PAPER_WHITE = 0xFFFAFAF9.toInt()
        const val ERROR_RED = 0xFFDC2626.toInt()

        const val HAIRLINE_DARK = 0x40FAFAF9
        const val RING_DARK = 0xB3FAFAF9.toInt()
        const val DARK_FACE = 0xFF383634.toInt()
        const val DARK_GREEN = 0xFF22C55E.toInt()
        const val DARK_ON_GREEN = 0xFF052E16.toInt()
        const val DARK_RED = 0xFFF87171.toInt()
        const val DARK_ON_RED = 0xFF450A0A.toInt()
    }
}
