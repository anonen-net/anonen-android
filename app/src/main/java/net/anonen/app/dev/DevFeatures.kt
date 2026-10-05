package net.anonen.app.dev

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import net.anonen.app.AnonenApp
import net.anonen.app.asr.TranscriptionEngine
import net.anonen.app.cloud.PlainUpload
import net.anonen.app.history.HistoryEntry
import net.anonen.app.settings.AnonenSettings
import okhttp3.OkHttpClient

interface DevFeatures {
    fun onAppCreate(app: AnonenApp) {}

    suspend fun onRecordingProcessed(
        context: Context,
        rawWav: ByteArray,
        sentWav: ByteArray,
    ) {}

    fun createEngine(
        app: AnonenApp,
        settings: AnonenSettings,
        client: OkHttpClient?,
    ): TranscriptionEngine? = null

    fun plainUpload(): PlainUpload? = null

    fun modelDisplayName(selectedModel: String): String? = null

    fun fallbackModelId(selectedModel: String): String? = null

    val extraTabs: List<DevTab> get() = emptyList()

    @Composable
    fun HomeSection()

    @Composable
    fun HistoryRowAction(entry: HistoryEntry)

    @Composable
    fun SettingsSection(
        slot: DevSettingsSlot,
        app: AnonenApp,
        settings: AnonenSettings,
        update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
    )
}

class DevTab(
    val title: String,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

enum class DevSettingsSlot {
    CLOUD_SECTION_END,

    MODEL_SECTION_END,

    APP_SECTION_END,
}
