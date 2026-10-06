package net.anonen.app.cloud

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.anonen.app.core.AccessControl
import net.anonen.app.core.AccessControl.AccessGate
import java.util.concurrent.atomic.AtomicBoolean

class EntitlementManager(
    private val auth: AnonenCloudAuth,
    private val prefs: SharedPreferences,
) {
    private val _gate = MutableStateFlow(compute())
    val gate: StateFlow<AccessGate> = _gate.asStateFlow()

    private val quietLogout = AtomicBoolean(false)

    @Volatile
    private var checking: Boolean = false

    @Volatile
    private var checkFailed: Boolean = false

    fun lastKnownStatus(): String? {
        val status = prefs.getString(KEY_STATUS, null) ?: return null
        val cachedUser = prefs.getString(KEY_STATUS_USER, null) ?: return null
        val currentUser = auth.status().email ?: return null
        return status.takeIf { cachedUser == currentUser }
    }

    fun recordSubscription(
        status: String?,
        user: String?,
    ) {
        val sameSession = auth.isSignedIn() && user != null && user == auth.status().email
        if (sameSession) {
            checking = false
            checkFailed = false
            if (status != null) {
                prefs.edit()
                    .putString(KEY_STATUS, status)
                    .putString(KEY_STATUS_USER, user)
                    .apply()
            } else {
                clearCache()
            }
        }
        refresh()
    }

    fun onUserLogout() {
        quietLogout.set(true)
        onLogout()
    }

    fun consumeQuietLogout(): Boolean = quietLogout.getAndSet(false)

    fun onLogout() {
        checking = false
        checkFailed = false
        clearCache()
        refresh()
    }

    fun onNewSession() {
        checking = true
        checkFailed = false
        clearCache()
        refresh()
    }

    fun onNoSubscription() {
        checking = false
        checkFailed = false
        clearCache()
        refresh()
    }

    fun onCheckFinished() {
        checking = false
        refresh()
    }

    fun onCheckFailed() {
        checking = false
        checkFailed = true
        refresh()
    }

    fun refresh() {
        _gate.value = compute()
    }

    private fun clearCache() {
        prefs.edit().remove(KEY_STATUS).remove(KEY_STATUS_USER).apply()
    }

    private fun compute(): AccessGate = AccessControl.gate(auth.isSignedIn(), lastKnownStatus(), checking, checkFailed)

    companion object {
        private const val KEY_STATUS = "handy_cloud_subscription_status"

        private const val KEY_STATUS_USER = "handy_cloud_subscription_user"
    }
}
