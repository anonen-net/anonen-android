package net.anonen.app.settings

import net.anonen.app.core.HistoryRetention
import net.anonen.app.core.ModelIds
import net.anonen.app.core.RecordingAudioSource
import net.anonen.app.core.ThemeMode

data class AnonenSettings(
    val selectedModel: String = "",
    val previouslySelectedCloudModels: List<String> = emptyList(),
    val acknowledgedCloudPolicies: List<String> = emptyList(),
    val languageHint: String = "ja",
    val customWords: List<String> = emptyList(),
    val wordCorrectionThreshold: Double = 0.18,
    val customFillerWords: List<String>? = null,
    val appendTrailingSpace: Boolean = false,
    val pushToTalk: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val useWithoutAccessibility: Boolean = false,
    val initialModelProposalDismissed: Boolean = false,
    val notificationPermissionAsked: Boolean = false,
    val accessibilitySetupStartedAt: Long = 0L,
    val startAfterAccessibility: Boolean = false,
    val firstUseHintsShown: Int = 0,
    val recordingHintsShown: Int = 0,
    val audioFeedback: Boolean = true,
    val audioFeedbackVolume: Float = 1.0f,
    val recordingAudioSource: RecordingAudioSource = RecordingAudioSource.CAMCORDER,
    val spectralEqEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val historyRetention: HistoryRetention = HistoryRetention.RECENT,
    val buttonSizeDp: Int = DEFAULT_BUTTON_SIZE_DP,
    val buttonAlpha: Float = DEFAULT_BUTTON_ALPHA,
    val buttonLookVersion: Int = BUTTON_LOOK_VERSION,
    val hapticFeedback: Boolean = true,
    val buttonPosX: Int = -1,
    val buttonPosY: Int = -1,
) {
    val hasModelSelected: Boolean get() = !ModelIds.isNoneSelected(selectedModel)

    val historyEnabled: Boolean get() = historyRetention.enabled

    val historyLimit: Int get() = historyRetention.limit

    val historyAudioLimit: Int get() = historyRetention.audioLimit

    fun withCloudModelSelected(cloudId: String): AnonenSettings {
        val history =
            (listOf(cloudId) + previouslySelectedCloudModels.filter { it != cloudId })
                .take(MAX_PREVIOUS_CLOUD_MODELS)
        return copy(
            selectedModel = ModelIds.ANONEN_CLOUD_PREFIX + cloudId,
            previouslySelectedCloudModels = history,
        )
    }

    val needsInitialCloudModel: Boolean
        get() = !hasModelSelected && previouslySelectedCloudModels.isEmpty()

    val showsInitialModelProposal: Boolean
        get() = needsInitialCloudModel && !initialModelProposalDismissed

    fun withInitialModelProposalDismissed(): AnonenSettings = copy(initialModelProposalDismissed = true)

    fun needsDataPolicyDisclosure(fingerprint: String): Boolean = fingerprint !in acknowledgedCloudPolicies

    fun allowsOneTapSwitchTo(
        cloudId: String,
        fingerprint: String,
    ): Boolean = cloudId in previouslySelectedCloudModels && !needsDataPolicyDisclosure(fingerprint)

    fun selectedCloudModelNeedingDisclosure(fingerprints: Map<String, String>): String? {
        if (!selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) return null
        val id = selectedModel.removePrefix(ModelIds.ANONEN_CLOUD_PREFIX)
        val fingerprint = fingerprints[id] ?: return null
        return if (needsDataPolicyDisclosure(fingerprint)) id else null
    }

    fun withDataPolicyAcknowledged(fingerprint: String): AnonenSettings =
        if (fingerprint in acknowledgedCloudPolicies) {
            this
        } else {
            copy(
                acknowledgedCloudPolicies =
                    (listOf(fingerprint) + acknowledgedCloudPolicies)
                        .take(MAX_ACKNOWLEDGED_POLICIES),
            )
        }

    fun withNoModelSelected(): AnonenSettings = copy(selectedModel = ModelIds.NONE)

    fun withDataPolicyDeclined(cloudId: String): AnonenSettings =
        if (selectedModel == ModelIds.ANONEN_CLOUD_PREFIX + cloudId) {
            withCloudModelSelected(cloudId).withNoModelSelected()
        } else {
            this
        }

    companion object {
        const val MAX_PREVIOUS_CLOUD_MODELS = 20

        const val MAX_ACKNOWLEDGED_POLICIES = 60

        const val DEFAULT_BUTTON_SIZE_DP = 50
        const val DEFAULT_BUTTON_ALPHA = 0.5f

        const val BUTTON_SIZE_MIN_DP = 40
        const val BUTTON_SIZE_MAX_DP = 96
        const val BUTTON_ALPHA_MIN = 0.3f
        const val BUTTON_ALPHA_MAX = 1f
        const val BUTTON_LOOK_VERSION = 3
    }
}

internal fun migrateButtonLook(
    sizeDp: Int,
    alpha: Float,
    version: Int,
): Triple<Int, Float, Int> {
    val enlarged = version == ENLARGED_LOOK_VERSION && sizeDp == ENLARGED_SIZE_DP && alpha == ENLARGED_ALPHA
    val size = if (enlarged) AnonenSettings.DEFAULT_BUTTON_SIZE_DP else sizeDp
    val a = if (enlarged) AnonenSettings.DEFAULT_BUTTON_ALPHA else alpha
    return Triple(
        size.coerceIn(AnonenSettings.BUTTON_SIZE_MIN_DP, AnonenSettings.BUTTON_SIZE_MAX_DP),
        a.coerceIn(AnonenSettings.BUTTON_ALPHA_MIN, AnonenSettings.BUTTON_ALPHA_MAX),
        AnonenSettings.BUTTON_LOOK_VERSION,
    )
}

private const val ENLARGED_LOOK_VERSION = 2
private const val ENLARGED_SIZE_DP = 64
private const val ENLARGED_ALPHA = 1f
