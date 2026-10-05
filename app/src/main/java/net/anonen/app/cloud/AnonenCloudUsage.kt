package net.anonen.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.anonen.app.core.DiagnosticsLog

class AnonenCloudUsage(
    private val client: AnonenCloudClient,
    private val auth: AnonenCloudAuth,
    private val onAccount: (CloudAccountStatus, String?) -> Unit = { _, _ -> },
    private val onNoSubscription: () -> Unit = {},
    private val onCheckFailed: () -> Unit = {},
    private val recordingLimits: RecordingLimits? = null,
) {
    private val _usage = MutableStateFlow<UsageSnapshot?>(null)
    val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    private val _account = MutableStateFlow<CloudAccountStatus?>(null)
    val account: StateFlow<CloudAccountStatus?> = _account.asStateFlow()

    fun updateFromTranscribe(snapshot: UsageSnapshot): Boolean {
        val wasLow = isLowRemaining
        _usage.value = snapshot
        recordingLimits?.update(snapshot.maxRequestS)
        return !wasLow && isLowRemaining
    }

    suspend fun fetch(): UsageSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val user = auth.status().email
                val token = auth.getAccessToken()
                val account = client.fetchUsage(token)
                if (auth.status().email != user) {
                    DiagnosticsLog.log("Usage 破棄: fetch 中にアカウントが変わった")
                    return@withContext null
                }
                _account.value = account
                _usage.value = account.usage
                recordingLimits?.update(account.usage.maxRequestS)
                onAccount(account, user)
                account.usage
            } catch (e: Exception) {
                if (e is GatewayError && e.isNoSubscription) {
                    DiagnosticsLog.log("Usage 取得: 402 no_active_subscription → 契約キャッシュ無効化")
                    onNoSubscription()
                } else {
                    DiagnosticsLog.log("Usage 取得失敗: ${e.message}")
                    onCheckFailed()
                }
                null
            }
        }

    fun clear() {
        _usage.value = null
        _account.value = null

        recordingLimits?.update(null)
    }

    val isLowRemaining: Boolean
        get() {
            val u = _usage.value ?: return false
            if (u.weekCapS == 0 && u.monthCapS == 0) return false
            val weekRatio = if (u.weekCapS > 0) u.weekRemainingS.toFloat() / u.weekCapS else 1f
            val monthRatio = if (u.monthCapS > 0) u.monthRemainingS.toFloat() / u.monthCapS else 1f
            return minOf(weekRatio, monthRatio) < LOW_REMAINING_THRESHOLD
        }

    companion object {
        private const val LOW_REMAINING_THRESHOLD = 0.1f
    }
}
