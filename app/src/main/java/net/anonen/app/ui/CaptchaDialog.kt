package net.anonen.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import net.anonen.app.core.DiagnosticsLog
import java.util.concurrent.atomic.AtomicBoolean

private const val CAPTCHA_URL = "https://anonen.net/captcha"

private fun captchaUrl(dark: Boolean): String = "$CAPTCHA_URL?theme=" + if (dark) "dark" else "light"

private const val CAPTCHA_TIMEOUT_MS = 60_000L

private const val CAPTCHA_HEIGHT_FALLBACK_DP = 280
private const val CAPTCHA_HEIGHT_MIN_DP = 72
private const val CAPTCHA_HEIGHT_MAX_DP = 320

private const val CAPTCHA_HEIGHT_LOADING_DP = 76
private const val CAPTCHA_HEIGHT_GRACE_MS = 1_500L

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CaptchaDialog(
    onToken: (String) -> Unit,
    onDismiss: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val latestOnToken by rememberUpdatedState(onToken)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestOnFailure by rememberUpdatedState(onFailure)
    var finished by remember { mutableStateOf(false) }

    var contentHeightDp by remember { mutableStateOf<Int?>(null) }

    var graceExpired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(CAPTCHA_HEIGHT_GRACE_MS)
        graceExpired = true
    }

    val webViewHeightDp =
        (
            contentHeightDp
                ?: if (graceExpired) CAPTCHA_HEIGHT_FALLBACK_DP else CAPTCHA_HEIGHT_LOADING_DP
        ).coerceIn(CAPTCHA_HEIGHT_MIN_DP, CAPTCHA_HEIGHT_MAX_DP)

    fun fail(message: String) {
        if (finished) return
        finished = true
        latestOnFailure(message)
    }

    LaunchedEffect(contentHeightDp == null) {
        if (contentHeightDp != null) return@LaunchedEffect
        delay(CAPTCHA_TIMEOUT_MS)
        DiagnosticsLog.log("人の確認ができない: 時間切れ（読み込みが終わらない）")
        fail("時間がかかりすぎました")
    }

    Dialog(
        onDismissRequest = {
            if (!finished) {
                finished = true
                latestOnDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("人か確かめています", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "閉じずにお待ちください",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(webViewHeightDp.dp)) {
                    if (contentHeightDp == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply webView@{
                                settings.javaScriptEnabled = true

                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                                CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(this@webView, true)
                                }

                                val bridge =
                                    AnonenCaptchaBridge(
                                        onToken = { token ->
                                            if (!finished) {
                                                finished = true
                                                latestOnToken(token)
                                            }
                                        },
                                        onHeight = { px -> contentHeightDp = px },
                                    )
                                addJavascriptInterface(bridge, "AnonenCaptcha")
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean {
                                            if (!request.isForMainFrame) return false
                                            if (isAllowedCaptchaUrl(request.url)) return false
                                            DiagnosticsLog.log("人の確認ができない: 許可していないページへ移ろうとした ${request.url.host}")
                                            fail("人か確かめるページを開けませんでした")
                                            return true
                                        }

                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String?,
                                            favicon: Bitmap?,
                                        ) {
                                            if (url == null || !isAllowedCaptchaUrl(Uri.parse(url))) {
                                                view.stopLoading()
                                                DiagnosticsLog.log(
                                                    "人の確認ができない: 許可していないページを開こうとした " +
                                                        url?.let { Uri.parse(it).host },
                                                )
                                                fail("人か確かめるページを開けませんでした")
                                            }
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            error: WebResourceError,
                                        ) {
                                            if (request.isForMainFrame) {
                                                DiagnosticsLog.log(
                                                    "人の確認ができない: 通信 ${error.errorCode} ${error.description}",
                                                )
                                                fail("人か確かめるページを開けませんでした")
                                            }
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            errorResponse: WebResourceResponse,
                                        ) {
                                            if (request.isForMainFrame) {
                                                DiagnosticsLog.log("人の確認ができない: HTTP ${errorResponse.statusCode}")
                                                fail("人か確かめるページを開けませんでした")
                                            }
                                        }

                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: SslErrorHandler,
                                            error: SslError,
                                        ) {
                                            handler.cancel()
                                            DiagnosticsLog.log("人の確認ができない: SSL ${error.primaryError}")
                                            fail("人か確かめるページを開けませんでした")
                                        }
                                    }

                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                loadUrl(captchaUrl(dark), mapOf("Cache-Control" to "no-store"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(webViewHeightDp.dp),
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.removeJavascriptInterface("AnonenCaptcha")
                            webView.loadUrl("about:blank")
                            webView.clearHistory()
                            webView.clearFormData()
                            webView.clearCache(true)
                            webView.destroy()
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                            }
                            WebStorage.getInstance().deleteAllData()
                        },
                    )
                }
                TextButton(
                    onClick = {
                        if (!finished) {
                            finished = true
                            latestOnDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("閉じる")
                }
            }
        }
    }
}

private fun isAllowedCaptchaUrl(uri: Uri): Boolean =
    uri.scheme == "https" &&
        uri.host == "anonen.net" &&
        (uri.path == "/captcha" || uri.path == "/captcha/")

private class AnonenCaptchaBridge(
    private val onToken: (String) -> Unit,
    private val onHeight: (Int) -> Unit,
) {
    private val delivered = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onToken(token: String) {
        if (token.isBlank() || !delivered.compareAndSet(false, true)) return
        mainHandler.post { onToken.invoke(token) }
    }

    @JavascriptInterface
    fun onHeight(px: Int) {
        if (px !in 1..2000) return
        mainHandler.post { onHeight.invoke(px) }
    }
}
