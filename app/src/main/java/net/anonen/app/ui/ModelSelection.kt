package net.anonen.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.anonen.app.AnonenApp
import net.anonen.app.asr.ModelState
import net.anonen.app.asr.ModelStatsStore
import net.anonen.app.cloud.AnonenCloudModels
import net.anonen.app.cloud.UsageNote
import net.anonen.app.cloud.dataPolicy
import net.anonen.app.cloud.processingDetail
import net.anonen.app.cloud.usageMultiplierNote
import net.anonen.app.core.LocalModelInfo
import net.anonen.app.core.ModelIds
import net.anonen.app.settings.AnonenSettings

@Composable
internal fun ModelSelectionSection(
    app: AnonenApp,
    settings: AnonenSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
) {
    val isSignedIn = remember { mutableStateOf(app.cloudAuth.isSignedIn()) }
    val cloudModels by app.cloudModels.models.collectAsState()
    val cloudFetch by app.cloudModels.lastFetch.collectAsState()
    val stats by app.modelStats.stats.collectAsState(initial = emptyMap())

    val relativeSpeed = remember(stats) { ModelStatsStore.relativeSpeedScores(stats) }

    LaunchedEffect(Unit) {
        if (isSignedIn.value) app.cloudModels.refresh()
    }

    var pendingDisclosureId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDisclosure = pendingDisclosureId?.let { id -> cloudModels.firstOrNull { it.id == id } }

    LaunchedEffect(cloudModels) {
        val id = pendingDisclosureId ?: return@LaunchedEffect
        if (cloudModels.isNotEmpty() && cloudModels.none { it.id == id }) pendingDisclosureId = null
    }

    var viewingPolicyId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewingPolicy = viewingPolicyId?.let { id -> cloudModels.firstOrNull { it.id == id } }

    LaunchedEffect(cloudModels, settings.selectedModel, settings.acknowledgedCloudPolicies) {
        if (pendingDisclosureId != null) return@LaunchedEffect
        val fingerprints = cloudModels.associate { it.id to it.dataPolicy().fingerprint }
        settings.selectedCloudModelNeedingDisclosure(fingerprints)?.let { pendingDisclosureId = it }
    }

    viewingPolicy?.let { model ->
        ModelDataPolicyDialog(
            model = model,
            mode = PolicyDialogMode.VIEW,
            onAccept = { viewingPolicyId = null },
            onDecline = { viewingPolicyId = null },
        )
    }

    pendingDisclosure?.let { model ->
        ModelDataPolicyDialog(
            model = model,
            mode =
                if (settings.selectedModel == ModelIds.ANONEN_CLOUD_PREFIX + model.id) {
                    PolicyDialogMode.RECONFIRM
                } else {
                    PolicyDialogMode.CHOOSE
                },
            onDecline = {
                pendingDisclosureId = null
                update { it.withDataPolicyDeclined(model.id) }
            },
            onAccept = {
                pendingDisclosureId = null
                update {
                    it.withDataPolicyAcknowledged(model.dataPolicy().fingerprint)
                        .withCloudModelSelected(model.id)
                }
            },
        )
    }

    SettingsGroup("モデル") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (!settings.hasModelSelected) {
                Text(
                    "モデルを選んでください",
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (settings.needsInitialCloudModel) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                !isSignedIn.value -> {
                    Text(
                        "ログインすると選べます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cloudModels.isEmpty() -> {
                    Text(
                        AnonenCloudModels.emptyCatalogMessage(cloudFetch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cloudFetch == AnonenCloudModels.Fetch.FAILED) {
                        TextButton(onClick = { scope.launch { app.cloudModels.refresh() } }) { Text("読み込み直す") }
                    }
                }
                else -> {
                    cloudModels.forEachIndexed { index, model ->
                        if (index > 0) SettingDivider()
                        val fullId = "${ModelIds.ANONEN_CLOUD_PREFIX}${model.id}"
                        ModelScoreRow(
                            name = model.displayName.ifEmpty { model.id },
                            detail = model.processingDetail(),
                            quotaNote = model.usageMultiplierNote(),
                            selected = settings.selectedModel == fullId,
                            onClick = {
                                val seen = model.dataPolicy().fingerprint
                                if (settings.needsDataPolicyDisclosure(seen)) {
                                    pendingDisclosureId = model.id
                                } else {
                                    update { it.withCloudModelSelected(model.id) }
                                }
                            },
                            accuracy = model.accuracyScore,
                            speed = relativeSpeed[fullId] ?: model.speedScore,
                            speedIsMeasured = relativeSpeed.containsKey(fullId),
                            samples = stats[fullId].orEmpty(),
                            onDetails = { viewingPolicyId = model.id },
                        )
                    }
                }
            }

            LocalModelInfo.ALL.forEach { info ->
                val modelState by app.localModelManager.stateFlow(info).collectAsState()

                SettingDivider()
                ModelScoreRow(
                    name = info.displayName,
                    detail = info.description,
                    showSpeed = false,
                    selected = settings.selectedModel == info.id,
                    enabled = modelState is ModelState.Ready,
                    onClick = { update { it.copy(selectedModel = info.id) } },
                    accuracy = info.accuracyScore,
                    speed = relativeSpeed[info.id] ?: info.speedScore,
                    speedIsMeasured = relativeSpeed.containsKey(info.id),
                    samples = stats[info.id].orEmpty(),
                )
                LocalModelDownloadRow(
                    info = info,
                    state = modelState,
                    onDownload = { scope.launch { app.localModelManager.download(info) } },
                    onDelete = {
                        app.localModelManager.delete(info)
                        if (settings.selectedModel == info.id) {
                            update { it.withNoModelSelected() }
                        }
                    },
                )
            }

            if (stats.isNotEmpty()) {
                SettingDivider()
                TextButton(onClick = { scope.launch { app.modelStats.clear() } }) {
                    Text("測った速さを削除", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun LocalModelDownloadRow(
    info: LocalModelInfo,
    state: ModelState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 48.dp, end = 16.dp, bottom = 8.dp)) {
        when (state) {
            is ModelState.NotDownloaded -> {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    val sizeMb = info.totalSizeBytes / 1_000_000
                    Text("ダウンロード（${sizeMb}MB）")
                }
            }
            is ModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    "ダウンロード中… %d%%".format((state.progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is ModelState.Ready -> {
                var showDeleteDialog by remember { mutableStateOf(false) }
                if (showDeleteDialog) {
                    AnonenDialog(
                        title = "このモデルを削除しますか",
                        onDismissRequest = { showDeleteDialog = false },
                        primary = DialogButton("やめる", { showDeleteDialog = false }),
                        secondary =
                            DialogButton(
                                "削除",
                                {
                                    onDelete()
                                    showDeleteDialog = false
                                },
                                filled = false,
                                destructive = true,
                            ),
                    )
                }
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("モデルを削除")
                }
            }
            is ModelState.Error -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("もう一度")
                }
            }
        }
    }
}

@Composable
internal fun ModelScoreRow(
    name: String,
    detail: String?,
    quotaNote: UsageNote? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    accuracy: Float,
    speed: Float,
    speedIsMeasured: Boolean = false,
    showSpeed: Boolean = true,
    samples: List<Long> = emptyList(),
    onDetails: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
            colors = radioColors(),
            modifier = Modifier.padding(RADIO_PADDING),
        )
        Column(modifier = Modifier.weight(1f)) {
            if (onDetails == null) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Text(
                        "詳しく",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .clickable(onClick = onDetails, role = Role.Button)
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
            }

            if (detail != null || quotaNote != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        detail.orEmpty(),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                lineBreak = LineBreak.Heading,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (quotaNote != null) {
                        val tone = usageToneColor(quotaNote.positive)
                        Text(
                            quotaNote.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = tone,
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .padding(start = 8.dp)
                                    .background(tone.copy(alpha = 0.14f), RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            if (accuracy > 0f || speed > 0f) {
                Spacer(Modifier.height(4.dp))
                Row {
                    ScoreBar("正確さ", accuracy, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))

                    if (showSpeed) {
                        ScoreBar(if (speedIsMeasured) "速さ*" else "速さ", speed, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            ModelStatsStore.medianMs(samples)?.let { median ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "このスマホで %.1f 秒（%d 回）".format(median / 1000.0, samples.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ScoreBar(
    label: String,
    score: Float,
    modifier: Modifier = Modifier,
) {
    val stacked = isLargeText()
    val bar: @Composable (Modifier) -> Unit = { m ->
        LinearProgressIndicator(
            progress = { score.coerceIn(0f, 1f) },
            modifier = m.height(5.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            drawStopIndicator = {},
        )
    }
    val text: @Composable () -> Unit = {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (stacked) {
        Column(modifier = modifier) {
            text()
            bar(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            text()
            Spacer(Modifier.width(4.dp))
            bar(Modifier.weight(1f))
        }
    }
}
