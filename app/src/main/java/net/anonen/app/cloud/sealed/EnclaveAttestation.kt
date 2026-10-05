package net.anonen.app.cloud.sealed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

object EnclaveAttestation {
    const val ISSUER = "https://confidentialcomputing.googleapis.com"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val DEFAULT_AUDIENCE = "https://anonen.net/attestation/v1"

    private const val CLOCK_SKEW_SECONDS = 60L

    private const val REQUIRED_SUPPORT_ATTRIBUTE = "STABLE"

    const val IMAGE_DIGEST_LABEL = "イメージの digest"

    const val MALFORMED_LABEL = "申告の形"

    fun parseAcceptedDigests(raw: String): Set<String> =
        raw
            .split(',', ' ', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun verify(
        body: JsonObject,
        clientNonce: String,
        jwks: JsonArray,
        nowSeconds: Long,
        acceptedImageDigests: Set<String>,
        audience: String = DEFAULT_AUDIENCE,
    ): AttestationResult {
        val checks = mutableListOf<AttestationCheck>()
        val token = body["token"].stringOrNull()
        if (token.isNullOrBlank()) {
            return reject(checks, "token", "応答に token が無い")
        }
        val jwt =
            runCatching { Jwt.parse(token) }.getOrNull()
                ?: return reject(checks, "token", "JWT の形をしていない")

        val algorithm = jwt.header["alg"].stringOrNull()
        if (algorithm != "RS256") {
            return reject(checks, "署名アルゴリズム", "RS256 でない: $algorithm")
        }
        val kid = jwt.header["kid"].stringOrNull()
        val key =
            jwks.firstOrNull { (it as? JsonObject)?.get("kid").stringOrNull() == kid } as? JsonObject
                ?: return reject(checks, "署名鍵", "kid=$kid が Google の鍵束に無い")
        val signatureOk = runCatching { rs256Verify(key, jwt.signed, jwt.signature) }.getOrDefault(false)
        checks += AttestationCheck(signatureOk, "Google の署名", "kid=$kid")
        if (!signatureOk) return AttestationResult.Rejected(checks)

        val issuer = jwt.payload["iss"].stringOrNull()
        checks += AttestationCheck(issuer == ISSUER, "発行者", issuer.orEmpty())

        val issuedAt = jwt.payload["iat"].longOrZero()
        val expiresAt = jwt.payload["exp"].longOrZero()
        val fresh =
            nowSeconds >= issuedAt - CLOCK_SKEW_SECONDS &&
                nowSeconds <= expiresAt + CLOCK_SKEW_SECONDS
        checks += AttestationCheck(fresh, "有効期限", "残り ${expiresAt - nowSeconds} 秒")

        val publicKey = SealedWire.decodePublicKey(body["public_key"].stringOrNull())
        val declaredKeyId = body["key_id"].stringOrNull()
        val keyOk =
            publicKey != null &&
                declaredKeyId != null &&
                constantTimeEquals(declaredKeyId, SealedWire.keyIdHex(publicKey))
        checks += AttestationCheck(keyOk, "公開鍵の申告", "key_id=$declaredKeyId")

        val suite = body["suite"].stringOrNull()

        checks += AttestationCheck(suite == SealedWire.SUITE, "封緘スイート", suite.orEmpty())

        val bound = if (publicKey != null) SealedWire.boundNonce(clientNonce, publicKey) else ""
        checks +=
            AttestationCheck(
                publicKey != null && nonceMatches(jwt.payload["eat_nonce"], bound),
                "nonce と鍵の束縛",
                "SHA-256(nonce ‖ 公開鍵) = $bound",
            )

        val aud = jwt.payload["aud"].stringOrNull()
        checks += AttestationCheck(aud == audience, "audience", aud.orEmpty())

        val submods = jwt.payload["submods"] as? JsonObject
        val container = submods?.get("container") as? JsonObject
        val dbgstat = jwt.payload["dbgstat"].stringOrNull()
        checks += AttestationCheck(dbgstat == "disabled-since-boot", "デバッグ機能", dbgstat.orEmpty())

        val swname = jwt.payload["swname"].stringOrNull()
        checks += AttestationCheck(swname == "CONFIDENTIAL_SPACE", "実行環境", swname.orEmpty())

        checks += supportAttributesCheck(submods)

        val restart = container?.get("restart_policy").stringOrNull()
        checks += AttestationCheck(restart == "Never", "再起動ポリシー", restart.orEmpty())

        val imageDigest = container?.get("image_digest").stringOrNull().orEmpty()
        checks +=
            AttestationCheck(
                imageDigest.isNotEmpty() && imageDigest.lowercase() in acceptedImageDigests,
                IMAGE_DIGEST_LABEL,
                imageDigest.ifEmpty { "(申告なし)" },
            )

        if (checks.any { !it.ok } || publicKey == null) {
            return AttestationResult.Rejected(checks)
        }
        return AttestationResult.Verified(
            VerifiedEnclave(
                publicKey = publicKey,
                keyIdHex = SealedWire.keyIdHex(publicKey),
                imageDigest = imageDigest,
                expiresAtSeconds = expiresAt,
            ),
            checks,
        )
    }

    private fun supportAttributesCheck(submods: JsonObject?): AttestationCheck {
        val raw = (submods?.get("confidential_space") as? JsonObject)?.get("support_attributes")
        val attributes =
            (raw as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return AttestationCheck(
            REQUIRED_SUPPORT_ATTRIBUTE in attributes,
            "イメージの区分",
            if (attributes.isEmpty()) "(申告なし)" else attributes.joinToString(" "),
        )
    }

    private fun nonceMatches(
        claim: JsonElement?,
        expected: String,
    ): Boolean {
        if (claim == null || expected.isEmpty()) return false
        val values =
            when (claim) {
                is JsonArray -> claim.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> listOfNotNull((claim as? JsonPrimitive)?.contentOrNull)
            }
        return values.any { constantTimeEquals(it, expected) }
    }

    private fun rs256Verify(
        jwk: JsonObject,
        signed: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val modulus = BigInteger(1, SealedWire.b64UrlDecode(jwk["n"]!!.jsonPrimitive.content))
        val exponent = BigInteger(1, SealedWire.b64UrlDecode(jwk["e"]!!.jsonPrimitive.content))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(signed)
            verify(signature)
        }
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.longOrZero(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun reject(
        checks: MutableList<AttestationCheck>,
        label: String,
        detail: String,
    ): AttestationResult {
        checks += AttestationCheck(false, label, detail)
        return AttestationResult.Rejected(checks)
    }

    private class Jwt(
        val header: JsonObject,
        val payload: JsonObject,
        val signed: ByteArray,
        val signature: ByteArray,
    ) {
        companion object {
            fun parse(token: String): Jwt {
                val parts = token.split(".")
                require(parts.size == 3) { "JWT は 3 パート" }
                return Jwt(
                    header = decode(parts[0]),
                    payload = decode(parts[1]),
                    signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
                    signature = SealedWire.b64UrlDecode(parts[2]),
                )
            }

            private fun decode(part: String): JsonObject =
                Json.parseToJsonElement(SealedWire.b64UrlDecode(part).decodeToString()).jsonObject
        }
    }
}

class AttestationCheck(
    val ok: Boolean,
    val label: String,
    val detail: String,
)

class VerifiedEnclave(
    val publicKey: ByteArray,
    val keyIdHex: String,
    val imageDigest: String,
    val expiresAtSeconds: Long,
)

sealed interface AttestationResult {
    class Verified(
        val enclave: VerifiedEnclave,
        val checks: List<AttestationCheck>,
    ) : AttestationResult

    class Rejected(
        val checks: List<AttestationCheck>,
    ) : AttestationResult

    fun failedLabels(): String =
        when (this) {
            is Verified -> ""
            is Rejected -> checks.filter { !it.ok }.joinToString("/") { it.label }
        }

    fun rejectedOnlyForUnknownImage(): Boolean =
        this is Rejected &&
            checks.filter { !it.ok }.map { it.label } ==
            listOf(EnclaveAttestation.IMAGE_DIGEST_LABEL)
}
