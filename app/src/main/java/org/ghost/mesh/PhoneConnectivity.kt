package org.ghost.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

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
