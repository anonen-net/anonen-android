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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

@Composable
internal fun CloudSignInForm(
    onSignedIn: (email: String) -> Unit,
    auth: AnonenCloudAuth,
    scope: CoroutineScope,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        var email by rememberSaveable { mutableStateOf("") }
        var otpCode by rememberSaveable { mutableStateOf("") }
        var otpSent by rememberSaveable { mutableStateOf(false) }
        var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }

        var sentAt by rememberSaveable { mutableLongStateOf(0L) }

        var lastTried by rememberSaveable { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var captchaOpen by remember { mutableStateOf(false) }
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(sentAt) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                if (resendWaitSeconds(sentAt, now) == 0) break
                delay(1_000)
            }
        }
        val resendWait = resendWaitSeconds(sentAt, now)

        fun requestCaptcha() {
            if (!EmailFormat.isValid(email)) {
                errorMsg = "メールアドレスの書き方が違います"
                return
            }
            errorMsg = null
            captchaOpen = true
        }

        fun sendCode(captchaToken: String) {
            captchaOpen = false
            loading = true
            errorMsg = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { auth.requestOtp(email.trim(), captchaToken) }
                    otpSent = true
                    otpCode = ""
                    lastTried = ""
                    sentAt = SystemClock.elapsedRealtime()
                } catch (e: InterruptedIOException) {
                    errorMsg = "時間がかかりすぎました"
                } catch (e: Exception) {
                    errorMsg = e.message ?: "送れませんでした"
                } finally {
                    loading = false
                }
            }
        }

        fun verify() {
            if (otpCode.length != 6 || loading) return
            lastTried = otpCode
            loading = true
            errorMsg = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { auth.verifyOtp(email.trim(), otpCode.trim()) }
                    onSignedIn(email.trim())
                } catch (e: InterruptedIOException) {
                    errorMsg = "時間がかかりすぎました"
                } catch (e: Exception) {
                    errorMsg = e.message ?: "ログインできませんでした"
                } finally {
                    loading = false
                }
            }
        }

        if (captchaOpen) {
            CaptchaDialog(
                onToken = { token -> sendCode(token) },
                onDismiss = {
                    captchaOpen = false
                    errorMsg = "人か確かめるのを、やめました"
                },
                onFailure = { message ->
                    captchaOpen = false
                    errorMsg = message
                },
            )
        }

        Text("メールアドレスでログイン", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("メールアドレス") },
            enabled = !loading && !captchaOpen,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!otpSent) requestCaptcha() }),
            trailingIcon = {
                if (email.isNotEmpty() && !loading && !captchaOpen) {
                    IconButton(onClick = { email = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "入力を削除")
                    }
                }
            },
            colors = textFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (otpSent) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = otpCode,
                onValueChange = { v ->
                    otpCode = v.filter { it.isDigit() }.take(6)
                    if (shouldAutoVerify(otpCode, lastTried, loading)) verify()
                },
                label = { Text("メールの 6 桁の数字") },
                enabled = !loading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { requestCaptcha() },
                enabled = resendWait == 0 && !loading && !captchaOpen,
            ) {
                Text(
                    if (resendWait > 0) "もう一度送る（${resendWait}秒）" else "もう一度送る",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        errorMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        if (!otpSent) {
            Button(
                onClick = { requestCaptcha() },
                enabled = email.isNotBlank() && !loading && !captchaOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("数字をメールで受け取る")
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        otpSent = false
                        otpCode = ""
                        lastTried = ""
                        errorMsg = null
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("戻る")
                }
                Button(
                    onClick = { verify() },
                    enabled = otpCode.length == 6 && !loading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (loading) {
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
