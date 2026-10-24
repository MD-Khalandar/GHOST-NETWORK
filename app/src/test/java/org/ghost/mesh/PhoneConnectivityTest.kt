package org.ghost.mesh

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.net.URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneConnectivityTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun capabilities(transport: Int, internet: Boolean = true) = ShadowNetworkCapabilities.newInstance().apply {
        shadowOf(this).addTransportType(transport)
        if (internet) shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Test fun cellularEnablesServerConnectionsAndHostedServersKeepTheDefaultRoute() = runBlocking {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager); shadow.clearAllNetworks()
        val cell = ShadowNetwork.newInstance(0)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, true)
        shadow.addNetwork(cell, info); shadow.setActiveNetworkInfo(info)
        shadow.setNetworkCapabilities(cell, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertTrue(observeServerNetwork(context).first())
        assertNull(serverWifiNetwork(context, URL("https://cloud.example.org")))
        assertNull(serverWifiNetwork(context, URL("http://192.168.1.10:3000")))
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun localWifiWorksWithoutInternetAndDoesNotHijackHostedOrVpnServers() = runBlocking {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager); shadow.clearAllNetworks()
        val wifi = ShadowNetwork.newInstance(1)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true)
        shadow.addNetwork(wifi, info)
        shadow.setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, internet = false))
        assertTrue(observeServerNetwork(context).first())
        for (url in listOf("http://192.168.1.10:3000", "http://10.1.1.2:3000", "http://ghost.local", "http://[fd00::1]"))
            assertEquals(wifi, serverWifiNetwork(context, URL(url)))
        assertNull(serverWifiNetwork(context, URL("https://cloud.example.org")))
        assertNull(serverWifiNetwork(context, URL("http://127.0.0.1:3000")))
        val vpn = ShadowNetwork.newInstance(17)
        val vpnInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, true)
        shadow.addNetwork(vpn, vpnInfo); shadow.setActiveNetworkInfo(vpnInfo)
        shadow.setNetworkCapabilities(manager.activeNetwork, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        assertNull(serverWifiNetwork(context, URL("https://10.20.0.2")))
    }

    @Test fun noNetworkPausesOnlineWork() = runBlocking {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).clearAllNetworks()
        assertFalse(observeServerNetwork(context).first())
    }
}
