package net.anonen.app.cloud

import net.anonen.app.asr.TranscriptionEngine
import net.anonen.app.cloud.sealed.EnclaveKeys
import net.anonen.app.core.AsrError
import net.anonen.app.core.DiagnosticsLog
import net.anonen.app.core.LanguageHints
import net.anonen.app.core.TranscriptionRequest
import net.anonen.app.core.TranscriptionResult
import java.io.IOException

class AnonenCloudEngine(
    private val auth: AnonenCloudAuth,
    private val gatewayClient: AnonenCloudClient,
    private val cloudModelId: String?,
    private val onUsageUpdate: ((UsageSnapshot) -> Unit)? = null,
    private val onModelInvalid: (() -> Unit)? = null,
    private val onModelsVersion: ((String) -> Unit)? = null,
    private val onModelUsed: ((String) -> Unit)? = null,
    private val onNoSubscription: (() -> Unit)? = null,
    private val isOnline: () -> Boolean = { true },
    private val cancel: TranscribeCancelController? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val opusEncode: ((ByteArray) -> ByteArray?)? = null,
    private val enclaveKeys: EnclaveKeys?,
    private val sealedRequired: Boolean,
) : TranscriptionEngine {
    override fun transcribe(request: TranscriptionRequest): TranscriptionResult {
        if (!isOnline()) {
            DiagnosticsLog.log("転写中止: オフライン（接続確認）")
            return TranscriptionResult.Failure(
                AsrError(AsrError.Kind.NETWORK, "あのねんにつながりません", "offline (pre-check)"),
            )
        }
        val language = LanguageHints.normalize(request.languageHint)
        val startedAt = System.currentTimeMillis()

        return try {
            val text =
                transcribeWithRetry(
                    prepareUpload(request.wavBytes),
                    cloudModelId,
                    language.ifBlank { null },
                    requestTimeoutSecs(request.wavBytes),
                )
            TranscriptionResult.Success(text, System.currentTimeMillis() - startedAt)
        } catch (e: GatewayError) {
            TranscriptionResult.Failure(gatewayErrorToAsrError(e))
        } catch (e: AnonenCloudAuthException) {
            TranscriptionResult.Failure(
                AsrError(AsrError.Kind.CONFIG, e.message ?: "ログインできませんでした", "auth: ${e.message}"),
            )
        } catch (e: IOException) {
            TranscriptionResult.Failure(
                AsrError(
                    AsrError.Kind.NETWORK,
                    "あのねんにつながりません",
                    "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                ),
            )
        }
    }

    private fun prepareUpload(wavBytes: ByteArray): AudioUpload {
        val encoder = opusEncode
        if (encoder == null) {
            DiagnosticsLog.log("WAV 送信 ${wavBytes.size}B（Opus エンコーダ未注入）")
            return AudioUpload.wav(wavBytes)
        }
        val startedAt = System.currentTimeMillis()
        val opus = encoder(wavBytes)
        return if (opus != null) {
            val ms = System.currentTimeMillis() - startedAt
            DiagnosticsLog.log("Opus 圧縮 ${wavBytes.size}B → ${opus.size}B ${ms}ms")
            AudioUpload.oggOpus(opus)
        } else {
            DiagnosticsLog.log("WAV 送信 ${wavBytes.size}B（Opus 変換不可 → 縮退）")
            AudioUpload.wav(wavBytes)
        }
    }

    private fun transcribeWithRetry(
        audio: AudioUpload,
        model: String?,
        language: String?,
        timeoutSecs: Long,
    ): String {
        var lastError: GatewayError? = null
        var authRetried = false
        var staleRetried = false

        val keyWasCached = enclaveKeys?.hasValidCache() == true
        val keyStartedAt = System.currentTimeMillis()
        var enclave = enclaveKeys?.acquire()
        if (enclaveKeys != null) {
            DiagnosticsLog.log(
                "封緘鍵: ${System.currentTimeMillis() - keyStartedAt}ms " +
                    "(cache=${if (keyWasCached) "hit" else "miss"}, got=${enclave != null})",
            )
        }

        if (cancel?.isCancelled == true) {
            DiagnosticsLog.log("転写中断: ユーザーキャンセル（封緘鍵の検証中）")
            throw java.io.InterruptedIOException("transcribe cancelled by user")
        }
        if (enclave == null && sealedRequired) {
            if (enclaveKeys?.lastRefusalWasUnknownImage == true) {
                DiagnosticsLog.log("転写中止: サーバーのイメージを受理できない — アプリが古い")
                throw GatewayError(0, "seal_outdated", body = "")
            }
            DiagnosticsLog.log("転写中止: 封じる鍵を確認できず（SEALED_REQUIRED）")
            throw GatewayError(0, "seal_unavailable", body = "")
        }

        val sealedOnce = enclave != null

        for (attempt in 0 until MAX_ATTEMPTS) {
            if (attempt > 0 && lastError != null) {
                Thread.sleep(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
            }

            if (cancel?.isCancelled == true) {
                DiagnosticsLog.log("転写中断: ユーザーキャンセル（attempt=$attempt）")
                throw java.io.InterruptedIOException("transcribe cancelled by user")
            }

            val token = auth.getAccessToken()

            try {
                val response =
                    gatewayClient.transcribe(token, audio, model, language, timeoutSecs, cancel, enclave)

                notifyQuietly("usage") { response.usage?.let { onUsageUpdate?.invoke(it) } }
                notifyQuietly("models_version") {
                    response.modelsVersion?.let { onModelsVersion?.invoke(it) }
                }
                notifyQuietly("model") { response.model?.let { onModelUsed?.invoke(it) } }
                return response.text
            } catch (e: GatewayError) {
                if (e.isStaleEnclaveKey && sealedOnce) {
                    if (staleRetried) {
                        DiagnosticsLog.log("転写中止: 鍵を取り直しても enclave が入れ替わり続ける")
                        throw e
                    }
                    staleRetried = true
                    val renewed = enclaveKeys?.reacquire()
                    if (renewed == null) {
                        if (enclaveKeys?.lastRefusalWasUnknownImage == true) {
                            DiagnosticsLog.log("転写中止: 鍵の取り直しに失敗（受理できないイメージ — アプリが古い）")
                            throw GatewayError(0, "seal_outdated_after_send", body = "")
                        }
                        DiagnosticsLog.log("転写中止: 鍵の取り直しに失敗（enclave 入れ替え）")
                        throw GatewayError(0, "seal_lost_after_send", body = "")
                    }
                    enclave = renewed
                    continue
                }
                if (e.isSealFailed) {
                    DiagnosticsLog.log("転写中止: 音声を封じられない（${e.code}）")
                    throw e
                }
                if (e.isSealBroken) {
                    DiagnosticsLog.log("転写中止: 応答が封じられていない（${e.code}）")
                    throw e
                }
                if (e.isSealedRequired && sealedOnce) {
                    DiagnosticsLog.log("転写中止: 封を剥がされた疑い（sealed_required）")
                    throw GatewayError(e.httpStatus, "seal_refused", body = "")
                }
                if (e.isUnauthorized && !authRetried) {
                    auth.invalidateAccessToken()
                    authRetried = true
                    continue
                }
                if (e.isModelInvalid) {
                    onModelInvalid?.invoke()
                    throw e
                }
                if (e.isNoSubscription) {
                    onNoSubscription?.invoke()
                    throw e
                }
                if (e.isRetryable && attempt < MAX_ATTEMPTS - 1) {
                    lastError = e
                    continue
                }
                throw e
            }
        }

        throw lastError ?: GatewayError(500, "max_retries", body = "Max retries exceeded")
    }

    private inline fun notifyQuietly(
        label: String,
        block: () -> Unit,
    ) {
        runCatching(block)
            .onFailure { DiagnosticsLog.log("転写後の通知に失敗 $label: ${it.javaClass.simpleName}") }
    }

    internal fun userMessageFor(e: GatewayError): String = gatewayErrorToAsrError(e).userMessage

    private fun gatewayErrorToAsrError(e: GatewayError): AsrError {
        val kind =
            when {
                e.isNoSubscription -> AsrError.Kind.CONFIG
                e.isCapExceeded -> AsrError.Kind.HTTP
                e.isModelInvalid -> AsrError.Kind.CONFIG
                e.isSealUnavailable || e.isSealOutdated || e.isSealFailed ||
                    e.isSealLostAfterSend || e.isSealOutdatedAfterSend ||
                    e.isSealBroken || e.isSealRefused || e.isSealedRequired ->
                    AsrError.Kind.CONFIG
                else -> AsrError.Kind.HTTP
            }
        val userMessage =
            when {
                e.isNoSubscription -> "あのねんの契約が必要です。anonen.net からご契約ください"
                e.isCapExceeded -> {
                    val cap = if (e.which == "week") "今週" else "この 28 日"
                    val until = formatTimeUntilReset(e.resetsAt, nowMillis())
                    val head =
                        if (until != null) {
                            "${cap}の分を使い切りました（${until}でまた使えます）"
                        } else {
                            "${cap}の分を使い切りました"
                        }

                    if (e.fallback == "local") {
                        "$head。スマホの中のモデルなら続けられます"
                    } else {
                        head
                    }
                }

                e.isAudioTooLong -> "1 回の録音が長すぎました（スマホの中のモデルなら使えます）"
                e.isUnauthorized -> "ログインが切れました"

                e.isSealUnavailable -> "安全に送れる状態を確認できませんでした（音声は送っていません）"

                e.isSealOutdated ->
                    "このアプリでは、いまのサーバーを確認できません。" +
                        "アプリを最新版に更新してください（音声は送っていません）"

                e.isSealFailed -> "音声を保護できなかったため、送信を中止しました"

                e.isSealLostAfterSend ->
                    "サーバーに送り直せませんでした。もう一度お試しください（音声は保護して送りました）"
                e.isSealOutdatedAfterSend ->
                    "このアプリでは、いまのサーバーを確認できません。" +
                        "アプリを最新版に更新してください（音声は保護して送りました）"
                e.isSealBroken ->
                    "保護された応答を受け取れませんでした。時間をおいて試してください（音声は保護して送りました）"
                e.isSealRefused ->
                    "サーバーが、保護された送信として受け付けませんでした。" +
                        "時間をおいて試してください（音声は保護して送りました）"

                e.isSealedRequired -> "サーバーが、保護されていない送信を受け付けませんでした"

                e.isModelInvalid -> "このモデルはもう使えません"

                e.code == "bad_response" -> "あのねんの返事を読めませんでした"
                e.httpStatus == 502 -> "あのねんが一時的に使えません"
                e.httpStatus == 503 -> "あのねんが混み合っています"
                else -> "文字起こしできませんでした"
            }
        return AsrError(
            kind = kind,
            userMessage = userMessage,
            detail = "gateway ${e.httpStatus}/${e.code}",
            httpStatus = e.httpStatus,
            fallbackHint = if (e.isCapExceeded) e.fallback else null,
            capResetAt = if (e.isCapExceeded) e.resetsAt else null,
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(0, 1000, 2000)

        private const val WAV_BYTES_PER_SEC = 32_000L

        internal fun requestTimeoutSecs(wavBytes: ByteArray): Long {
            val audioSecs = (wavBytes.size + WAV_BYTES_PER_SEC - 1) / WAV_BYTES_PER_SEC
            return (30L + audioSecs).coerceAtMost(120L)
        }
    }
}

internal fun formatTimeUntilReset(
    resetsAt: String?,
    nowMillis: Long,
): String? {
    if (resetsAt.isNullOrBlank()) return null
    val resetMillis =
        runCatching { java.time.Instant.parse(resetsAt).toEpochMilli() }
            .getOrNull() ?: return null
    val remainMinutes = (resetMillis - nowMillis) / 60_000
    if (remainMinutes <= 0) return null
    val days = remainMinutes / (24 * 60)
    val hours = (remainMinutes % (24 * 60)) / 60
    val minutes = remainMinutes % 60
    return when {
        days > 0 -> "あと${days}日${hours}時間"
        hours > 0 -> "あと${hours}時間${minutes}分"
        else -> "あと${minutes}分"
    }
}
