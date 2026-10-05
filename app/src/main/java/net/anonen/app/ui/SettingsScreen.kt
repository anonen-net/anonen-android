package net.anonen.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.BuildConfig
import net.anonen.app.R
import net.anonen.app.cloud.CloudAccountStatus
import net.anonen.app.core.DiagnosticsReport
import net.anonen.app.core.HistoryRetention
import net.anonen.app.core.LanguageHints
import net.anonen.app.core.ModelIds
import net.anonen.app.core.ThemeMode
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.dev.DevSettingsSlot
import net.anonen.app.history.AudioUsage
import net.anonen.app.settings.AnonenSettings

@Composable
internal fun SettingsScreen() {
    val app = AnonenApp.from(LocalContext.current)
    val settingsOrNull by app.settingsRepository.settings.collectAsState(initial = null)
    val settings = settingsOrNull ?: return
    val scope = rememberCoroutineScope()

    fun update(transform: (AnonenSettings) -> AnonenSettings) {
        scope.launch { app.settingsRepository.update(transform) }
    }

    val dev = DevFeaturesProvider.instance

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        AnonenCloudSection(app, settings, scope, ::update)
        dev.SettingsSection(DevSettingsSlot.MODEL_SECTION_END, app, settings, ::update)
        LanguageSection(app, settings, ::update)
        SoundSection(settings, ::update)
        AppSection(settings, ::update)
        HistorySection(app, settings, scope, ::update)
        dev.SettingsSection(DevSettingsSlot.APP_SECTION_END, app, settings, ::update)

        UsageSection(app, scope)
        SupportSection(app, settings)
        AboutSection()

        HowToUseSection(settings)
        Spacer(Modifier.height(24.dp))
    }
}

internal const val ACCOUNT_DELETION_URL = "https://anonen.net/legal/account-deletion"

internal const val SUPPORT_FORM_URL = "https://anonen.net/support/"

internal const val PRIVACY_POLICY_URL = "https://anonen.net/legal/privacy"
internal const val TOKUSHOHO_URL = "https://anonen.net/legal/tokushoho"

@Composable
private fun AnonenCloudSection(
    app: AnonenApp,
    settings: AnonenSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    val isSignedIn = remember { mutableStateOf(app.cloudAuth.isSignedIn()) }
    val authEmail = remember { mutableStateOf(app.cloudAuth.status().email ?: "") }

    val account by app.cloudUsage.account.collectAsState()

    LaunchedEffect(Unit) {
        if (isSignedIn.value) {
            app.cloudModels.refresh()
            withContext(Dispatchers.IO) {
                runCatching { app.cloudUsage.fetch() }
            }
        }
    }

    SettingsGroup("あのねん") {
        if (!isSignedIn.value) {
            CloudSignInForm(
                onSignedIn = { email ->
                    isSignedIn.value = true
                    authEmail.value = email

                    scope.launch {
                        app.cloudModels.refresh(force = true)
                        app.ensureInitialCloudModel()
                        withContext(Dispatchers.IO) {
                            runCatching { app.cloudUsage.fetch() }
                        }
                    }
                },
                auth = app.cloudAuth,
                scope = scope,
            )
        } else {
            CloudSignedInView(
                email = authEmail.value,
                account = account,
                onLogout = {
                    app.cloudAuth.logout()
                    app.cloudUsage.clear()

                    app.entitlement.onLogout()
                    isSignedIn.value = false
                    authEmail.value = ""
                },
            )
            DevFeaturesProvider.instance.SettingsSection(
                DevSettingsSlot.CLOUD_SECTION_END,
                app,
                settings,
                update,
            )
        }
    }
}

internal fun subscriptionStatusLabel(status: String?): String? =
    when (status) {
        null -> null
        "trialing" -> "無料のお試し中（使える時間は普段の半分）"
        "active" -> "契約中"
        "past_due" -> "お支払いを待っています"
        "canceled" -> "解約しました"
        "unpaid" -> "お支払いができず、止まっています"
        "incomplete" -> "申し込みが終わっていません"
        "incomplete_expired" -> "申し込みの期限が切れました"
        "paused" -> "一時停止中"
        else -> "契約: $status"
    }

