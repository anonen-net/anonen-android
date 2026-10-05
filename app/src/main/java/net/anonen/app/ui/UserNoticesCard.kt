package net.anonen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.anonen.app.AnonenApp

@Composable
internal fun UserNoticesCard(app: AnonenApp) {
    val notices by app.userNotices.notices.collectAsState()
    if (notices.isEmpty()) return

    SettingsGroup("お知らせ") {
        notices.take(MAX_SHOWN).forEachIndexed { index, notice ->
            if (index > 0) SettingDivider()
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(notice.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatNoticeTime(notice.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SettingDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { app.userNotices.clear() }) { Text("お知らせを削除") }
        }
    }
}

private const val MAX_SHOWN = 5

private fun formatNoticeTime(millis: Long): String =
    runCatching {
        android.text.format.DateFormat.format("MM/dd HH:mm", millis).toString()
    }.getOrDefault("")
