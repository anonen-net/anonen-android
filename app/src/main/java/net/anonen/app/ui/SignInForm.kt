package net.anonen.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.anonen.app.cloud.AnonenCloudAuth
import net.anonen.app.core.EmailFormat
import java.io.InterruptedIOException

internal const val RESEND_WAIT_MS = 60_000L

internal const val LOGIN_WITH_EMAIL = "メールアドレスでログイン"

internal const val BACK_LABEL = "戻る"

@Stable
internal class SignInFormState(
    email: String = "",
    otpCode: String = "",
    otpSent: Boolean = false,
    errorMsg: String? = null,
    sentAt: Long = 0L,
    lastTried: String = "",
    sentTo: String = "",
) {
    var email by mutableStateOf(email)
    var otpCode by mutableStateOf(otpCode)
    var otpSent by mutableStateOf(otpSent)
    var errorMsg by mutableStateOf(errorMsg)

    var sentAt by mutableLongStateOf(sentAt)

    var lastTried by mutableStateOf(lastTried)

    var sentTo by mutableStateOf(sentTo)

    var loading by mutableStateOf(false)
    var captchaOpen by mutableStateOf(false)
    var password by mutableStateOf("")

    fun backToEmail() {
        otpSent = false
        otpCode = ""
        lastTried = ""
        errorMsg = null
    }

    companion object {
        val Saver: Saver<SignInFormState, Any> =
            listSaver(
                save = {
                    listOf(it.email, it.otpCode, it.otpSent, it.errorMsg ?: "", it.sentAt, it.lastTried, it.sentTo)
                },
                restore = {
                    SignInFormState(
                        email = it[0] as String,
                        otpCode = it[1] as String,
                        otpSent = it[2] as Boolean,
                        errorMsg = (it[3] as String).ifEmpty { null },
                        sentAt = it[4] as Long,
                        lastTried = it[5] as String,
                        sentTo = (it.getOrNull(6) as? String).orEmpty(),
                    )
                },
            )
    }
}

@Composable
internal fun rememberSignInFormState(): SignInFormState =
    rememberSaveable(saver = SignInFormState.Saver) { SignInFormState() }

internal fun canReuseSentCode(
    email: String,
    sentTo: String,
    sentAtMs: Long,
    nowMs: Long,
): Boolean =
    sentAtMs != 0L && sentTo.isNotEmpty() && email.trim().equals(sentTo, ignoreCase = true) &&
        nowMs - sentAtMs in 0 until RESEND_WAIT_MS

internal fun resendWaitSeconds(
    sentAtMs: Long,
    nowMs: Long,
): Int {
    if (sentAtMs == 0L) return 0
    val left = (RESEND_WAIT_MS - (nowMs - sentAtMs)).coerceAtLeast(0)
    return ((left + 999) / 1000).toInt()
}

internal fun shouldAutoVerify(
    code: String,
    lastTried: String,
    busy: Boolean,
): Boolean = code.length == 6 && code != lastTried && !busy

internal fun isOperatorAccount(email: String): Boolean = email.trim().lowercase().endsWith("@anonen.net")

