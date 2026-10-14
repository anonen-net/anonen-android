package net.anonen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import net.anonen.app.cloud.ModelDataPolicy

internal const val PREVIEW_LABEL = "プレビュー版"

internal const val FILLER_REDUCTION_LABEL = "フィラー削減"

@Composable
internal fun ModelChipsRow(
    preview: Boolean,
    fillerReduction: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!preview && !fillerReduction) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (preview) PreviewChip()
        if (fillerReduction) FillerReductionChip()
    }
}

@Composable
internal fun PreviewChip() {
    ModelChip(PREVIEW_LABEL, flaskIcon, content = previewToneColor(), fill = PREVIEW_CHIP_FILL)
}

@Composable
private fun FillerReductionChip() {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    ModelChip(FILLER_REDUCTION_LABEL, scissorsIcon, content = gray, fill = gray.copy(alpha = FEATURE_CHIP_FILL_ALPHA))
}

@Composable
internal fun PreviewNotice(modifier: Modifier = Modifier) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val placeholder =
        remember(labelStyle, density) {
            val label = measurer.measure(PREVIEW_LABEL, labelStyle, softWrap = false, maxLines = 1).size
            with(density) {
                val chipWidth = label.width + (CHIP_ICON_SIZE + CHIP_ICON_GAP + CHIP_PADDING_H * 2).toPx()
                val chipHeight = maxOf(label.height.toFloat(), CHIP_ICON_SIZE.toPx()) + (CHIP_PADDING_V * 2).toPx()
                Placeholder(
                    width = (chipWidth + NOTICE_CHIP_GAP.toPx()).toSp(),
                    height = chipHeight.toSp(),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
            }
        }
    Text(
        text =
            buildAnnotatedString {
                appendInlineContent(PREVIEW_CHIP_ID, PREVIEW_LABEL)
                append(ModelDataPolicy.PREVIEW_NOTICE.joinToString(""))
            },
        inlineContent =
            mapOf(
                PREVIEW_CHIP_ID to
                    InlineTextContent(placeholder) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { PreviewChip() }
                    },
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ModelChip(
    label: String,
    icon: ImageVector,
    content: Color,
    fill: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .background(fill, RoundedCornerShape(50))
                .padding(horizontal = CHIP_PADDING_H, vertical = CHIP_PADDING_V),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(CHIP_ICON_SIZE))
        Spacer(Modifier.width(CHIP_ICON_GAP))
        Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, softWrap = false)
    }
}

internal val flaskIcon: ImageVector =
    ImageVector
        .Builder(
            name = "Anonen.Flask",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = ICON_STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {

                moveTo(8.5f, 3f)
                horizontalLineTo(15.5f)

                moveTo(10f, 3f)
                verticalLineTo(9.5f)
                lineTo(4.6f, 18.6f)
                curveTo(4f, 19.6f, 4.7f, 21f, 5.9f, 21f)
                horizontalLineTo(18.1f)
                curveTo(19.3f, 21f, 20f, 19.6f, 19.4f, 18.6f)
                lineTo(14f, 9.5f)
                verticalLineTo(3f)

                moveTo(7.3f, 15f)
                horizontalLineTo(16.7f)
            }
        }.build()

internal val scissorsIcon: ImageVector =
    ImageVector
        .Builder(
            name = "Anonen.Scissors",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = ICON_STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {

                moveTo(9f, 6f)
                arcTo(3f, 3f, 0f, true, true, 3f, 6f)
                arcTo(3f, 3f, 0f, true, true, 9f, 6f)
                close()
                moveTo(9f, 18f)
                arcTo(3f, 3f, 0f, true, true, 3f, 18f)
                arcTo(3f, 3f, 0f, true, true, 9f, 18f)
                close()

                moveTo(8.1f, 8.1f)
                lineTo(20f, 17.5f)
                moveTo(8.1f, 15.9f)
                lineTo(20f, 6.5f)
            }
        }.build()

private const val PREVIEW_CHIP_ID = "preview-chip"
private const val ICON_STROKE = 2.2f
private const val FEATURE_CHIP_FILL_ALPHA = 0.12f
private val CHIP_ICON_SIZE = 12.dp
private val CHIP_ICON_GAP = 3.dp
private val CHIP_PADDING_H = 6.dp
private val CHIP_PADDING_V = 1.dp
private val CHIP_SPACING = 6.dp

private val NOTICE_CHIP_GAP = 6.dp
