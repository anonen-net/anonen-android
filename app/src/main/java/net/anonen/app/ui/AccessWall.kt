package net.anonen.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.overlay.ButtonState
import net.anonen.app.overlay.FloatingButtonView
import net.anonen.app.settings.AnonenSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun LoginScreen(
    app: AnonenApp,
    onSignedIn: () -> Unit,
    onViewHistory: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val demoAbove = fitsDemoAbove(DEMO_ABOVE_FORM_MIN_DP)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                "あのねん",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (demoAbove) {
                Spacer(Modifier.height(16.dp))

                WallDemo()
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "話すだけで、いま見ている画面に文字が入ります。",
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))

            Text(
                "ご利用には、まず下のサイトでのお申し込みが必要です。",
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            SiteAddress()
            Spacer(Modifier.height(24.dp))

            CloudSignInForm(
                onSignedIn = { onSignedIn() },
                auth = app.cloudAuth,
                scope = scope,
            )
            if (!demoAbove) {
                Spacer(Modifier.height(32.dp))
                WallDemo()
            }

            Spacer(Modifier.height(24.dp))

            TextButton(onClick = onViewHistory) {
                Text("記録を見る")
            }

            WallSupportSection(app = app)
        }
    }
}

@Composable
private fun WallSupportSection(app: AnonenApp) {
    val settings by app.settingsRepository.settings.collectAsState(initial = null)

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(
        "うまくいかないとき",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(10.dp))
    SupportActions(app, settings)
    Spacer(Modifier.height(24.dp))
}

@Composable
internal fun CheckingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.height(28.dp).width(28.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "契約を確かめています…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SubscriptionRequiredScreen(
    app: AnonenApp,
    onViewHistory: () -> Unit,
    checkFailed: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val account by app.cloudUsage.account.collectAsState()
    var rechecking by remember { mutableStateOf(false) }
    val email = remember { app.cloudAuth.status().email ?: "" }
    val statusLabel =
        subscriptionStatusLabel(account?.subscriptionStatus ?: app.entitlement.lastKnownStatus())

    val demoAbove = fitsDemoAbove(DEMO_ABOVE_BUTTON_MIN_DP)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                if (checkFailed) "契約を確かめられません" else "ご契約が必要です",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            if (email.isNotBlank()) {
                Text(
                    email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            statusLabel?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                if (checkFailed) {
                    "ネットにつながったら「もう一度確かめる」を押してください。"
                } else {
                    "下のサイトで契約したら、「もう一度確かめる」を押してください。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            SiteAddress()
            Spacer(Modifier.height(24.dp))
            if (demoAbove) {
                WallDemo()
                Spacer(Modifier.height(24.dp))
            }

            Button(
                onClick = {
                    rechecking = true
                    scope.launch {
                        val minSpin = launch { delay(MIN_RECHECK_SPIN_MS) }
                        withContext(Dispatchers.IO) { runCatching { app.cloudUsage.fetch() } }
                        app.entitlement.refresh()
                        minSpin.join()
                        rechecking = false
                    }
                },
                enabled = !rechecking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (rechecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("もう一度確かめる")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onViewHistory, modifier = Modifier.fillMaxWidth()) {
                Text("記録を見る")
            }
            Spacer(Modifier.height(4.dp))
            var confirmLogout by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
                Text("別のメールアドレスでログイン", color = MaterialTheme.colorScheme.error)
            }
            if (confirmLogout) {
                LogoutConfirmDialog(
                    onKeep = { confirmLogout = false },
                    onLogout = {
                        confirmLogout = false
                        app.cloudAuth.logout()
                        app.cloudUsage.clear()
                        app.entitlement.onUserLogout()
                    },
                )
            }
            if (!demoAbove) {
                Spacer(Modifier.height(24.dp))
                WallDemo()
            }

            WallSupportSection(app = app)

            Spacer(Modifier.height(24.dp))
            Text(
                "アカウントを削除するには",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    ACCOUNT_DELETION_URL.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun HistoryWallScreen(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onBack) { Text("← 戻る") }
                Text("記録", style = MaterialTheme.typography.titleMedium)
            }
            Box(modifier = Modifier.weight(1f)) {
                HistoryScreen()
            }
        }
    }
}

private const val MIN_RECHECK_SPIN_MS = 1_000L

@Composable
private fun SiteAddress() {
    SelectionContainer(modifier = Modifier.fillMaxWidth()) {
        Text(
            "anonen.net",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WallDemo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var still by remember { mutableStateOf(!animatorsEnabled(context)) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        var t = 0.0
        while (true) {
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            still = scale <= 0f
            if (still) {
                delay(STILL_RECHECK_MS)
                last = withFrameMillis { it }
                continue
            }
            val now = withFrameMillis { it }
            t += (now - last) / scale.toDouble()
            last = now
            elapsedMs = t.toLong()
        }
    }

    val frame by remember { derivedStateOf { if (still) WallDemoTimeline.FINAL else WallDemoTimeline.at(elapsedMs) } }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = WALL_DEMO_DESCRIPTION }) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = DEMO_BUTTON_SIZE / 2)
                    .background(MaterialTheme.colorScheme.surface, DEMO_FRAME_SHAPE)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, DEMO_FRAME_SHAPE)
                    .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 10.dp + DEMO_BUTTON_SIZE / 2),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DemoBubble(DEMO_AI_MESSAGE, mine = false)
            DemoBubble(DEMO_MY_MESSAGE, mine = true)
            Spacer(Modifier.height(2.dp))
            DemoInput(frame)
        }
        DemoButton(frame, dark, Modifier.align(Alignment.BottomEnd).padding(bottom = DEMO_BUTTON_BOTTOM))
    }
}

