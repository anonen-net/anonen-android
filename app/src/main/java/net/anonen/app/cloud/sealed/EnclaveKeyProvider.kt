package net.anonen.app.cloud.sealed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.anonen.app.cloud.ANONEN_CLIENT_HEADER
import net.anonen.app.core.DiagnosticsLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

interface EnclaveKeys {
    fun prefetch()

    fun hasValidCache(): Boolean = false

    fun acquire(): VerifiedEnclave?

    fun reacquire(): VerifiedEnclave?

    val lastRefusalWasUnknownImage: Boolean
}

class EnclaveKeyProvider(
    private val baseUrl: String,
    client: OkHttpClient,
    private val acceptedImageDigests: Set<String>,
    private val clientId: String = "",
    private val audience: String = EnclaveAttestation.DEFAULT_AUDIENCE,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val discoveryUrl: String = EnclaveAttestation.DISCOVERY_URL,
) : EnclaveKeys {
    private val http = client.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private class Cached(
        val enclave: VerifiedEnclave,
        val reuseUntilSeconds: Long,
    )

    private val cached = AtomicReference<Cached?>(null)

    private fun cachedRemainingSeconds(): Long? {
        val c = cached.get() ?: return null
        val until = minOf(c.reuseUntilSeconds, c.enclave.expiresAtSeconds)
        val now = nowSeconds()
        return if (until > now) until - now else null
    }

    override fun hasValidCache(): Boolean = cachedValid() != null

    private fun cachedValid(): VerifiedEnclave? {
        val c = cached.get() ?: return null
        val now = nowSeconds()
        return if (now < c.reuseUntilSeconds && now < c.enclave.expiresAtSeconds) c.enclave else null
    }

    private fun fetchAndCache(): VerifiedEnclave? {
        val fresh = fetchAndVerify() ?: return null
        cached.set(Cached(fresh, nowSeconds() + REUSE_SECONDS))
        return fresh
    }

    private class CachedJwks(
        val keys: JsonArray,
        val refetchAtSeconds: Long,
    )

    @Volatile private var jwks: CachedJwks? = null

    private fun cachedJwks(): JsonArray? = jwks?.takeIf { nowSeconds() < it.refetchAtSeconds }?.keys

    @Volatile private var unsupportedUntilSeconds = 0L

    @Volatile private var unknownImage = false

    override val lastRefusalWasUnknownImage: Boolean
        get() = unknownImage

    override fun prefetch() {
        val left = cachedRemainingSeconds()
        if (left != null && left > REFRESH_MARGIN_SECONDS) return
        fetchAndCache()
    }

    override fun acquire(): VerifiedEnclave? = cachedValid() ?: fetchAndCache()

    override fun reacquire(): VerifiedEnclave? {
        cached.set(null)
        return fetchAndCache()
    }

    private fun fetchAndVerify(): VerifiedEnclave? {
        unknownImage = false
        if (nowSeconds() < unsupportedUntilSeconds) return null
        val nonce = SealedWire.newClientNonce()
        val body =
            runCatching {
                Json
                    .parseToJsonElement(
                        get("$baseUrl/v1/attestation?nonce=$nonce", identify = true),
                    ).jsonObject
            }.getOrElse {
                if (it is AttestationUnsupported) {
                    unsupportedUntilSeconds = nowSeconds() + UNSUPPORTED_COOLDOWN_SECONDS
                    DiagnosticsLog.log(
                        "封緘: このサーバーは封緘に未対応（${UNSUPPORTED_COOLDOWN_SECONDS}秒は試さない）",
                    )
                } else {
                    DiagnosticsLog.log("封緘: 申告を取得できず（${it.javaClass.simpleName}）")
                }
                return null
            }

        var keys = cachedJwks() ?: fetchJwks() ?: return null
        var result = verify(body, nonce, keys)
        if (result is AttestationResult.Rejected && result.failedLabels() == "署名鍵") {
            keys = fetchJwks() ?: return null
            result = verify(body, nonce, keys)
        }

        return when (result) {
            is AttestationResult.Verified -> {
                DiagnosticsLog.log(
                    "封緘: 検証 OK key_id=${result.enclave.keyIdHex} " +
                        "image=${result.enclave.imageDigest}",
                )
                result.enclave
            }
            is AttestationResult.Rejected -> {
                unknownImage = result.rejectedOnlyForUnknownImage()
                if (unknownImage) {
                    DiagnosticsLog.log("封緘: このイメージを受理リストに持っていない — アプリが古い")
                } else {
                    DiagnosticsLog.log("封緘: 検証 NG（${result.failedLabels()}）— この鍵では封じない")
                }
                null
            }
        }
    }

    private fun verify(
        body: JsonObject,
        nonce: String,
        keys: JsonArray,
    ): AttestationResult =
        runCatching {
            EnclaveAttestation.verify(body, nonce, keys, nowSeconds(), acceptedImageDigests, audience)
        }.getOrElse {
            AttestationResult.Rejected(
                listOf(
                    AttestationCheck(
                        ok = false,
                        label = EnclaveAttestation.MALFORMED_LABEL,
                        detail = it.javaClass.simpleName,
                    ),
                ),
            )
        }

    private fun fetchJwks(): JsonArray? =
        runCatching {
            val discovery = Json.parseToJsonElement(get(discoveryUrl)).jsonObject
            val uri = discovery["jwks_uri"]!!.jsonPrimitive.content
            Json.parseToJsonElement(get(uri)).jsonObject["keys"]!!.jsonArray
        }.onSuccess { jwks = CachedJwks(it, nowSeconds() + JWKS_TTL_SECONDS) }
            .onFailure { DiagnosticsLog.log("封緘: Google の鍵束を取得できず（${it.javaClass.simpleName}）") }
            .getOrNull()

    private fun get(
        url: String,
        identify: Boolean = false,
    ): String {
        val builder = Request.Builder().url(url).get()
        if (identify && clientId.isNotEmpty()) builder.header(ANONEN_CLIENT_HEADER, clientId)
        val response = http.newCall(builder.build()).execute()
        response.use {
            val text = it.body?.string().orEmpty()

            if (it.code == 501 || it.code == 404) throw AttestationUnsupported()
            check(it.isSuccessful) { "HTTP ${it.code}" }
            return text
        }
    }

    private class AttestationUnsupported : Exception()

    companion object {
        const val TIMEOUT_SECONDS = 10L

        const val UNSUPPORTED_COOLDOWN_SECONDS = 300L

        const val REUSE_SECONDS = 3000L

        const val REFRESH_MARGIN_SECONDS = 600L

        const val JWKS_TTL_SECONDS = 3600L
    }
}
