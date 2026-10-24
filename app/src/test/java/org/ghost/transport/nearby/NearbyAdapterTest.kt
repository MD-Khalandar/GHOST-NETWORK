package org.ghost.transport.nearby

import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the real adapter's discovery/lifecycle callbacks; no radio hardware. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyAdapterTest {
    private class Sdk {
        lateinit var lifecycle: ConnectionLifecycleCallback
        lateinit var discovery: EndpointDiscoveryCallback
        val requested = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var stopped = 0
        val api = Proxy.newProxyInstance(ConnectionsClient::class.java.classLoader, arrayOf(ConnectionsClient::class.java)) { _, method, args ->
            when (method.name) {
                "startAdvertising" -> { lifecycle = args!![2] as ConnectionLifecycleCallback; Tasks.forResult<Void>(null) }
                "startDiscovery" -> { discovery = args!![1] as EndpointDiscoveryCallback; Tasks.forResult<Void>(null) }
                "requestConnection" -> { requested.add(args!![1] as String); Tasks.forResult<Void>(null) }
                "acceptConnection" -> { accepted.add(args!![0] as String); Tasks.forResult<Void>(null) }
                "rejectConnection" -> { rejected.add(args!![0] as String); Tasks.forResult<Void>(null) }
                "stopAllEndpoints" -> { stopped++; null }
                "stopAdvertising", "stopDiscovery", "disconnectFromEndpoint" -> null
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as ConnectionsClient
        fun found(endpoint: String, name: String = "GHOST Z") = discovery.onEndpointFound(endpoint, DiscoveredEndpointInfo("org.ghost.sos.v1", name))
        fun initiated(endpoint: String) = lifecycle.onConnectionInitiated(endpoint, ConnectionInfo("GHOST Z", "1234", true))
        fun connected(endpoint: String) = lifecycle.onConnectionResult(endpoint, ConnectionResolution(Status.RESULT_SUCCESS))
    }
    private fun TestScope.adapter(sdk: Sdk): NearbyAdapter {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(*NearbyAdapter.requiredPermissions())
        return NearbyAdapter(context, backgroundScope, { sdk.api }, { true }, { testScheduler.currentTime })
    }

    @Test fun joinAutomaticallyRequestsAndAcceptsWithoutPhoneSelectionThenStopRejectsLateCallbacks() = runTest {
        val sdk = Sdk(); val adapter = adapter(sdk)
        assertTrue(adapter.start("GHOST A").isSuccess)
        sdk.found("outgoing"); sdk.found("incoming", "GHOST 0")
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("outgoing"), sdk.requested) // Only the elected side initiates.
        sdk.initiated("outgoing"); sdk.initiated("incoming")
        assertEquals(listOf("outgoing", "incoming"), sdk.accepted)
        sdk.connected("outgoing"); sdk.connected("incoming")
        assertEquals(2, adapter.peers.value.size)
        adapter.close()
        sdk.initiated("late"); sdk.connected("late")
        advanceTimeBy(30000); runCurrent()
        assertFalse(adapter.state.value.active)
        assertTrue(adapter.peers.value.isEmpty())
        assertEquals(listOf("late"), sdk.rejected)
        assertEquals(1, sdk.requested.size)
        assertEquals(1, sdk.stopped)
    }

    @Test fun lostLinksRetryWithBackoffAndPendingInvitationsAreBounded() = runTest {
        val sdk = Sdk(); val adapter = adapter(sdk)
        adapter.start("GHOST A")
        sdk.found("peer")
        advanceTimeBy(1000); runCurrent()
        sdk.initiated("peer"); sdk.connected("peer")
        sdk.lifecycle.onDisconnected("peer")
        advanceTimeBy(9000); runCurrent()
        assertEquals(1, sdk.requested.size)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, sdk.requested.size)
        // Requests that never complete cannot consume slots indefinitely.
        advanceTimeBy(15000); runCurrent()
        assertTrue(adapter.peers.value.isEmpty())
        repeat(8) { sdk.found("other-$it") }
        advanceTimeBy(1000); runCurrent()
        assertTrue(sdk.requested.count { it.startsWith("other-") } <= 4)
        sdk.initiated("extra")
        assertTrue("extra" in sdk.rejected)
        adapter.close()
    }
}
