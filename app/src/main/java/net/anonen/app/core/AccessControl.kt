package net.anonen.app.core

object AccessControl {
    val ACTIVE_STATUSES = setOf("trialing", "active", "past_due")

    fun isActiveSubscription(status: String?): Boolean = status in ACTIVE_STATUSES

    enum class AccessGate { NEEDS_LOGIN, CHECKING, CHECK_FAILED, NEEDS_SUBSCRIPTION, ALLOWED }

    fun gate(
        signedIn: Boolean,
        lastKnownStatus: String?,
        checking: Boolean = false,
        checkFailed: Boolean = false,
    ): AccessGate =
        when {
            !signedIn -> AccessGate.NEEDS_LOGIN
            isActiveSubscription(lastKnownStatus) -> AccessGate.ALLOWED
            checking -> AccessGate.CHECKING
            checkFailed -> AccessGate.CHECK_FAILED
            else -> AccessGate.NEEDS_SUBSCRIPTION
        }
}
