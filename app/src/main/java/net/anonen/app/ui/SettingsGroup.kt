package net.anonen.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val CARD_SHAPE = RoundedCornerShape(6.dp)
private val CARD_BORDER = 2.dp
private val CARD_SHADOW = 2.dp

@Composable
internal fun BrutalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.padding(end = CARD_SHADOW, bottom = CARD_SHADOW)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(CARD_SHADOW, CARD_SHADOW)
                .background(cardShadowColor(), CARD_SHAPE),
        )
        val clickModifier =
            if (onClick != null) {
                Modifier.fillMaxWidth().clickable(onClick = onClick)
            } else {
                Modifier.fillMaxWidth()
            }
        Surface(
            shape = CARD_SHAPE,
            border = BorderStroke(CARD_BORDER, cardBorderColor()),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = clickModifier,
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 1.5,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        BrutalCard(content = content)
    }
}

@Composable
internal fun SettingItem(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun SettingDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
