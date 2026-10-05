package net.anonen.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.anonen.app.cloud.CloudModelInfo
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.cloud.processingDetail

internal enum class PolicyDialogMode {
    CHOOSE,

    PROPOSE,

    RECONFIRM,

    VIEW,
}

@Composable
internal fun ModelDataPolicyDialog(
    model: CloudModelInfo,
    mode: PolicyDialogMode,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit = onDecline,
) {
    val policy = model.dataPolicy()
    AnonenDialog(
        title = model.displayName.ifEmpty { model.id },
        onDismissRequest = if (mode == PolicyDialogMode.RECONFIRM) ({}) else onDismiss,
        primary =
            DialogButton(
                acceptLabel(mode),
                if (mode == PolicyDialogMode.VIEW) onDismiss else onAccept,
                filled = mode != PolicyDialogMode.RECONFIRM,
            ),
        secondary = declineLabel(mode)?.let { DialogButton(it, onDecline, filled = false) },
    ) {
        if (mode == PolicyDialogMode.RECONFIRM) {
            Text(RECONFIRM_LINE, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }
        PolicyRow("音声の送り先", model.processingDetail() ?: model.provider)
        PolicyRow("AI の学習に使うか", policy.training)
        PolicyRow("送り先に残るか", policy.retention)
    }
}

internal fun acceptLabel(mode: PolicyDialogMode): String =
    when (mode) {
        PolicyDialogMode.CHOOSE -> "このモデルを使う"
        PolicyDialogMode.PROPOSE -> "このモデルで始める"
        PolicyDialogMode.RECONFIRM -> "このまま使う"
        PolicyDialogMode.VIEW -> "閉じる"
    }

internal fun declineLabel(mode: PolicyDialogMode): String? =
    when (mode) {
        PolicyDialogMode.CHOOSE -> "やめる"
        PolicyDialogMode.PROPOSE -> "他のモデルを選ぶ"
        PolicyDialogMode.RECONFIRM -> "使うのをやめる"
        PolicyDialogMode.VIEW -> null
    }

@Composable
private fun PolicyRow(
    label: String,
    value: String,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
}

internal const val RECONFIRM_LINE = "使い続けるには、もう一度確かめてください"
