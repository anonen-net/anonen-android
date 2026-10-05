package net.anonen.app.cloud.sealed

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object SealedWire {
    const val CONTENT_TYPE = "application/vnd.anonen.sealed"
    const val SUITE = "HPKE-X25519-HKDF-SHA256-CHACHA20POLY1305"

    const val PUBLIC_KEY_BYTES = 32

    private val MAGIC = "ANON1".toByteArray(Charsets.US_ASCII)
    private val TRANSCRIBE_INFO = "anonen/transcribe/v1".toByteArray(Charsets.US_ASCII)
    private val RESPONSE_AAD = "anonen/response/v1".toByteArray(Charsets.US_ASCII)
    private const val KEY_ID_BYTES = 16
    private const val RESPONSE_KEY_BYTES = 32
    private const val RESPONSE_NONCE_BYTES = 12
    private const val MAX_HEADER_BYTES = 8192

    private const val MAC_BITS = 128

    private val random = SecureRandom()

    fun seal(
        publicKey: ByteArray,
        requestId: String,
        model: String,
        language: String?,
        audio: ByteArray,
    ): SealedEnvelope {
        require(publicKey.size == PUBLIC_KEY_BYTES) {
            "public key must be $PUBLIC_KEY_BYTES bytes"
        }

        require(model.isNotBlank()) { "model is required (api-v1 R23)" }
        val responseKey = ByteArray(RESPONSE_KEY_BYTES).also { random.nextBytes(it) }
        val header =
            buildJsonObject {
                put("request_id", requestId)
                put("response_key", b64UrlNoPad(responseKey))
                put("model", model)
                if (!language.isNullOrBlank() && language != "auto") put("language", language)
            }.toString().toByteArray(Charsets.UTF_8)
        if (header.size > MAX_HEADER_BYTES) throw SealedException("header too large")

        val plaintext = ByteArray(4 + header.size + audio.size)
        plaintext[0] = (header.size ushr 24).toByte()
        plaintext[1] = (header.size ushr 16).toByte()
        plaintext[2] = (header.size ushr 8).toByte()
        plaintext[3] = header.size.toByte()
        header.copyInto(plaintext, 4)
        audio.copyInto(plaintext, 4 + header.size)

        val hpke = newHpke()
        val ctx = hpke.setupBaseS(hpke.deserializePublicKey(publicKey), TRANSCRIBE_INFO)

        val ciphertext =
            try {
                ctx.seal(EMPTY, plaintext)
            } catch (e: Exception) {
                throw SealedException("cannot seal request", e)
            }

        val body = ByteArray(MAGIC.size + KEY_ID_BYTES + ctx.encapsulation.size + ciphertext.size)
        var at = 0
        MAGIC.copyInto(body, at).also { at += MAGIC.size }
        keyId(publicKey).copyInto(body, at).also { at += KEY_ID_BYTES }
        ctx.encapsulation.copyInto(body, at).also { at += ctx.encapsulation.size }
        ciphertext.copyInto(body, at)
        return SealedEnvelope(body, responseKey)
    }

    fun openResponse(
        responseKey: ByteArray,
        body: ByteArray,
    ): ByteArray {
        if (body.size <= RESPONSE_NONCE_BYTES) throw SealedException("malformed sealed response")
        val nonce = body.copyOfRange(0, RESPONSE_NONCE_BYTES)
        val sealed = body.copyOfRange(RESPONSE_NONCE_BYTES, body.size)
        return try {
            val aead = ChaCha20Poly1305()
            aead.init(false, AEADParameters(KeyParameter(responseKey), MAC_BITS, nonce, RESPONSE_AAD))
            val out = ByteArray(aead.getOutputSize(sealed.size))
            var len = aead.processBytes(sealed, 0, sealed.size, out, 0)
            len += aead.doFinal(out, len)
            if (len == out.size) out else out.copyOf(len)
        } catch (e: Exception) {
            throw SealedException("cannot open sealed response", e)
        }
    }

    fun keyId(publicKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(KEY_ID_BYTES)

    fun keyIdHex(publicKey: ByteArray): String = keyId(publicKey).joinToString("") { "%02x".format(it) }

    fun boundNonce(
        clientNonce: String,
        publicKey: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(clientNonce.toByteArray(Charsets.UTF_8))
        digest.update(publicKey)
        return b64UrlNoPad(digest.digest())
    }

    fun newClientNonce(): String = b64UrlNoPad(ByteArray(24).also { random.nextBytes(it) })

    fun decodePublicKey(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        val raw = runCatching { b64UrlDecode(value) }.getOrNull() ?: return null
        return if (raw.size == PUBLIC_KEY_BYTES) raw else null
    }

    internal fun b64UrlNoPad(raw: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    internal fun b64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun newHpke() =
        HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )

    private val EMPTY = ByteArray(0)
}

class SealedEnvelope(
    val body: ByteArray,
    val responseKey: ByteArray,
)

class SealedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