@Composable
private fun CloudSignedInView(
    email: String,
    account: CloudAccountStatus?,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ログイン中", style = MaterialTheme.typography.bodyMedium)
                Text(
                    email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subscriptionStatusLabel(account?.subscriptionStatus)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            var confirmLogout by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmLogout = true }) {
                Text("ログアウト", color = MaterialTheme.colorScheme.error)
            }
            if (confirmLogout) {
                LogoutConfirmDialog(
                    onKeep = { confirmLogout = false },
                    onLogout = {
                        confirmLogout = false
                        onLogout()
                    },
                )
            }
        }

        if (account?.subscriptionStatus == "past_due") {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.past_due_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ACCOUNT_DELETION_URL)))
                }.onFailure {
                    Toast.makeText(context, "ページを開けませんでした", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Text("アカウントを削除するには", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LanguageSection(
    app: AnonenApp,
    settings: AnonenSettings,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    val cloudModels by app.cloudModels.models.collectAsState()
    val selected = ModelIds.normalizeSelected(settings.selectedModel)
    val cloudInfo =
        if (selected.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) {
            cloudModels.firstOrNull { it.id == selected.removePrefix(ModelIds.ANONEN_CLOUD_PREFIX) }
        } else {
            null
        }
    val hidden = cloudInfo != null && cloudInfo.supportedLanguages.isEmpty()
    if (hidden) return

    SettingsGroup("言葉") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            LanguageHintDropdown(
                selectedCode = settings.languageHint,
                allowedCodes = cloudInfo?.supportedLanguages,
                onSelect = { code -> update { it.copy(languageHint = code) } },
            )
        }
    }
}

@Composable
private fun SoundSection(
    settings: AnonenSettings,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    SettingsGroup("音とバイブレーション") {
        SwitchRow(
            label = "録音の開始・停止音",
            checked = settings.audioFeedback,
        ) { v -> update { it.copy(audioFeedback = v) } }
        SettingDivider()
        SwitchRow(
            label = "バイブレーションで通知",
            checked = settings.hapticFeedback,
        ) { v -> update { it.copy(hapticFeedback = v) } }

        if (settings.audioFeedback) {
            SettingDivider()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LabeledSlider(
                    label = "音量",
                    value = settings.audioFeedbackVolume,
                    range = 0f..1f,
                    onCommit = { v -> update { it.copy(audioFeedbackVolume = v) } },
                )
            }
        }
    }
}

@Composable
private fun AppSection(
    settings: AnonenSettings,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    SettingsGroup("ボタン") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            LabeledSlider(
                label = "ボタンの大きさ",
                value = settings.buttonSizeDp.toFloat(),
                range = AnonenSettings.BUTTON_SIZE_MIN_DP.toFloat()..AnonenSettings.BUTTON_SIZE_MAX_DP.toFloat(),
                onCommit = { v -> update { it.copy(buttonSizeDp = v.toInt()) } },
            )
        }

        SettingDivider()

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            LabeledSlider(
                label = "ボタンの濃さ",
                value = settings.buttonAlpha,
                range = AnonenSettings.BUTTON_ALPHA_MIN..AnonenSettings.BUTTON_ALPHA_MAX,
                onCommit = { v -> update { it.copy(buttonAlpha = v) } },
            )
        }
    }
    SettingsGroup("画面の色") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            listOf(
                ThemeMode.SYSTEM to "スマホに合わせる",
                ThemeMode.LIGHT to "明るい",
                ThemeMode.DARK to "暗い",
            ).forEach { (mode, label) ->
                ModelRadioRow(
                    name = label,
                    detail = null,
                    selected = settings.themeMode == mode,
                    weight = true,
                    onClick = { update { it.copy(themeMode = mode) } },
                )
            }
        }
    }
}

@Composable
private fun HistorySection(
    app: AnonenApp,
    settings: AnonenSettings,
    scope: CoroutineScope,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    var usage by remember { mutableStateOf<AudioUsage?>(null) }
    LaunchedEffect(Unit) { usage = withContext(Dispatchers.IO) { app.historyRepository.audioUsage() } }
    SettingsGroup("記録") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            listOf(
                Triple(
                    HistoryRetention.NONE,
                    "これからは残さない",
                    "今ある記録は消えません。文字起こしできなかった録音も後から文字起こしできません",
                ),
                Triple(
                    HistoryRetention.RECENT,
                    "新しい ${HistoryRetention.RECENT_LIMIT} 件だけ",
                    "古いものから消えます",
                ),
                Triple(
                    HistoryRetention.UNLIMITED,
                    "ずっと",
                    "文字はずっと、録音は新しい ${HistoryRetention.UNLIMITED_AUDIO_LIMIT} 件まで残します",
                ),
            ).forEach { (mode, label, detail) ->
                ModelRadioRow(
                    name = label,
                    detail = detail,
                    selected = settings.historyRetention == mode,
                    weight = true,
                    onClick = {
                        update { it.copy(historyRetention = mode) }

                        scope.launch {
                            app.historyRepository.trimTo(mode.limit)
                            app.historyRepository.dropAudioBeyond(mode.audioLimit)
                            usage = withContext(Dispatchers.IO) { app.historyRepository.audioUsage() }
                        }
                    },
                )
            }
            usage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SupportSection(
    app: AnonenApp,
    settings: AnonenSettings,
) {
    SettingsGroup("うまくいかないとき") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SupportActions(app, settings)
        }
    }
}

