package net.anonen.app.dev

import androidx.compose.runtime.Composable
import net.anonen.app.AnonenApp
import net.anonen.app.history.HistoryEntry
import net.anonen.app.settings.AnonenSettings

object DevFeaturesProvider {
    val instance: DevFeatures =
        object : DevFeatures {
            @Composable
            override fun HomeSection() {}

            @Composable
            override fun HistoryRowAction(entry: HistoryEntry) {}

            @Composable
            override fun SettingsSection(
                slot: DevSettingsSlot,
                app: AnonenApp,
                settings: AnonenSettings,
                update: (transform: (AnonenSettings) -> AnonenSettings) -> Unit,
            ) {}
        }
}
