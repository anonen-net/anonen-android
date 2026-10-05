package net.anonen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp

@Composable
internal fun LoginScreen(
    app: AnonenApp,
    onSignedIn: () -> Unit,
    onViewHistory: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                "あのねん",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))

            CloudSignInForm(
                onSignedIn = { onSignedIn() },
                auth = app.cloudAuth,
                scope = scope,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "初めての方",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))

            Text(
                "下のサイトで申し込んでから、ログインしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    "anonen.net",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    "anonen.net",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    rechecking = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { app.cloudUsage.fetch() } }
                        app.entitlement.refresh()
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
                        app.entitlement.onLogout()
                    },
                )
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
