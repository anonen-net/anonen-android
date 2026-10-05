package net.anonen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        title = { Text(title) },
        text = {
            Column {
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
