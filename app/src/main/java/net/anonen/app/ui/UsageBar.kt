package net.anonen.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.anonen.app.R

private val BAR_HEIGHT = 6.dp

private val HATCH_STEP = 5.dp
private val HATCH_STROKE = 1.dp
private val EDGE_STROKE = 1.dp

private const val LOCKED_ALPHA = 0.35f

@Composable
internal fun UsageBar(
    label: String,
    usedS: Int,
    capS: Int,
    fullCapS: Int = capS,
    resetLabel: String? = null,
) {
    val g = usageBarGeometry(usedS, capS, fullCapS)
    val barColor = if (g.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "残り ${g.remainingPercent}%",
                style = MaterialTheme.typography.bodySmall,
                color = if (g.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        if (g.isTrial) {
            TrialBar(g, barColor)
            Spacer(Modifier.height(2.dp))

            Text(
                "お試し中に使えるのはここまで。契約すると全部使えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                progress = { g.usedFraction },
                modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        if (resetLabel != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.usage_resets_at, resetLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrialBar(
    g: UsageBarGeometry,
    barColor: Color,
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val edge = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(percent = 50))
            .progressSemantics(g.usedRatio.coerceIn(0f, 1f)),
    ) {
        val usableX = size.width * g.usableFraction
        val usedX = size.width * g.usedFraction
        drawRect(track, size = Size(usableX, size.height))
        drawRect(
            track.copy(alpha = LOCKED_ALPHA),
            topLeft = Offset(usableX, 0f),
            size = Size(size.width - usableX, size.height),
        )
        drawHatch(track, fromX = usableX)
        if (usedX > 0f) {
            drawRoundRect(barColor, size = Size(usedX, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        drawLine(
            edge,
            start = Offset(usableX, 0f),
            end = Offset(usableX, size.height),
            strokeWidth = EDGE_STROKE.toPx(),
        )
    }
}

private fun DrawScope.drawHatch(
    color: Color,
    fromX: Float,
) {
    val step = HATCH_STEP.toPx()
    val stroke = HATCH_STROKE.toPx()
    clipRect(left = fromX) {
        var x = fromX - size.height
        while (x < size.width) {
            drawLine(color, start = Offset(x, size.height), end = Offset(x + size.height, 0f), strokeWidth = stroke)
            x += step
        }
    }
}