@Composable
internal fun SupportActions(
    app: AnonenApp,
    settings: AnonenSettings?,
) {
    val context = LocalContext.current
    val usage by app.cloudUsage.usage.collectAsState()
    val account by app.cloudUsage.account.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_FORM_URL)),
                    )
                }.onFailure {
                    Toast.makeText(context, "ページを開けませんでした", Toast.LENGTH_SHORT).show()
                }
            },
        ) {
            Icon(Icons.Default.Email, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("問い合わせる")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val report =
                    DiagnosticsReport.build(
                        context = context,
                        settings = settings,
                        auth = app.cloudAuth.status(),
                        account = account,
                        usage = usage,
                    )
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("ANONEN diagnostics", report),
                )
                Toast.makeText(context, "お問い合わせ情報をコピーしました", Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("お問い合わせ情報をコピー")
        }

        var showContents by remember { mutableStateOf(false) }
        TextButton(onClick = { showContents = !showContents }) { Text("お問い合わせ情報に入るもの") }
        if (showContents) {
            Text(
                DiagnosticsReport.CONTENTS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "コピーするだけで、アプリからは送りません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageSection(
    app: AnonenApp,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val usage by app.cloudUsage.usage.collectAsState()
    val snapshot = usage ?: return

    SettingsGroup("使った量") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            UsageBar(
                label = "今週",
                usedS = snapshot.weekUsedS,
                capS = snapshot.weekCapS,
                fullCapS = snapshot.weekBarFullS,
                resetLabel = formatWeekResetJst(snapshot.weekResetsAt),
            )

            val weekLeft = snapshot.weekCapS - snapshot.weekUsedS
            val longLeft = snapshot.monthCapS - snapshot.monthUsedS
            if (longLeft < weekLeft) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "この 28 日で使える分が、先になくなります（残り ${formatUsageDuration(longLeft)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { app.cloudUsage.fetch() }
                        }
                    }
                },
            ) {
                Text("読み込み直す", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val LINK_PADDING_H = 8.dp
private val LINK_PADDING = PaddingValues(horizontal = LINK_PADDING_H, vertical = 8.dp)

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var showNotices by remember { mutableStateOf(false) }

    var notices by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(showNotices) {
        if (showNotices && notices == null) {
            notices =
                withContext(Dispatchers.IO) {
                    noticeChunks(
                        listOf("NOTICE.md", "THIRD-PARTY-NOTICES.md")
                            .mapNotNull { name ->
                                runCatching {
                                    context.assets.open(name).bufferedReader().use { it.readText() }
                                }.getOrNull()
                            }
                            .joinToString("\n\n"),
                    )
                }
        }
    }

    SettingsGroup("このアプリについて") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("あのねん", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                BuildConfig.GIT_HASH,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(4.dp))

            Column(modifier = Modifier.offset(x = -LINK_PADDING_H)) {
                LegalLink(context, "プライバシーポリシー", PRIVACY_POLICY_URL)
                LegalLink(context, "特定商取引法に基づく表記", TOKUSHOHO_URL)
                AboutLink(if (showNotices) "閉じる" else "ライセンス") { showNotices = !showNotices }
            }
            if (showNotices) {
                val chunks = notices
                when {
                    chunks == null ->
                        Text(
                            "読み込み中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    chunks.isEmpty() ->
                        Text(
                            "ライセンス表記を読み込めませんでした",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                    else ->
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(chunks) { chunk ->
                                Text(
                                    chunk,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun AboutLink(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, contentPadding = LINK_PADDING) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LegalLink(
    context: android.content.Context,
    label: String,
    url: String,
) {
    AboutLink(label) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "ページを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun ModelRadioRow(
    name: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    weight: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
            colors = radioColors(),
            modifier = Modifier.padding(RADIO_PADDING),
        )
        Column(modifier = if (weight) Modifier.weight(1f) else Modifier) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun parseWordList(value: String): List<String> =
    value.split(',', '\n').map {
        it.trim()
    }.filter { it.isNotEmpty() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageHintDropdown(
    selectedCode: String,
    allowedCodes: List<String>?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val options =
        if (allowedCodes.isNullOrEmpty()) {
            LanguageHints.options
        } else {
            LanguageHints.options.filter { it.code.isEmpty() || it.code in allowedCodes }
        }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = LanguageHints.labelFor(selectedCode),
            onValueChange = {},
            readOnly = true,
            label = { Text("話す言葉") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = textFieldColors(),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option.code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@Composable
internal fun DebouncedTextField(
    label: String,
    initial: String,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(text, dirty) {
        if (dirty) {
            delay(400)
            onSave(text)
            dirty = false
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            dirty = true
        },
        label = { Text(label) },
        colors = textFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range,
        colors =
            SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
    )
}

internal val RADIO_PADDING = 12.dp

@Composable
internal fun radioColors() =
    RadioButtonDefaults.colors(
        selectedColor = MaterialTheme.colorScheme.primary,
        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

@Composable
internal fun textFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

@Composable
internal fun LogoutConfirmDialog(
    onKeep: () -> Unit,
    onLogout: () -> Unit,
) {
    AnonenDialog(
        title = "ログアウトしますか",
        onDismissRequest = onKeep,
        primary = DialogButton("ログアウトしない", onKeep),
        secondary = DialogButton("ログアウトする", onLogout, filled = false, destructive = true),
    ) {
        Text("ログインし直すには、メールの数字が要ります", style = MaterialTheme.typography.bodyMedium)
    }
}
