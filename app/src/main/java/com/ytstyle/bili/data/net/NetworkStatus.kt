package com.ytstyle.bili.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the default network looks like, at the granularity the autoplay rule
 * needs.
 *
 * Only one distinction actually matters for playback: is this link metered?
 * Wi-Fi and Ethernet are not, cellular is, and "no link" is its own answer so
 * the settings page can say so instead of guessing.
 */
enum class NetworkKind(val label: String) {
    Wifi("Wi-Fi"),
    Cellular("移动网络"),
    /** Ethernet, VPN-over-Wi-Fi and anything else unmetered. */
    Other("其他网络"),
    None("未连接");

    /** Wi-Fi and wired links do not cost the user money. */
    val isUnmetered: Boolean get() = this == Wifi || this == Other
}

/**
 * Reads the active network's transport.
 *
 * Requires `ACCESS_NETWORK_STATE`, which the manifest already declares. Every
 * failure path returns [NetworkKind.Other] rather than throwing: a playback
 * preference is not worth crashing over, and "unmetered" is the permissive
 * default that keeps autoplay working.
 */
object NetworkStatus {

    fun current(context: Context): NetworkKind {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return NetworkKind.Other
        val network = manager.activeNetwork ?: return NetworkKind.None
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkKind.None
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Other
            else -> NetworkKind.Other
        }
    }
}
