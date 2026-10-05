package net.anonen.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkStatus {
    fun isOnline(context: Context): Boolean =
        runCatching {
            val cm =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            decide(
                hasActiveNetwork = caps != null,
                hasInternetCapability =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isValidated =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        }.getOrDefault(true)

    internal fun decide(
        hasActiveNetwork: Boolean,
        hasInternetCapability: Boolean,
        @Suppress("UNUSED_PARAMETER") isValidated: Boolean,
    ): Boolean = hasActiveNetwork && hasInternetCapability
}