@Composable
private fun DemoBubble(
    text: String,
    mine: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (mine) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth(DEMO_BUBBLE_MAX_WIDTH)
                    .wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
                    .background(if (mine) colors.primaryContainer else colors.surfaceVariant, DEMO_BUBBLE_SHAPE)
                    .border(1.dp, if (mine) colors.primaryContainer else colors.outlineVariant, DEMO_BUBBLE_SHAPE)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DemoInput(frame: WallDemoFrame) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = MaterialTheme.colorScheme
    val caretColor = colors.primary
    Text(
        DEMO_TRANSCRIPT,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurface,
        onTextLayout = { layout = it },
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.5.dp, if (frame.textShown) colors.primary else colors.outlineVariant, DEMO_INPUT_SHAPE)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .drawWithContent {
                    if (frame.textShown) drawContent()
                    val l = layout
                    if (frame.caretOn && l != null) {
                        val r = l.getCursorRect(if (frame.textShown) DEMO_TRANSCRIPT.length else 0)
                        drawRect(caretColor, topLeft = Offset(r.left, r.top), size = Size(2.dp.toPx(), r.height))
                    }
                },
    )
}

@Composable
private fun DemoButton(
    frame: WallDemoFrame,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val look = FloatingButtonView.look(frame.state, dark, AnonenSettings.DEFAULT_BUTTON_ALPHA)
    Box(
        modifier =
            modifier
                .size(DEMO_BUTTON_SIZE)
                .graphicsLayer {
                    scaleX = frame.buttonScale
                    scaleY = frame.buttonScale
                }.background(Color(look.face), CircleShape)
                .border(FloatingButtonView.RING_DP.dp, Color(FloatingButtonView.ringColor(dark)), CircleShape)
                .padding(DEMO_BUTTON_SIZE * FloatingButtonView.ICON_PADDING_RATIO),
    ) {
        Icon(
            painterResource(look.iconRes),
            contentDescription = null,
            tint = Color(look.icon),
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = frame.iconScale
                    scaleY = frame.iconScale
                    rotationZ = frame.iconRotation
                },
        )
    }
}

@Composable
private fun fitsDemoAbove(minHeightDp: Int): Boolean =
    demoFitsAbove(LocalConfiguration.current.screenHeightDp, LocalDensity.current.fontScale, minHeightDp)

private fun animatorsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

internal fun demoFitsAbove(
    screenHeightDp: Int,
    fontScale: Float,
    minHeightDp: Int,
): Boolean = screenHeightDp >= minHeightDp * fontScale.coerceAtLeast(1f)

