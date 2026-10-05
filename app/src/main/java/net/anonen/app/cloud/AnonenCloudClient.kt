package net.anonen.app.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.anonen.app.cloud.sealed.SealedException
import net.anonen.app.cloud.sealed.SealedWire
import net.anonen.app.cloud.sealed.VerifiedEnclave
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ANONEN_CLIENT_HEADER = "X-Anonen-Client"

fun interface PlainUpload {
    fun transcribe(
        client: AnonenCloudClient,
        accessToken: String,
        audio: AudioUpload,
        model: String,
        language: String?,
        timeoutSecs: Long?,
        cancel: TranscribeCancelController?,
        requestId: String,
    ): TranscribeResponse
}

class AnonenCloudClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val clientId: String = "",
    private val plainUpload: PlainUpload? = null,
) {
    private val warmClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    }

    fun warm(
        accessToken: String,
        model: String?,
    ) {
        val modelId = requireCatalogModelId(model)
        val body =
            buildJsonObject {
                put("model", modelId)
            }.toString()
        val request =
            Request.Builder()
                .url(endpoint("/v1/warm"))
                .identify()
                .header("Authorization", "Bearer $accessToken")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        warmClient.newCall(request).execute().use { }
    }

    fun transcribe(
        accessToken: String,
        audio: AudioUpload,
        model: String?,
        language: String?,
        timeoutSecs: Long? = null,
        cancel: TranscribeCancelController? = null,
        enclave: VerifiedEnclave?,
    ): TranscribeResponse {
        val modelId = requireCatalogModelId(model)
        val requestId = UUID.randomUUID().toString()
        if (enclave != null) {
            return transcribeSealed(accessToken, audio, modelId, language, timeoutSecs, cancel, enclave, requestId)
        }

        val plain = plainUpload ?: throw GatewayError(0, "seal_unavailable", body = "")
        return plain.transcribe(this, accessToken, audio, modelId, language, timeoutSecs, cancel, requestId)
    }

    private fun transcribeSealed(
        accessToken: String,
        audio: AudioUpload,
        model: String,
        language: String?,
        timeoutSecs: Long?,
        cancel: TranscribeCancelController?,
        enclave: VerifiedEnclave,
        requestId: String,
    ): TranscribeResponse {
        val envelope =
            try {
                SealedWire.seal(enclave.publicKey, requestId, model, language, audio.bytes)
            } catch (e: Exception) {
                throw GatewayError(0, "seal_failed", body = "")
            }

        val request =
            Request.Builder()
                .url(endpoint("/v1/transcribe"))
                .identify()
                .header("Authorization", "Bearer $accessToken")
                .post(envelope.body.toRequestBody(SEALED_MEDIA_TYPE))
                .build()

        val outcome = execute(request, timeoutSecs, cancel)
        if (outcome.contentType() != SealedWire.CONTENT_TYPE) {
            val body = outcome.body.decodeToString()
            if (!outcome.successful) throw parseGatewayError(outcome.code, body)
            throw GatewayError(outcome.code, "sealed_response_missing", body = "")
        }

        val payload =
            try {
                SealedWire.openResponse(envelope.responseKey, outcome.body).decodeToString()
            } catch (e: SealedException) {
                throw GatewayError(outcome.code, "sealed_response_unreadable", body = "")
            }

        return readOpened(outcome.code, payload)
    }

    internal fun readOpened(
        httpStatus: Int,
        opened: String,
    ): TranscribeResponse {
        sealedError(httpStatus, opened)?.let { throw it }
        return parseTranscribe(httpStatus, opened)
    }

    private fun sealedError(
        httpStatus: Int,
        opened: String,
    ): GatewayError? {
        val parsed = runCatching { Json.parseToJsonElement(opened).jsonObject }.getOrNull() ?: return null
        val code = parsed["error"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content ?: return null
        return GatewayError(
            httpStatus = httpStatus,
            code = GatewayError.codeFromWire(code),
            which = parsed.stringOrNull("which"),
            resetsAt = parsed.stringOrNull("resets_at"),
            fallback = parsed.stringOrNull("fallback"),
            body = "",
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.numberOrNull(name: String): Double? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.wholeCountOrNull(name: String): Int? =
        numberOrNull(name)
            ?.takeIf { it >= 0.0 && it <= Int.MAX_VALUE.toDouble() && it == kotlin.math.floor(it) }
            ?.toInt()

    private fun JsonObject.floatOrNull(name: String): Float? = numberOrNull(name)?.toFloat()?.takeIf { it.isFinite() }

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

    internal fun execute(
        request: Request,
        timeoutSecs: Long?,
        cancel: TranscribeCancelController?,
    ): HttpOutcome {
        val callClient =
            if (timeoutSecs != null) {
                client.newBuilder().callTimeout(timeoutSecs, TimeUnit.SECONDS).build()
            } else {
                client
            }

        val call = callClient.newCall(request)
        cancel?.register(call)
        try {
            val response = call.execute()
            return HttpOutcome(
                code = response.code,
                successful = response.isSuccessful,
                contentTypeHeader = response.header("Content-Type"),
                body = response.body?.bytes() ?: ByteArray(0),
            )
        } finally {
            cancel?.unregister(call)
        }
    }

    internal fun parseTranscribe(
        code: Int,
        body: String,
    ): TranscribeResponse {
        try {
            val parsed = Json.parseToJsonElement(body).jsonObject

            val text =
                (parsed.getValue("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("text is not a string")
            val durationS = parsed.wholeCountOrNull("duration_s") ?: 0
            val usage = parsed.objectOrNull("usage")?.let { parseUsage(it) }

            val modelsVersion = parsed.stringOrNull("models_version")?.takeIf { it.isNotBlank() }

            val model = parsed.stringOrNull("model")?.takeIf { it.isNotBlank() }
            return TranscribeResponse(text, durationS, usage, modelsVersion, model)
        } catch (e: Exception) {
            val shape = if (body.trimStart().startsWith("{")) "json-like" else "not-json"
            throw GatewayError(
                code,
                "bad_response",
                body = "unparsable $shape response; ${body.length} chars",
            )
        }
    }

    internal class HttpOutcome(
        val code: Int,
        val successful: Boolean,
        val contentTypeHeader: String?,
        val body: ByteArray,
    ) {
        fun contentType(): String = contentTypeHeader?.substringBefore(";")?.trim().orEmpty()
    }

    fun fetchModels(): List<CloudModelInfo> {
        val request =
            Request.Builder()
                .url(endpoint("/v1/models"))
                .identify()
                .get()
                .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) throw java.io.IOException("GET /v1/models returned ${response.code}")

        val parsed = Json.parseToJsonElement(body).jsonObject
        val models =
            parsed["models"] as? JsonArray
                ?: throw java.io.IOException("GET /v1/models has no models")
        return models.mapNotNull { elem ->

            val obj = elem as? JsonObject ?: return@mapNotNull null
            CloudModelInfo(
                id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                displayName = obj.stringOrNull("display_name") ?: "",
                provider = obj.stringOrNull("provider") ?: "",
                description = obj.stringOrNull("description") ?: "",
                accuracyScore = obj.floatOrNull("accuracy_score") ?: 0f,
                speedScore = obj.floatOrNull("speed_score") ?: 0f,
                pricePerHour = obj.floatOrNull("price_per_hour") ?: 0f,
                usageMultiplier = obj.floatOrNull("usage_multiplier") ?: 1f,
                supportedLanguages =
                    (obj["supported_languages"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                supportsTranslation = obj.booleanOrNull("supports_translation") ?: false,
                providerLabel = obj.stringOrNull("provider_label") ?: "",
                isRecommended = obj.booleanOrNull("is_recommended") ?: false,
                trainingUse = obj.stringOrNull("training_use") ?: "unknown",
                retentionKind = obj.stringOrNull("retention_kind") ?: "unknown",
                retentionDays = obj.wholeCountOrNull("retention_days"),
            )
        }
    }

    fun fetchUsage(accessToken: String): CloudAccountStatus {
        val request =
            Request.Builder()
                .url(endpoint("/v1/usage"))
                .identify()
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw parseGatewayError(response.code, body)
        }
        try {
            val parsed = Json.parseToJsonElement(body).jsonObject
            val usage =
                parsed.objectOrNull("usage")?.let { parseUsage(it) }
                    ?: throw GatewayError(422, "usage_missing", body = body)
            val subscription = parsed.objectOrNull("subscription")
            return CloudAccountStatus(
                usage = usage,
                planName = parsed.objectOrNull("plan")?.stringOrNull("name"),
                subscriptionStatus = subscription?.stringOrNull("status"),
                currentPeriodEnd = subscription?.stringOrNull("current_period_end"),
            )
        } catch (e: GatewayError) {
            throw e
        } catch (e: Exception) {
            throw GatewayError(response.code, "bad_response", body = body)
        }
    }

    internal fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    internal fun Request.Builder.identify(): Request.Builder =
        if (clientId.isEmpty()) this else header(ANONEN_CLIENT_HEADER, clientId)

    private fun parseUsage(obj: JsonObject): UsageSnapshot =
        UsageSnapshot(
            weekUsedS = obj.wholeCountOrNull("week_used_s") ?: 0,
            weekCapS = obj.wholeCountOrNull("week_cap_s") ?: 0,
            weekResetsAt = obj.stringOrNull("week_resets_at") ?: "",
            monthUsedS = obj.wholeCountOrNull("month_used_s") ?: 0,
            monthCapS = obj.wholeCountOrNull("month_cap_s") ?: 0,
            monthResetsAt = obj.stringOrNull("month_resets_at") ?: "",
            maxRequestS = obj.wholeCountOrNull("max_request_s"),
            weekCapFullS = obj.wholeCountOrNull("week_cap_full_s"),
        )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val SEALED_MEDIA_TYPE = SealedWire.CONTENT_TYPE.toMediaType()
    }

    internal fun parseGatewayError(
        httpStatus: Int,
        body: String,
    ): GatewayError {
        return try {
            val parsed = Json.parseToJsonElement(body).jsonObject

            GatewayError(
                httpStatus = httpStatus,
                code = GatewayError.codeFromWire(parsed.stringOrNull("error")),
                which = parsed.stringOrNull("which"),
                resetsAt = parsed.stringOrNull("resets_at"),
                fallback = parsed.stringOrNull("fallback"),
                body = body,
            )
        } catch (_: Exception) {
            GatewayError(httpStatus, "unknown", body = body)
        }
    }
}

class AudioUpload private constructor(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
) {
    companion object {
        fun wav(bytes: ByteArray) = AudioUpload(bytes, "audio/wav", "audio.wav")

        fun oggOpus(bytes: ByteArray) = AudioUpload(bytes, "audio/ogg", "audio.ogg")
    }
}

data class TranscribeResponse(
    val text: String,
    val durationS: Int,
    val usage: UsageSnapshot?,
    val modelsVersion: String? = null,
    val model: String? = null,
)

data class UsageSnapshot(
    val weekUsedS: Int,
    val weekCapS: Int,
    val weekResetsAt: String,
    val monthUsedS: Int,
    val monthCapS: Int,
    val monthResetsAt: String,
    val maxRequestS: Int? = null,
    val weekCapFullS: Int? = null,
) {
    val weekRemainingS: Int get() = (weekCapS - weekUsedS).coerceAtLeast(0)
    val monthRemainingS: Int get() = (monthCapS - monthUsedS).coerceAtLeast(0)
    val remainingS: Int get() = minOf(weekRemainingS, monthRemainingS)

    val weekBarFullS: Int get() = maxOf(weekCapFullS ?: weekCapS, weekCapS)
}

data class CloudAccountStatus(
    val usage: UsageSnapshot,
    val planName: String?,
    val subscriptionStatus: String?,
    val currentPeriodEnd: String?,
)

data class CloudModelInfo(
    val id: String,
    val displayName: String,
    val provider: String,
    val description: String,
    val accuracyScore: Float,
    val speedScore: Float,
    val pricePerHour: Float,
    val usageMultiplier: Float = 1f,
    val supportedLanguages: List<String>,
    val supportsTranslation: Boolean,
    val providerLabel: String,
    val isRecommended: Boolean,
    val trainingUse: String = "unknown",
    val retentionKind: String = "unknown",
    val retentionDays: Int? = null,
)

internal fun requireCatalogModelId(model: String?): String {
    val id = model?.trim().orEmpty()
    val parts = id.split("/")
    if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
        throw GatewayError(
            httpStatus = 0,
            code = "client_misconfigured",
            body = "モデル id が \"provider/model_name\" の形ではありません: \"$id\"",
        )
    }
    return id
}

class GatewayError(
    val httpStatus: Int,
    val code: String,
    val which: String? = null,
    val resetsAt: String? = null,
    val fallback: String? = null,
    val body: String = "",
) : Exception("Gateway error $httpStatus/$code") {
    val isRetryable: Boolean
        get() =
            httpStatus in listOf(408, 409, 500, 502, 503) ||
                (httpStatus == 429 && code == "rate_limited")

    val isCapExceeded: Boolean get() = httpStatus == 429 && code == "cap_exceeded"

    companion object {
        val LOCAL_ONLY_CODES: Set<String> =
            setOf(
                "seal_unavailable",
                "seal_outdated",
                "seal_lost_after_send",
                "seal_outdated_after_send",
                "seal_refused",
                "seal_failed",
                "sealed_response_missing",
                "sealed_response_unreadable",
                "client_misconfigured",
                "bad_response",
                "usage_missing",
                "max_retries",
            )

        fun codeFromWire(raw: String?): String = raw?.takeUnless { it in LOCAL_ONLY_CODES } ?: "unknown"
    }

    val isAudioTooLong: Boolean get() = httpStatus == 413
    val isUnauthorized: Boolean get() = httpStatus == 401
    val isNoSubscription: Boolean get() = httpStatus == 402
    val isUnknownProvider: Boolean get() = httpStatus == 400 && code == "unknown_provider"
    val isUnknownModel: Boolean get() = httpStatus == 400 && code == "unknown_model"

    val isStaleEnclaveKey: Boolean get() = httpStatus == 409 && code == "stale_enclave_key"

    val isSealBroken: Boolean
        get() = code == "sealed_response_missing" || code == "sealed_response_unreadable"

    val isSealFailed: Boolean get() = code == "seal_failed"

    val isSealedRequired: Boolean get() = httpStatus == 400 && code == "sealed_required"

    val isSealUnavailable: Boolean get() = code == "seal_unavailable"

    val isSealLostAfterSend: Boolean get() = code == "seal_lost_after_send"

    val isSealOutdatedAfterSend: Boolean get() = code == "seal_outdated_after_send"

    val isSealRefused: Boolean get() = code == "seal_refused"

    val isSealOutdated: Boolean get() = code == "seal_outdated"

    val isModelInvalid: Boolean get() = isUnknownProvider || isUnknownModel
}

fun CloudModelInfo.processingDetail(): String? =

    providerLabel.ifEmpty {
        when (provider) {
            "alibaba", "alibaba-funasr" -> "Alibaba Cloud・シンガポールで処理"
            "openai" -> "OpenAI・米国で処理"
            "mistral" -> "Mistral AI・フランスで処理"
            else -> description.ifEmpty { provider }
        }
    }.ifEmpty { null }

fun CloudModelInfo.usageMultiplierNote(): UsageNote? {
    if (usageMultiplier == 1f) return null

    val percent = (abs(1f - usageMultiplier) * 100f).roundToInt()

    return if (usageMultiplier < 1f) {
        UsageNote("消費量 $percent% OFF", positive = true)
    } else {
        UsageNote("消費量 $percent% 増加", positive = false)
    }
}

data class UsageNote(val text: String, val positive: Boolean)
