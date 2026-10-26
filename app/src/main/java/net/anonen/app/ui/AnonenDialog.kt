package net.anonen.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class DialogButton(
    val label: String,
    val onClick: () -> Unit,
    val filled: Boolean = true,
    val destructive: Boolean = false,
)

@Composable
internal fun AnonenDialog(
    title: String,
    onDismissRequest: () -> Unit,
    primary: DialogButton,
    secondary: DialogButton? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = LocalTextStyle.current.copy(lineBreak = DIALOG_LINE_BREAK)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                content()
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogActionButton(primary)
                    secondary?.let { DialogActionButton(it) }
                }
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun DialogActionButton(button: DialogButton) {
    val modifier = Modifier.fillMaxWidth()
    val shape = RoundedCornerShape(8.dp)
    val label: @Composable () -> Unit = {
        Text(
            button.label,
            color =
                when {
                    button.destructive -> MaterialTheme.colorScheme.error
                    button.filled -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurface
                },
        )
    }
    if (button.filled) {
        Button(onClick = button.onClick, modifier = modifier, shape = shape) { label() }
    } else {
        OutlinedButton(onClick = button.onClick, modifier = modifier, shape = shape) { label() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AnonenConfirmDialog(
    text: String,
    onDismissRequest: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)

    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(lineBreak = DIALOG_LINE_BREAK),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                val colors = MaterialTheme.colorScheme
                val bodyColor = if (title != null) colors.onSurfaceVariant else colors.onSurface
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = DIALOG_LINE_BREAK),
                    color = bodyColor,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = shape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) { Text(dismissLabel) }
                    Button(onClick = onConfirm, shape = shape) { Text(confirmLabel) }
                }
            }
        }
    }
}

private val DIALOG_LINE_BREAK = LineBreak.Simple.copy(wordBreak = LineBreak.WordBreak.Phrase)
