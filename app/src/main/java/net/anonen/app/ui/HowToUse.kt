package net.anonen.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.anonen.app.R
import net.anonen.app.core.OverlayVisibility
import net.anonen.app.overlay.AnonenNotifier
import net.anonen.app.overlay.RecordingCancelGesture
import net.anonen.app.settings.AnonenSettings

internal fun tryItStopHint(pushToTalk: Boolean): String = if (pushToTalk) "離すと止まります" else "丸いボタンをもう一度押すと止まり、長押しでやめます"

internal data class HowToStep(
    val text: String,
    val caption: String? = null,
    val buttonMark: Boolean = false,
)

internal enum class HowToIcon { TOUCH_APP, NOTIFICATIONS, KEYBOARD, CONTENT_PASTE }

internal data class HowToNote(
    val icon: HowToIcon,
    val text: String,
    val caption: String? = null,
)

internal data class HowToUse(
    val steps: List<HowToStep>,
    val notes: List<HowToNote>,
)

internal fun howToUse(
    pushToTalk: Boolean,
    accessibilityOn: Boolean,
    notificationShown: Boolean,
): HowToUse =
    HowToUse(
        steps =
            listOf(
                HowToStep(if (pushToTalk) "ボタンを押したまま" else "ボタンを押す", "録音が始まります", buttonMark = true),
                HowToStep("話す"),
                HowToStep(if (pushToTalk) "離す" else "もう一度押す", "止まって、文字になります"),
            ),
        notes =
            buildList {
                if (RecordingCancelGesture.longPressDeletes(pushToTalk)) {
                    add(HowToNote(HowToIcon.TOUCH_APP, "途中でやめる: 録音中にボタンを長押し", "「元に戻す」で戻せます"))
                }
                if (notificationShown) add(HowToNote(HowToIcon.NOTIFICATIONS, "通知からもやめられます"))
                add(
                    if (accessibilityOn) {
                        HowToNote(HowToIcon.KEYBOARD, "文字は、そのとき使っているアプリの入力欄に入ります")
                    } else {
                        HowToNote(HowToIcon.CONTENT_PASTE, "文字はコピーされるので、入力欄を長押しして貼り付けます")
                    },
                )
            },
    )

private val STEP_SHAPE = RoundedCornerShape(8.dp)
private val STEP_NUMBER_SIZE = 24.dp
private val MARK_SIZE = 20.dp
private val MARK_ICON_SIZE = 12.dp
private val NOTE_ROW_MIN_HEIGHT = 40.dp
private val NOTE_ICON_SIZE = 20.dp

@Composable
internal fun HowToUseSection(settings: AnonenSettings) {
    val context = LocalContext.current
    val accessibilityOn by OverlayVisibility.accessibilityConnected.collectAsState()
    var notificationShown by remember { mutableStateOf(AnonenNotifier.serviceActionsVisible(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationShown = AnonenNotifier.serviceActionsVisible(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val content =
        howToUse(
            pushToTalk = settings.pushToTalk,
            accessibilityOn = accessibilityOn,
            notificationShown = notificationShown,
        )
    SettingsGroup("使い方") {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content.steps.forEachIndexed { index, step -> StepCard(number = index + 1, step = step) }

            HowToCard(modifier = Modifier.padding(top = 4.dp)) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    content.notes.forEach { note -> NoteRow(note) }
                }
            }
        }
    }
}

@Composable
private fun HowToCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = STEP_SHAPE,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun StepCard(
    number: Int,
    step: HowToStep,
) {
    HowToCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepNumber(number)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        step.text,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (step.buttonMark) {
                        Spacer(Modifier.width(8.dp))
                        RoundMicMark()
                    }
                }
                step.caption?.let { caption ->
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepNumber(number: Int) {
    val style = MaterialTheme.typography.labelMedium
    val textHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier =
            Modifier
                .size(maxOf(STEP_NUMBER_SIZE, textHeight + 6.dp))
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = style,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun RoundMicMark() {
    Box(
        modifier =
            Modifier
                .size(MARK_SIZE)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MARK_ICON_SIZE),
        )
    }
}

@Composable
private fun NoteRow(note: HowToNote) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = NOTE_ROW_MIN_HEIGHT)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(STEP_NUMBER_SIZE), contentAlignment = Alignment.Center) {
            Icon(
                notePainter(note.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(NOTE_ICON_SIZE),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            note.caption?.let { caption ->
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun notePainter(icon: HowToIcon): Painter =
    when (icon) {
        HowToIcon.TOUCH_APP -> painterResource(R.drawable.ic_touch_app)
        HowToIcon.NOTIFICATIONS -> rememberVectorPainter(Icons.Outlined.Notifications)
        HowToIcon.KEYBOARD -> painterResource(R.drawable.ic_keyboard)
        HowToIcon.CONTENT_PASTE -> painterResource(R.drawable.ic_content_paste)
    }
