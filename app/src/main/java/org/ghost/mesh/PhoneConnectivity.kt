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

/** Wi-Fi permits a connection attempt; the server request establishes reachability. */
fun observeWifi(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networks = manager.allNetworks.filterTo(mutableSetOf()) { network ->
        manager.getNetworkCapabilities(network)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
    }
    trySend(networks.isNotEmpty())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) networks.add(network)
            else networks.remove(network)
            trySend(networks.isNotEmpty())
        }
        override fun onLost(network: Network) { networks.remove(network); trySend(networks.isNotEmpty()) }
    }
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
    if (!registered) trySend(false)
    awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
}

/** Bind online requests to Wi-Fi even when Android prefers mobile data for public internet. */
fun openWifiConnection(context: Context, url: URL): HttpURLConnection {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.allNetworks.firstOrNull { candidate ->
        manager.getNetworkCapabilities(candidate)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
    } ?: throw java.io.IOException("Wi-Fi unavailable")
    return network.openConnection(url) as HttpURLConnection
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
