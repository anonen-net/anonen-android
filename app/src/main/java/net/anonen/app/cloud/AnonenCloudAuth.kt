package net.anonen.app.cloud

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.anonen.app.core.DiagnosticsLog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AnonenCloudAuth(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val secrets: SharedPreferences,
    client: OkHttpClient? = null,
    private val isOnline: () -> Boolean = { true },
    private val onSessionEstablished: () -> Unit = {},
    private val onSessionInvalidated: () -> Unit = {},
) {
    private val http: OkHttpClient =
        (
            client ?: OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        ).newBuilder()
            .callTimeout(AUTH_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var accessToken: AccessToken? = null

    private val refreshLock = Any()

    private val stateLock = Any()

    private val sessionGeneration = AtomicLong(0)

    data class AuthStatus(
        val signedIn: Boolean,
        val email: String?,
        val userId: String? = null,
    )

    fun status(): AuthStatus {
        val token = accessToken
        val hasFresh = token?.isFresh() == true
        val hasRefresh = loadRefreshToken() != null
        return AuthStatus(
            signedIn = hasFresh || hasRefresh,
            email = loadEmail(),
            userId = token?.let { userIdFromJwt(it.token) },
        )
    }

    fun requestOtp(
        email: String,
        captchaToken: String? = null,
    ) {
        ensureReady()
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/otp"
        val body = otpRequestBody(email, captchaToken)
        val request =
            Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

        val response =
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw AnonenCloudAuthException(networkErrorMessage(e))
            }
        response.use { r ->
            if (!r.isSuccessful) {
                throw AnonenCloudAuthException(otpSendErrorMessage(r.code))
            }
        }
    }

    private fun otpSendErrorMessage(code: Int): String {
        DiagnosticsLog.log("確認コードを送れない http=$code")
        return when (code) {
            429 -> "何回も送ったので、少し待ってください"
            in 500..599 -> "メールを送れません。少し待ってください"
            else -> "メールを送れませんでした"
        }
    }

    private fun networkErrorMessage(e: IOException): String =
        if (e is InterruptedIOException) {
            "時間がかかりすぎました"
        } else {
            "ネットにつながりません"
        }

    fun verifyOtp(
        email: String,
        token: String,
    ) {
        ensureReady()
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/verify"

        val body =
            buildJsonObject {
                put("email", email)
                put("token", token)
                put("type", "email")
            }.toString()

        val request =
            Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val response =
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw AnonenCloudAuthException(networkErrorMessage(e))
            }
        val responseBody = response.body.stringAtMost(MAX_SMALL_RESPONSE_BYTES)
        if (!response.isSuccessful) {
            DiagnosticsLog.log("確認コードを確かめられない http=${response.code}")
            throw AnonenCloudAuthException(
                when (response.code) {
                    429 -> TOO_MANY_TRIES

                    400, 401, 403 -> "確認コードが違うか、古くなっています。一番新しいメールのコードを入れてください"
                    else -> "うまく確かめられませんでした"
                },
            )
        }
        consumeSessionResponse(responseBody)
    }

    private fun ensureReady() {
        if (supabaseUrl.isBlank()) throw AnonenCloudAuthException("Supabase URL が未設定です")
        if (!isOnline()) throw AnonenCloudAuthException(OFFLINE_MESSAGE)
    }

    fun signInWithPassword(
        email: String,
        password: String,
        captchaToken: String? = null,
    ) {
        ensureReady()
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=password"
        val body = passwordRequestBody(email, password, captchaToken)
        val request =
            Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val response =
            try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw AnonenCloudAuthException(networkErrorMessage(e))
            }
        val responseBody = response.body.stringAtMost(MAX_SMALL_RESPONSE_BYTES)
        if (!response.isSuccessful) {
            DiagnosticsLog.log("パスワードでログインできない http=${response.code}")
            throw AnonenCloudAuthException(
                when (response.code) {
                    429 -> TOO_MANY_TRIES
                    400, 401, 403 -> "メールアドレスかパスワードが違います"
                    else -> LOGIN_FAILED
                },
            )
        }
        consumeSessionResponse(responseBody)
    }

    fun getAccessToken(): String {
        accessToken?.let { if (it.isFresh()) return it.token }

        synchronized(refreshLock) {
            accessToken?.let { if (it.isFresh()) return it.token }

            val (generation, stored) = synchronized(stateLock) { sessionGeneration.get() to loadRefreshToken() }
            val refresh = stored ?: throw AnonenCloudAuthException(SIGN_IN_REQUIRED)
            val renewed =
                try {
                    refreshAccessToken(refresh)
                } catch (e: RefreshRejected) {
                    val stillSame =
                        synchronized(stateLock) {
                            (sessionGeneration.get() == generation).also { if (it) clearRefreshToken() }
                        }

                    if (stillSame) onSessionInvalidated()
                    throw AnonenCloudAuthException("ログインが切れました")
                }
            val kept =
                synchronized(stateLock) {
                    if (sessionGeneration.get() != generation) {
                        false
                    } else {
                        storeRefreshToken(renewed.refresh)
                        accessToken = renewed.access
                        true
                    }
                }
            if (!kept) {
                DiagnosticsLog.log("更新の間にサインアウトされた → 新しい token は保存せず失効させる")
                revokeSessionAsync(renewed.access.token, null)
                throw AnonenCloudAuthException(SIGN_IN_REQUIRED)
            }
            return renewed.access.token
        }
    }

    fun invalidateAccessToken() {
        accessToken = null
    }

    fun logout() {
        val (fresh, refresh) =
            synchronized(stateLock) {
                val fresh = accessToken?.takeIf { it.isFresh() }?.token
                val refresh = if (fresh == null) loadRefreshToken() else null
                sessionGeneration.incrementAndGet()
                clearRefreshToken()
                clearEmail()
                accessToken = null
                fresh to refresh
            }
        revokeSessionAsync(fresh, refresh)
    }

    private fun revokeSessionAsync(
        token: String?,
        refreshToken: String?,
    ) {
        if (supabaseUrl.isBlank()) return
        if (!token.isNullOrEmpty()) {
            postLogout(token)
            return
        }
        if (refreshToken.isNullOrEmpty()) return
        http.newCall(refreshRequest(refreshToken)).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = Unit

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val refreshed =
                        response.use { r ->
                            if (!r.isSuccessful) return
                            runCatching {
                                Json.parseToJsonElement(r.body.stringAtMost(MAX_SMALL_RESPONSE_BYTES))
                                    .jsonObject["access_token"]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                    if (!refreshed.isNullOrEmpty()) postLogout(refreshed)
                }
            },
        )
    }

    private fun postLogout(token: String) {
        val request =
            Request.Builder()
                .url("${supabaseUrl.trimEnd('/')}/auth/v1/logout?scope=local")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = Unit

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) = response.close()
            },
        )
    }

    fun isSignedIn(): Boolean = status().signedIn

    private fun refreshRequest(refreshToken: String): Request {
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token"
        val body = buildJsonObject { put("refresh_token", refreshToken) }.toString()
        return Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private class RefreshRejected : Exception()

    private class Renewed(
        val access: AccessToken,
        val refresh: String,
    )

    private fun refreshAccessToken(refreshToken: String): Renewed {
        if (!isOnline()) throw AnonenCloudAuthException(OFFLINE_MESSAGE)
        val request = refreshRequest(refreshToken)

        val response: okhttp3.Response
        try {
            response = http.newCall(request).execute()
        } catch (e: IOException) {
            throw AnonenCloudAuthException(networkErrorMessage(e))
        }

        val responseBody = response.body.stringAtMost(MAX_SMALL_RESPONSE_BYTES)
        if (!response.isSuccessful) {
            if (response.code == 400 || response.code == 401) throw RefreshRejected()
            DiagnosticsLog.log("ログインを続けられない http=${response.code}")
            throw AnonenCloudAuthException("ログインできませんでした。少し待ってください")
        }

        val access: String
        val newRefresh: String
        val expiresIn: Long
        try {
            val parsed = Json.parseToJsonElement(responseBody).jsonObject
            access =
                parsed["access_token"]?.jsonPrimitive?.content
                    ?: throw AnonenCloudAuthException(LOGIN_FAILED)
            newRefresh =
                parsed["refresh_token"]?.jsonPrimitive?.content
                    ?: throw AnonenCloudAuthException(LOGIN_FAILED)
            expiresIn = parsed["expires_in"]?.jsonPrimitive?.long ?: 3600L
        } catch (e: AnonenCloudAuthException) {
            throw e
        } catch (e: Exception) {
            throw AnonenCloudAuthException(LOGIN_FAILED)
        }

        return Renewed(AccessToken(access, System.currentTimeMillis() + expiresIn * 1000), newRefresh)
    }

    private fun consumeSessionResponse(body: String) {
        val access: String
        val refresh: String
        val expiresIn: Long
        val email: String?
        try {
            val parsed = Json.parseToJsonElement(body).jsonObject
            access =
                parsed["access_token"]?.jsonPrimitive?.content
                    ?: throw AnonenCloudAuthException(LOGIN_FAILED)
            refresh =
                parsed["refresh_token"]?.jsonPrimitive?.content
                    ?: throw AnonenCloudAuthException(LOGIN_FAILED)
            expiresIn = parsed["expires_in"]?.jsonPrimitive?.long ?: 3600L
            email =
                (parsed["user"] as? JsonObject)
                    ?.get("email")?.jsonPrimitive?.contentOrNull
        } catch (e: AnonenCloudAuthException) {
            throw e
        } catch (e: Exception) {
            throw AnonenCloudAuthException(LOGIN_FAILED)
        }

        synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
            storeRefreshToken(refresh)

            if (email != null) storeEmail(email) else clearEmail()
            accessToken = AccessToken(access, System.currentTimeMillis() + expiresIn * 1000)
        }

        onSessionEstablished()
    }

    private fun loadRefreshToken(): String? = secrets.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotEmpty() }

    private fun storeRefreshToken(token: String) {
        secrets.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    private fun clearRefreshToken() {
        secrets.edit().remove(KEY_REFRESH_TOKEN).apply()
    }

    private fun loadEmail(): String? = secrets.getString(KEY_EMAIL, null)?.takeIf { it.isNotEmpty() }

    private fun storeEmail(email: String) {
        secrets.edit().putString(KEY_EMAIL, email).apply()
    }

    private fun clearEmail() {
        secrets.edit().remove(KEY_EMAIL).apply()
    }

    private data class AccessToken(val token: String, val expiresAtMillis: Long) {
        fun isFresh(): Boolean = System.currentTimeMillis() + REFRESH_SKEW_MS < expiresAtMillis
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val REFRESH_SKEW_MS = 60_000L
        private const val OFFLINE_MESSAGE = "ネットにつながっていません"

        private const val SIGN_IN_REQUIRED = "あのねんにログインしてください"

        private const val TOO_MANY_TRIES = "何回も入れたので、少し待ってください"
        internal const val LOGIN_FAILED = "ログインできませんでした"

        private const val AUTH_CALL_TIMEOUT_S = 30L
        private const val KEY_REFRESH_TOKEN = "handy_cloud_refresh_token"
        private const val KEY_EMAIL = "handy_cloud_email"
    }
}

internal fun userIdFromJwt(token: String): String? {
    val payload = token.split('.').getOrNull(1) ?: return null
    return runCatching {
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        Json.parseToJsonElement(json).jsonObject["sub"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal fun otpRequestBody(
    email: String,
    captchaToken: String?,
): String =
    buildJsonObject {
        put("email", email)

        captchaToken?.takeIf { it.isNotBlank() }?.let {
            putJsonObject("gotrue_meta_security") { put("captcha_token", it) }
        }
    }.toString()

internal fun passwordRequestBody(
    email: String,
    password: String,
    captchaToken: String?,
): String =
    buildJsonObject {
        put("email", email)
        put("password", password)
        captchaToken?.takeIf { it.isNotBlank() }?.let {
            putJsonObject("gotrue_meta_security") { put("captcha_token", it) }
        }
    }.toString()

class AnonenCloudAuthException(message: String) : Exception(message)
