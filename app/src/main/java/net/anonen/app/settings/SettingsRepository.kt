package net.anonen.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.anonen.app.core.HistoryRetention
import net.anonen.app.core.LanguageHints
import net.anonen.app.core.ModelIds
import net.anonen.app.core.RecordingAudioSource
import net.anonen.app.core.ThemeMode

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AnonenSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AnonenSettings = settings.first()

    suspend fun update(transform: (AnonenSettings) -> AnonenSettings) {
        dataStore.edit { prefs ->
            prefs.setAll(transform(prefs.toSettings()))
        }
    }

    companion object Keys {
        private val json = Json
        private val listSerializer = ListSerializer(String.serializer())

        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val PREVIOUS_CLOUD_MODELS = stringPreferencesKey("previously_selected_cloud_models_json")
        val ACKNOWLEDGED_POLICIES = stringPreferencesKey("acknowledged_cloud_policies_json")
        val LANGUAGE_HINT = stringPreferencesKey("language_hint")
        val CUSTOM_WORDS = stringPreferencesKey("custom_words_json")
        val WORD_THRESHOLD = doublePreferencesKey("word_correction_threshold")
        val CUSTOM_FILLER_WORDS = stringPreferencesKey("custom_filler_words_json")
        val TRAILING_SPACE = booleanPreferencesKey("append_trailing_space")
        val PUSH_TO_TALK = booleanPreferencesKey("push_to_talk")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val USE_WITHOUT_ACCESSIBILITY = booleanPreferencesKey("use_without_accessibility")
        val INITIAL_MODEL_PROPOSAL_DISMISSED = booleanPreferencesKey("initial_model_proposal_dismissed")
        val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        val ACCESSIBILITY_SETUP_STARTED_AT = longPreferencesKey("accessibility_setup_started_at")
        val START_AFTER_ACCESSIBILITY = booleanPreferencesKey("start_after_accessibility")
        val FIRST_USE_HINTS_SHOWN = intPreferencesKey("first_use_hints_shown")
        val RECORDING_HINTS_SHOWN = intPreferencesKey("recording_hints_shown")
        val BUTTON_LOOK_VERSION = intPreferencesKey("button_look_version")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val AUDIO_FEEDBACK = booleanPreferencesKey("audio_feedback")
        val AUDIO_FEEDBACK_VOLUME = floatPreferencesKey("audio_feedback_volume")
        val RECORDING_AUDIO_SOURCE = stringPreferencesKey("recording_audio_source")
        val SPECTRAL_EQ_ENABLED = booleanPreferencesKey("spectral_eq_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HISTORY_RETENTION = stringPreferencesKey("history_retention")

        val LEGACY_HISTORY_ENABLED = booleanPreferencesKey("history_enabled")

        val LEGACY_HISTORY_LIMIT = intPreferencesKey("history_limit")

        val LEGACY_CAP_FALLBACK_TO_LOCAL = booleanPreferencesKey("cap_fallback_to_local")
        val BUTTON_SIZE_DP = intPreferencesKey("button_size_dp")
        val BUTTON_ALPHA = floatPreferencesKey("button_alpha")
        val BUTTON_POS_X = intPreferencesKey("button_pos_x")
        val BUTTON_POS_Y = intPreferencesKey("button_pos_y")

        fun Preferences.toSettings(): AnonenSettings {
            val defaults = AnonenSettings()

            val look =
                migrateButtonLook(
                    this[BUTTON_SIZE_DP] ?: defaults.buttonSizeDp,
                    this[BUTTON_ALPHA] ?: defaults.buttonAlpha,
                    this[BUTTON_LOOK_VERSION] ?: 1,
                )
            return AnonenSettings(
                selectedModel =
                    ModelIds.normalizeSelected(this[SELECTED_MODEL] ?: defaults.selectedModel),
                previouslySelectedCloudModels =
                    this[PREVIOUS_CLOUD_MODELS]?.let { json.decodeFromString(listSerializer, it) }
                        ?: defaults.previouslySelectedCloudModels,
                acknowledgedCloudPolicies =
                    this[ACKNOWLEDGED_POLICIES]?.let { json.decodeFromString(listSerializer, it) }
                        ?: defaults.acknowledgedCloudPolicies,
                languageHint = LanguageHints.normalize(this[LANGUAGE_HINT] ?: defaults.languageHint),
                customWords =
                    this[CUSTOM_WORDS]?.let { json.decodeFromString(listSerializer, it) }
                        ?: defaults.customWords,
                wordCorrectionThreshold = this[WORD_THRESHOLD] ?: defaults.wordCorrectionThreshold,
                customFillerWords =
                    this[CUSTOM_FILLER_WORDS]?.let { json.decodeFromString(listSerializer, it) },
                appendTrailingSpace = this[TRAILING_SPACE] ?: defaults.appendTrailingSpace,
                pushToTalk = this[PUSH_TO_TALK] ?: defaults.pushToTalk,
                autoStartOnBoot = this[AUTO_START_ON_BOOT] ?: defaults.autoStartOnBoot,
                useWithoutAccessibility =
                    this[USE_WITHOUT_ACCESSIBILITY] ?: defaults.useWithoutAccessibility,
                initialModelProposalDismissed =
                    this[INITIAL_MODEL_PROPOSAL_DISMISSED] ?: defaults.initialModelProposalDismissed,
                notificationPermissionAsked =
                    this[NOTIFICATION_PERMISSION_ASKED] ?: defaults.notificationPermissionAsked,
                accessibilitySetupStartedAt =
                    this[ACCESSIBILITY_SETUP_STARTED_AT] ?: defaults.accessibilitySetupStartedAt,
                startAfterAccessibility = this[START_AFTER_ACCESSIBILITY] ?: defaults.startAfterAccessibility,
                firstUseHintsShown = this[FIRST_USE_HINTS_SHOWN] ?: defaults.firstUseHintsShown,
                recordingHintsShown = this[RECORDING_HINTS_SHOWN] ?: defaults.recordingHintsShown,
                audioFeedback = this[AUDIO_FEEDBACK] ?: defaults.audioFeedback,
                audioFeedbackVolume = this[AUDIO_FEEDBACK_VOLUME] ?: defaults.audioFeedbackVolume,
                recordingAudioSource =
                    this[RECORDING_AUDIO_SOURCE]?.let { stored ->
                        RecordingAudioSource.entries.firstOrNull { it.name == stored }
                    } ?: defaults.recordingAudioSource,
                spectralEqEnabled = this[SPECTRAL_EQ_ENABLED] ?: defaults.spectralEqEnabled,
                themeMode =
                    this[THEME_MODE]?.let { stored ->
                        ThemeMode.entries.firstOrNull { it.name == stored }
                    } ?: defaults.themeMode,
                historyRetention =
                    HistoryRetention.fromName(this[HISTORY_RETENTION])
                        ?: HistoryRetention.fromLegacyEnabled(this[LEGACY_HISTORY_ENABLED])
                        ?: defaults.historyRetention,
                buttonSizeDp = look.first,
                buttonAlpha = look.second,
                buttonLookVersion = look.third,
                hapticFeedback = this[HAPTIC_FEEDBACK] ?: defaults.hapticFeedback,
                buttonPosX = this[BUTTON_POS_X] ?: defaults.buttonPosX,
                buttonPosY = this[BUTTON_POS_Y] ?: defaults.buttonPosY,
            )
        }

        fun androidx.datastore.preferences.core.MutablePreferences.setAll(s: AnonenSettings) {
            this[SELECTED_MODEL] = s.selectedModel
            this[PREVIOUS_CLOUD_MODELS] =
                json.encodeToString(listSerializer, s.previouslySelectedCloudModels)
            this[ACKNOWLEDGED_POLICIES] =
                json.encodeToString(listSerializer, s.acknowledgedCloudPolicies)
            this[LANGUAGE_HINT] = LanguageHints.normalize(s.languageHint)
            this[CUSTOM_WORDS] = json.encodeToString(listSerializer, s.customWords)
            this[WORD_THRESHOLD] = s.wordCorrectionThreshold
            if (s.customFillerWords == null) {
                this.remove(CUSTOM_FILLER_WORDS)
            } else {
                this[CUSTOM_FILLER_WORDS] = json.encodeToString(listSerializer, s.customFillerWords)
            }
            this[TRAILING_SPACE] = s.appendTrailingSpace
            this[PUSH_TO_TALK] = s.pushToTalk
            this[AUTO_START_ON_BOOT] = s.autoStartOnBoot
            this[USE_WITHOUT_ACCESSIBILITY] = s.useWithoutAccessibility
            this[INITIAL_MODEL_PROPOSAL_DISMISSED] = s.initialModelProposalDismissed
            this[NOTIFICATION_PERMISSION_ASKED] = s.notificationPermissionAsked
            this[ACCESSIBILITY_SETUP_STARTED_AT] = s.accessibilitySetupStartedAt
            this[START_AFTER_ACCESSIBILITY] = s.startAfterAccessibility
            this[FIRST_USE_HINTS_SHOWN] = s.firstUseHintsShown
            this[RECORDING_HINTS_SHOWN] = s.recordingHintsShown
            this[AUDIO_FEEDBACK] = s.audioFeedback
            this[AUDIO_FEEDBACK_VOLUME] = s.audioFeedbackVolume
            this[RECORDING_AUDIO_SOURCE] = s.recordingAudioSource.name
            this[SPECTRAL_EQ_ENABLED] = s.spectralEqEnabled
            this[THEME_MODE] = s.themeMode.name
            this[HISTORY_RETENTION] = s.historyRetention.name

            this.remove(LEGACY_HISTORY_ENABLED)
            this.remove(LEGACY_HISTORY_LIMIT)
            this.remove(LEGACY_CAP_FALLBACK_TO_LOCAL)
            this[BUTTON_SIZE_DP] = s.buttonSizeDp
            this[BUTTON_ALPHA] = s.buttonAlpha
            this[BUTTON_LOOK_VERSION] = s.buttonLookVersion
            this[HAPTIC_FEEDBACK] = s.hapticFeedback
            this[BUTTON_POS_X] = s.buttonPosX
            this[BUTTON_POS_Y] = s.buttonPosY
        }
    }
}
