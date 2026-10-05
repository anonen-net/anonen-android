package net.anonen.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.AnonenApp
import net.anonen.app.core.setSensitivePlainText
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.history.HistoryAudioPlayer
import net.anonen.app.history.HistoryEntry
import net.anonen.app.pipeline.TranscriptionPipeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HistoryScreen() {
    val context = LocalContext.current
    val app = AnonenApp.from(context)
    val historyRepository = app.historyRepository
    val allEntries by historyRepository.entries.collectAsState(initial = emptyList())

    val pendingDelete by app.historyTrash.pending.collectAsState()
    val entries = allEntries.filter { it.id !in pendingDelete }
    var lastDeletedId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    var playingId by remember { mutableStateOf<Long?>(null) }
    val player = remember { HistoryAudioPlayer(context) }

    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { app.enclaveKeys.prefetch() } }
    }
    LaunchedEffect(playingId) {
        if (playingId == null) {
            positionMs = 0
            durationMs = 0
            return@LaunchedEffect
        }
        durationMs = player.durationMs
        while (true) {
            positionMs = player.positionMs
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    var showClearDialog by remember { mutableStateOf(false) }

    val retranscriber = app.retranscriber
    val retranscribingId by retranscriber.runningEntryId.collectAsState()
    val retranscribeMessage by retranscriber.message.collectAsState()
    LaunchedEffect(retranscribeMessage) {
        val text = retranscribeMessage ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

        retranscriber.consumeMessage()
    }

    if (showClearDialog) {
        AnonenDialog(
            title = "全て削除しますか",
            onDismissRequest = { showClearDialog = false },
            primary = DialogButton("やめる", { showClearDialog = false }),
            secondary =
                DialogButton(
                    "全て削除",
                    {
                        scope.launch { historyRepository.clear() }
                        showClearDialog = false
                    },
                    filled = false,
                    destructive = true,
                ),
        ) {
            Text("削除すると、元に戻せません", style = MaterialTheme.typography.bodyMedium)
        }
    }

    val undoId = lastDeletedId?.takeIf { it in pendingDelete }
    Box(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Text(
                "記録はまだありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.heightIn(min = SMALL_BUTTON_HEIGHT),
                            contentPadding = SMALL_BUTTON_PADDING,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) { Text("全て削除") }
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        retranscribing = retranscribingId == entry.id,
                        retranscribeEnabled = retranscribingId == null,
                        onRetranscribe = { retranscriber.retranscribe(entry) },
                        onCopy = { copyToClipboard(context, TranscriptionPipeline.historyDisplayText(entry.text)) },
                        onDelete = {
                            if (playingId == entry.id) {
                                player.stop()
                                playingId = null
                            }
                            app.historyTrash.stage(entry.id)
                            lastDeletedId = entry.id
                        },
                        playing = playingId == entry.id,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeek = { ms ->
                            player.seekTo(ms)
                            positionMs = ms
                        },
                        onTogglePlay = {
                            if (playingId == entry.id) {
                                player.stop()
                                playingId = null
                            } else {
                                val file = historyRepository.audioFile(entry)
                                val started =
                                    file != null && player.play(file) { playingId = null }
                                playingId = if (started) entry.id else null
                                if (!started) {
                                    Toast.makeText(context, "録音を再生できません", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    )
                }
            }
        }
        if (undoId != null) {
            UndoBar(
                onUndo = {
                    app.historyTrash.undo(undoId)
                    lastDeletedId = null
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun UndoBar(
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "削除しました",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) {
            Text("元に戻す", color = MaterialTheme.colorScheme.inversePrimary)
        }
    }
}

private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)

private val SMALL_BUTTON_HEIGHT = 32.dp
private val SMALL_BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

private fun formatMs(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    retranscribing: Boolean,
    retranscribeEnabled: Boolean,
    onRetranscribe: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    playing: Boolean,
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
    onTogglePlay: () -> Unit,
) {
    BrutalCard(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        onClick = onCopy,
    ) {
        val pages =
            remember(entry.text, entry.model, entry.revisions, entry.transcribedAt) {
                listOf(
                    Triple(
                        TranscriptionPipeline.historyDisplayText(entry.text),
                        entry.model,
                        entry.transcribedAt ?: entry.timestamp,
                    ),
                ) +
                    entry.revisions.reversed().map {
                        Triple(
                            TranscriptionPipeline.historyDisplayText(it.text),
                            it.model,
                            it.transcribedAt ?: entry.timestamp,
                        )
                    }
            }

        var page by rememberSaveable(entry.id, entry.revisions.size) { mutableIntStateOf(0) }
        val shown = pages[page.coerceIn(0, pages.lastIndex)]

        val time = timeFormat.format(Date(shown.third))

        val stacked = isLargeText()

        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            Text(shown.first, style = MaterialTheme.typography.bodyMedium)

            shown.second?.let { used ->
                Spacer(Modifier.height(4.dp))
                Text(
                    used,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (pages.size > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { page = (page + 1).coerceAtMost(pages.lastIndex) },
                        enabled = page < pages.lastIndex,
                        modifier = Modifier.heightIn(min = SMALL_BUTTON_HEIGHT),
                        contentPadding = SMALL_BUTTON_PADDING,
                    ) { Text("前") }
                    Text(
                        "${page + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { page = (page - 1).coerceAtLeast(0) },
                        enabled = page > 0,
                        modifier = Modifier.heightIn(min = SMALL_BUTTON_HEIGHT),
                        contentPadding = SMALL_BUTTON_PADDING,
                    ) { Text("次") }
                }
            }

            if (playing && durationMs > 0) {
                var dragging by remember { mutableStateOf(false) }
                var dragMs by remember { mutableIntStateOf(0) }
                val shownMs = if (dragging) dragMs else positionMs

                Spacer(Modifier.height(4.dp))
                Slider(
                    value = shownMs.toFloat(),
                    onValueChange = {
                        dragging = true
                        dragMs = it.toInt()
                    },
                    onValueChangeFinished = {
                        onSeek(dragMs)
                        dragging = false
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatMs(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMs(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (stacked) {
                Spacer(Modifier.height(8.dp))
                RowTime(time, Modifier.fillMaxWidth())
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stacked) Spacer(Modifier.weight(1f)) else RowTime(time, Modifier.weight(1f))
            DevFeaturesProvider.instance.HistoryRowAction(entry)

            if (entry.audioFileName != null) {
                PlayButton(playing = playing, onClick = onTogglePlay)
                RetranscribeButton(
                    untranscribed = TranscriptionPipeline.isUntranscribedHistoryText(entry.text),
                    retranscribing = retranscribing,
                    enabled = retranscribeEnabled,
                    onClick = onRetranscribe,
                )
            } else {
                Text(
                    "録音は残っていません",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            DeleteButton(enabled = !retranscribing, onClick = onDelete)
        }
    }
}

@Composable
private fun RowTime(
    time: String,
    modifier: Modifier,
) {
    Text(
        time,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun rowIconColors(contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    IconButtonDefaults.iconButtonColors(contentColor = contentColor)

private val stopIcon: ImageVector =
    materialIcon(name = "Filled.Stop") {
        materialPath {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
        }
    }

@Composable
private fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = if (playing) rowIconColors(MaterialTheme.colorScheme.primary) else rowIconColors(),
    ) {
        if (playing) {
            Icon(stopIcon, contentDescription = "停止")
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = "再生")
        }
    }
}

@Composable
private fun RetranscribeButton(
    untranscribed: Boolean,
    retranscribing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label =
        when {
            retranscribing -> "文字起こし中…"
            untranscribed -> "文字起こし"
            else -> "もう一度文字起こし"
        }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (untranscribed) rowIconColors(MaterialTheme.colorScheme.primary) else rowIconColors(),
    ) {
        if (retranscribing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).semantics { contentDescription = label },
                strokeWidth = 2.dp,
            )
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = label)
        }
    }
}

@Composable
private fun DeleteButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, colors = rowIconColors()) {
        Icon(Icons.Filled.Delete, contentDescription = "削除")
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setSensitivePlainText("Anonen", text)
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}