internal data class WallDemoFrame(
    val state: ButtonState,
    val textShown: Boolean,
    val buttonScale: Float,
    val iconScale: Float,
    val iconRotation: Float,
    val caretOn: Boolean,
)

internal object WallDemoTimeline {
    const val IDLE_MS = 1_200L
    const val RECORD_MS = 3_000L
    const val TRANSCRIBE_MS = 1_200L
    const val SHOWN_MS = 3_000L
    const val CYCLE_MS = IDLE_MS + RECORD_MS + TRANSCRIBE_MS + SHOWN_MS

    const val PRESS_MS = 180L
    const val PRESS_SCALE = 0.9f

    const val CARET_BLINK_MS = 500L

    val FINAL =
        WallDemoFrame(
            state = ButtonState.IDLE,
            textShown = true,
            buttonScale = 1f,
            iconScale = 1f,
            iconRotation = 0f,
            caretOn = true,
        )

    fun at(elapsedMs: Long): WallDemoFrame {
        val t = Math.floorMod(elapsedMs, CYCLE_MS)
        val recordAt = IDLE_MS
        val transcribeAt = recordAt + RECORD_MS
        val shownAt = transcribeAt + TRANSCRIBE_MS
        val state =
            when {
                t < recordAt -> ButtonState.IDLE
                t < transcribeAt -> ButtonState.RECORDING
                t < shownAt -> ButtonState.TRANSCRIBING
                else -> ButtonState.IDLE
            }
        return WallDemoFrame(
            state = state,
            textShown = t >= shownAt,
            buttonScale = pressScale(t, recordAt) * pressScale(t, transcribeAt),
            iconScale = if (state == ButtonState.RECORDING) pulse(t - recordAt) else 1f,
            iconRotation =
                if (state == ButtonState.TRANSCRIBING) {
                    360f * ((t - transcribeAt) % FloatingButtonView.ROTATE_MS) / FloatingButtonView.ROTATE_MS
                } else {
                    0f
                },
            caretOn = Math.floorMod(elapsedMs, 2 * CARET_BLINK_MS) < CARET_BLINK_MS,
        )
    }

    private fun pressScale(
        t: Long,
        pressAt: Long,
    ): Float {
        val from = pressAt - PRESS_MS
        if (t < from || t >= pressAt) return 1f
        val p = (t - from).toDouble() / PRESS_MS
        return (1.0 - (1.0 - PRESS_SCALE) * sin(PI * p)).toFloat()
    }

    private fun pulse(sinceRecordMs: Long): Float {
        val half = FloatingButtonView.PULSE_MS
        val phase = sinceRecordMs % (2 * half)
        val x = if (phase < half) phase.toDouble() / half else (2 * half - phase).toDouble() / half
        val eased = cos((x + 1) * PI) / 2 + 0.5
        return (1.0 + (FloatingButtonView.PULSE_SCALE - 1.0) * eased).toFloat()
    }
}

private const val DEMO_AI_MESSAGE = "売買契約書を読み込みました。気になる点はありますか？"
private const val DEMO_MY_MESSAGE = "ありがとう、ひとつ聞かせて"

internal const val DEMO_TRANSCRIPT = "この物件の瑕疵は、売主の過失がなくても責任を問えるんでしょうか。"

internal const val WALL_DEMO_DESCRIPTION = "使い方の例。丸いボタンを押して話し、もう一度押すと、話した文が入力欄に入ります"

private val DEMO_FRAME_SHAPE = RoundedCornerShape(12.dp)
private val DEMO_BUBBLE_SHAPE = RoundedCornerShape(10.dp)
private val DEMO_INPUT_SHAPE = RoundedCornerShape(8.dp)
private const val DEMO_BUBBLE_MAX_WIDTH = 0.85f

private val DEMO_BUTTON_SIZE = 40.dp
private val DEMO_BUTTON_BOTTOM = 14.dp

private const val STILL_RECHECK_MS = 500L

internal const val DEMO_ABOVE_BUTTON_MIN_DP = 600

internal const val DEMO_ABOVE_FORM_MIN_DP = 720