@Composable
internal fun CloudSignInForm(
    onSignedIn: (email: String) -> Unit,
    auth: AnonenCloudAuth,
    scope: CoroutineScope,
    state: SignInFormState = rememberSignInFormState(),
    asPage: Boolean = false,
) {
    Column(
        modifier = if (asPage) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = if (asPage) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(state.sentAt) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                if (resendWaitSeconds(state.sentAt, now) == 0) break
                delay(1_000)
            }
        }
        val resendWait = resendWaitSeconds(state.sentAt, now)
        val operatorLogin = isOperatorAccount(state.email)

        fun requestCaptcha() {
            if (!EmailFormat.isValid(state.email)) {
                state.errorMsg = "メールアドレスの書き方が違います"
                return
            }
            state.errorMsg = null

            val clock = SystemClock.elapsedRealtime()
            if (!state.otpSent && !operatorLogin && canReuseSentCode(state.email, state.sentTo, state.sentAt, clock)) {
                state.otpSent = true
                return
            }
            state.captchaOpen = true
        }

        fun sendCode(captchaToken: String) {
            state.captchaOpen = false
            state.loading = true
            state.errorMsg = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { auth.requestOtp(state.email.trim(), captchaToken) }
                    state.otpSent = true
                    state.otpCode = ""
                    state.lastTried = ""
                    state.sentAt = SystemClock.elapsedRealtime()
                    state.sentTo = state.email.trim()
                } catch (e: InterruptedIOException) {
                    state.errorMsg = "時間がかかりすぎました"
                } catch (e: Exception) {
                    state.errorMsg = e.message ?: "送れませんでした"
                } finally {
                    state.loading = false
                }
            }
        }

        fun loginWithPassword(captchaToken: String) {
            state.captchaOpen = false
            state.loading = true
            state.errorMsg = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        auth.signInWithPassword(state.email.trim(), state.password, captchaToken)
                    }
                    onSignedIn(state.email.trim())
                } catch (e: InterruptedIOException) {
                    state.errorMsg = "時間がかかりすぎました"
                } catch (e: Exception) {
                    state.errorMsg = e.message ?: AnonenCloudAuth.LOGIN_FAILED
                } finally {
                    state.loading = false
                }
            }
        }

        fun verify() {
            if (state.otpCode.length != 6 || state.loading) return
            state.lastTried = state.otpCode
            state.loading = true
            state.errorMsg = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { auth.verifyOtp(state.email.trim(), state.otpCode.trim()) }
                    onSignedIn(state.email.trim())
                } catch (e: InterruptedIOException) {
                    state.errorMsg = "時間がかかりすぎました"
                } catch (e: Exception) {
                    state.errorMsg = e.message ?: AnonenCloudAuth.LOGIN_FAILED
                } finally {
                    state.loading = false
                }
            }
        }

        if (state.captchaOpen) {
            CaptchaDialog(
                onToken = { token -> if (operatorLogin) loginWithPassword(token) else sendCode(token) },
                onDismiss = {
                    state.captchaOpen = false
                    state.errorMsg = "人か確かめるのを、やめました"
                },
                onFailure = { message ->
                    state.captchaOpen = false
                    state.errorMsg = message
                },
            )
        }

        if (!asPage) {
            Text(LOGIN_WITH_EMAIL, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = { state.email = it },
            label = { Text("メールアドレス") },
            enabled = !state.loading && !state.captchaOpen,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.otpSent) requestCaptcha() }),
            trailingIcon = {
                if (state.email.isNotEmpty() && !state.loading && !state.captchaOpen) {
                    IconButton(onClick = { state.email = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "入力を削除")
                    }
                }
            },
            colors = textFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (operatorLogin && !state.otpSent) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = { state.password = it },
                label = { Text("パスワード") },
                enabled = !state.loading && !state.captchaOpen,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { requestCaptcha() }),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.otpSent) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.otpCode,
                onValueChange = { v ->
                    state.otpCode = v.filter { it.isDigit() }.take(6)
                    if (shouldAutoVerify(state.otpCode, state.lastTried, state.loading)) verify()
                },
                label = { Text("確認コード（6 桁）") },
                enabled = !state.loading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { requestCaptcha() },
                enabled = resendWait == 0 && !state.loading && !state.captchaOpen,
            ) {
                Text(
                    if (resendWait > 0) "もう一度送る（${resendWait}秒）" else "もう一度送る",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.errorMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        if (!state.otpSent) {
            Button(
                onClick = { requestCaptcha() },
                enabled =
                    state.email.isNotBlank() && (!operatorLogin || state.password.isNotEmpty()) &&
                        !state.loading && !state.captchaOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(if (operatorLogin) "ログイン" else "メールで確認コードを受け取る")
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!asPage) {
                    OutlinedButton(
                        onClick = { state.backToEmail() },
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(BACK_LABEL)
                    }
                }
                Button(
                    onClick = { verify() },
                    enabled = state.otpCode.length == 6 && !state.loading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("ログイン")
                    }
                }
            }
        }
    }
}
