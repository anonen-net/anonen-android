package net.anonen.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

        if (model.isPreview) {
            PreviewNotice()
            Spacer(Modifier.height(12.dp))
        }
        PolicyRow("音声の送り先", model.processingDetail() ?: model.provider)
        PolicyRow("AI の学習に使うか", policy.training)
        PolicyRow("送り先に残るか", policy.retention)
        PrivacyPolicyLink()
    }
}

internal const val PRIVACY_PROVIDERS_URL = "https://anonen.net/legal/privacy#providers"

@Composable
private fun PrivacyPolicyLink() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_PROVIDERS_URL)))
            }.onFailure {
                Toast.makeText(context, "ページを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text("プライバシーポリシーを開く", style = MaterialTheme.typography.bodySmall)
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
