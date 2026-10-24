package org.ghost.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** A usable network permits an attempt; only a server response proves reachability. */
fun observeServerNetwork(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun usable(caps: NetworkCapabilities?) = caps?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } == true
    val networks = manager.allNetworks.filterTo(mutableSetOf()) { network ->
        usable(manager.getNetworkCapabilities(network))
    }
    trySend(networks.isNotEmpty())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (usable(networkCapabilities)) networks.add(network)
            else networks.remove(network)
            trySend(networks.isNotEmpty())
        }
        override fun onLost(network: Network) { networks.remove(network); trySend(networks.isNotEmpty()) }
    }
    val request = NetworkRequest.Builder().clearCapabilities()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).build()
    val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
    if (!registered) trySend(false)
    awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
}

/** Hosted servers use Android's default route, including cellular and VPNs. */
fun openServerConnection(context: Context, url: URL): HttpURLConnection =
    (serverWifiNetwork(context, url)?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

internal fun serverWifiNetwork(context: Context, url: URL): Network? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val active = manager.activeNetwork
    val activeCaps = manager.getNetworkCapabilities(active)
    if (!isLanServer(url.host) || activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return null
    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
    // A LAN server may be reachable over Wi-Fi while Android prefers cellular.
    val wifi = manager.allNetworks.filter {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    return wifi.firstOrNull { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
        ?: wifi.firstOrNull()
}

internal fun isLanServer(rawHost: String): Boolean {
    val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
    if (host == "localhost" || host == "::1" || host == "127.0.0.1") return false // Loopback belongs to this phone/emulator.
    if (host.endsWith(".local") || host.endsWith(".lan") || (!host.contains('.') && !host.contains(':'))) return true
    if (host.contains(':')) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
        host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 || octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 172 && octets[1] in 16..31 || octets[0] == 169 && octets[1] == 254
}

/** System validation is distinct from the configured GHOST server's reachability. */
fun observeInternet(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    fun status(caps: NetworkCapabilities?) = when {
        caps == null -> Connectivity.UNAVAILABLE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Connectivity.AVAILABLE
        else -> Connectivity.UNAVAILABLE
    }
    var current = manager.activeNetwork
    trySend(status(manager.getNetworkCapabilities(current)))
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { current = network; trySend(Connectivity.UNKNOWN) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (network == current) trySend(status(networkCapabilities))
        }
        override fun onLost(network: Network) {
            if (network == current) { current = null; trySend(Connectivity.UNAVAILABLE) }
        }
    }
    val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
    if (!registered) trySend(Connectivity.UNKNOWN)
    awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
}
