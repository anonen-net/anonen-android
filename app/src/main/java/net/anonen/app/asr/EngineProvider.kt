package net.anonen.app.asr

import net.anonen.app.AnonenApp
import net.anonen.app.cloud.AnonenCloudEngine
import net.anonen.app.core.AsrError
import net.anonen.app.core.LocalModelInfo
import net.anonen.app.core.ModelIds
import net.anonen.app.core.TranscriptionRequest
import net.anonen.app.core.TranscriptionResult
import net.anonen.app.dev.DevFeaturesProvider
import net.anonen.app.record.OpusOggEncoder
import net.anonen.app.settings.AnonenSettings

object EngineProvider {
    fun create(
        app: AnonenApp,
        settings: AnonenSettings,
        onUsageUpdate: ((net.anonen.app.cloud.UsageSnapshot) -> Unit)? = null,
        onCloudModelInvalid: (() -> Unit)? = null,
        onCloudModelsVersion: ((String) -> Unit)? = null,
        onModelUsed: ((String) -> Unit)? = null,
        cancel: net.anonen.app.cloud.TranscribeCancelController? = null,
    ): TranscriptionEngine {
        val localInfo = LocalModelInfo.fromId(settings.selectedModel)
        if (localInfo != null && app.localModelManager.isReady(localInfo)) {
            onModelUsed?.invoke(settings.selectedModel)

            return app.localModelManager.engineFor(localInfo, settings.languageHint)
        }

        if (settings.selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) {
            val cloudModelId = settings.selectedModel.removePrefix(ModelIds.ANONEN_CLOUD_PREFIX)
            return AnonenCloudEngine(
                auth = app.cloudAuth,
                gatewayClient = app.cloudClient,
                cloudModelId = cloudModelId,
                onUsageUpdate = onUsageUpdate,
                onModelInvalid = onCloudModelInvalid,
                onModelsVersion = onCloudModelsVersion,
                onModelUsed = onModelUsed,
                onNoSubscription = { app.entitlement.onNoSubscription() },
                cancel = cancel ?: app.transcribeCancel,
                isOnline = { net.anonen.app.core.NetworkStatus.isOnline(app) },
                opusEncode = { wav -> OpusOggEncoder.encode(wav, app.cacheDir) },
                enclaveKeys = app.enclaveKeys,
                sealedRequired = net.anonen.app.BuildConfig.SEALED_REQUIRED,
            )
        }

        DevFeaturesProvider.instance.createEngine(app, settings, app.httpClient)?.let { return it }

        return UnconfiguredEngine
    }

    fun resolvedModelName(
        settings: AnonenSettings,
        cloudModels: net.anonen.app.cloud.AnonenCloudModels? = null,
    ): String {
        if (settings.selectedModel.startsWith(ModelIds.ANONEN_CLOUD_PREFIX)) {
            val cloudId = settings.selectedModel.removePrefix(ModelIds.ANONEN_CLOUD_PREFIX)
            return cloudModels?.displayName(cloudId) ?: "あのねん: $cloudId"
        }
        LocalModelInfo.fromId(settings.selectedModel)?.let { return it.displayName }
        return DevFeaturesProvider.instance.modelDisplayName(settings.selectedModel)
            ?: settings.selectedModel
    }

    private object UnconfiguredEngine : TranscriptionEngine {
        override fun transcribe(request: TranscriptionRequest): TranscriptionResult =
            TranscriptionResult.Failure(
                AsrError(
                    kind = AsrError.Kind.CONFIG,
                    userMessage = "使えるモデルがありません。アプリで選んでください",
                    detail = "no engine for selected model",
                ),
            )
    }
}
