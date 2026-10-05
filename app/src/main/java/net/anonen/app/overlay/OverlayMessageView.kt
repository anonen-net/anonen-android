package net.anonen.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import net.anonen.app.core.DiagnosticsLog
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class OverlayMessageView(
    context: Context,
    private val windowManager: WindowManager,
) : LinearLayout(context) {
    var darkTheme: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { detach() }
    private val bubble = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
    private val actionFace = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
    private val message = TextView(context)
    private val actionView = TextView(context)
    private var onAction: (() -> Unit)? = null
    private var attached = false

    private val windowParams: WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )

    init {
        orientation = VERTICAL
        background = bubble
        elevation = dp(3).toFloat()

        setPadding(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))

        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        message.textSize = TEXT_SP
        message.maxLines = MAX_LINES
        message.ellipsize = TextUtils.TruncateAt.END
        addView(message)
        actionView.textSize = TEXT_SP
        actionView.setTypeface(actionView.typeface, Typeface.BOLD)
        actionView.gravity = Gravity.CENTER
        actionView.background = actionFace
        actionView.setPadding(dp(16), dp(8), dp(16), dp(8))

        actionView.filterTouchesWhenObscured = true
        actionView.setOnClickListener {
            val run = onAction
            detach()
            run?.invoke()
        }
        addView(
            actionView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )

        setOnClickListener { detach() }
    }

    fun show(
        text: String,
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        message.text = text
        val hasAction = actionLabel != null && action != null
        actionView.text = actionLabel.orEmpty()
        actionView.visibility = if (hasAction) VISIBLE else GONE
        onAction = if (hasAction) action else null

        windowParams.flags = bubbleWindowFlags(windowParams.flags, hasAction)
        applyColors()
        place(buttonX, buttonY, buttonSize)
        handler.removeCallbacks(hide)
        try {
            if (attached) {
                windowManager.updateViewLayout(this, windowParams)
            } else {
                windowManager.addView(this, windowParams)
                attached = true
            }
        } catch (e: IllegalStateException) {
            attachFailed(e)
            return
        } catch (e: WindowManager.BadTokenException) {
            attachFailed(e)
            return
        }
        handler.postDelayed(hide, bubbleDurationMs(text, hasAction))
    }

    fun detach() {
        handler.removeCallbacks(hide)
        onAction = null
        if (!attached) return
        attached = false
        runCatching { windowManager.removeView(this) }
    }

    private fun attachFailed(e: Exception) {
        attached = false
        DiagnosticsLog.log("吹き出しを出せない: ${e.javaClass.simpleName}")
    }

    private fun applyColors() {
        val face = if (darkTheme) FloatingButtonView.DARK_FACE else FloatingButtonView.PAPER_WHITE
        val ink = if (darkTheme) FloatingButtonView.PAPER_WHITE else INK_LIGHT
        val ring = if (darkTheme) FloatingButtonView.RING_DARK else FloatingButtonView.RING_LIGHT
        bubble.setColor(face)
        bubble.setStroke(dp(2), ring)
        message.setTextColor(ink)
        val green = if (darkTheme) FloatingButtonView.DARK_GREEN else FloatingButtonView.ACCENT_GREEN
        val onGreen = if (darkTheme) FloatingButtonView.DARK_ON_GREEN else FloatingButtonView.PAPER_WHITE
        actionFace.setColor(green)
        actionView.setTextColor(onGreen)
    }

    private fun place(
        buttonX: Int,
        buttonY: Int,
        buttonSize: Int,
    ) {
        val anchor =
            bubbleAnchor(
                screenWidth = resources.displayMetrics.widthPixels,
                buttonX = buttonX,
                buttonSize = buttonSize,
                gap = dp(8),
                minWidth = dp(MIN_WIDTH_DP),
                maxWidthCap = dp(MAX_WIDTH_DP),
            )
        windowParams.gravity =
            Gravity.TOP or (if (anchor.alignEnd) Gravity.END else Gravity.START)
        windowParams.x = anchor.x

        message.maxWidth = (anchor.maxWidth - dp(PAD_H_DP * 2)).coerceAtLeast(dp(80))
        windowParams.y =
            if (anchor.stacked) {
                measure(
                    MeasureSpec.makeMeasureSpec(anchor.maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                stackedBubbleY(
                    screenHeight = resources.displayMetrics.heightPixels,
                    buttonY = buttonY,
                    buttonSize = buttonSize,
                    bubbleHeight = measuredHeight,
                    gap = dp(8),
                )
            } else {
                buttonY.coerceAtLeast(0)
            }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TEXT_SP = 14f
        const val PAD_H_DP = 12
        const val PAD_V_DP = 8
        const val MAX_LINES = 5
        const val MIN_WIDTH_DP = 160
        const val MAX_WIDTH_DP = 320
        const val INK_LIGHT = 0xFF1C1B19.toInt()
    }
}

fun bubbleDurationMs(
    text: String,
    hasAction: Boolean,
): Long {
    val base = (BUBBLE_BASE_MS + BUBBLE_PER_CHAR_MS * text.length).coerceIn(BUBBLE_MIN_MS, BUBBLE_MAX_MS)
    return if (hasAction) base + BUBBLE_ACTION_EXTRA_MS else base
}

private const val BUBBLE_BASE_MS = 1_000L
private const val BUBBLE_PER_CHAR_MS = 150L
private const val BUBBLE_MIN_MS = 3_000L
private const val BUBBLE_MAX_MS = 6_000L
private const val BUBBLE_ACTION_EXTRA_MS = 2_000L

internal data class BubbleAnchor(
    val alignEnd: Boolean,
    val x: Int,
    val maxWidth: Int,
    val stacked: Boolean = false,
)

internal fun bubbleAnchor(
    screenWidth: Int,
    buttonX: Int,
    buttonSize: Int,
    gap: Int,
    minWidth: Int,
    maxWidthCap: Int,
): BubbleAnchor {
    val edge = gap
    val spaceOnLeft = buttonX - gap - edge
    val spaceOnRight = screenWidth - (buttonX + buttonSize + gap) - edge

    val alignEnd = spaceOnLeft >= spaceOnRight
    val space = if (alignEnd) spaceOnLeft else spaceOnRight

    if (space < minWidth) {
        return BubbleAnchor(
            alignEnd = false,
            x = gap,
            maxWidth = (screenWidth - gap - edge).coerceAtLeast(1),
            stacked = true,
        )
    }

    val width = space.coerceAtMost(maxWidthCap)
    return if (alignEnd) {
        BubbleAnchor(
            alignEnd = true,
            x = (screenWidth - buttonX + gap).coerceAtLeast(0),
            maxWidth = width,
        )
    } else {
        BubbleAnchor(
            alignEnd = false,
            x = (buttonX + buttonSize + gap).coerceAtLeast(0),
            maxWidth = width,
        )
    }
}

internal fun bubbleWindowFlags(
    flags: Int,
    hasAction: Boolean,
): Int =
    if (hasAction) {
        flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    } else {
        flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

internal fun stackedBubbleY(
    screenHeight: Int,
    buttonY: Int,
    buttonSize: Int,
    bubbleHeight: Int,
    gap: Int,
): Int {
    val below = buttonY + buttonSize + gap
    if (below + bubbleHeight <= screenHeight) return below
    val above = buttonY - gap - bubbleHeight
    if (above >= 0) return above
    val roomBelow = screenHeight - below
    val roomAbove = buttonY - gap
    return if (roomBelow >= roomAbove) {
        below.coerceAtMost((screenHeight - bubbleHeight).coerceAtLeast(0))
    } else {
        0
    }
}
