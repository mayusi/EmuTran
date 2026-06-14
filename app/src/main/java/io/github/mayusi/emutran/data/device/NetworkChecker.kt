package io.github.mayusi.emutran.data.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [ConnectivityManager] that answers a single question:
 * does the device currently have a working internet connection?
 *
 * Uses the modern [ConnectivityManager.getActiveNetwork] +
 * [ConnectivityManager.getNetworkCapabilities] API (available on API 23+;
 * minSdk is 29, so no compat shim needed).
 *
 * Capability strategy:
 *   1. [NetworkCapabilities.NET_CAPABILITY_VALIDATED] — indicates Android has
 *      probed the captive portal and the connection is genuinely reaching the
 *      internet. Best signal to use when present.
 *   2. Fall back to [NetworkCapabilities.NET_CAPABILITY_INTERNET] only if
 *      VALIDATED is absent — rare on modern Android but handles edge cases
 *      where the validation probe failed/was disabled while the link is fine.
 */
@Singleton
class NetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // Prefer VALIDATED — it confirms the connection reaches the internet
        // and is not stuck behind an unacknowledged captive portal.
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
        // Fallback: at minimum check that the network claims internet access.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
