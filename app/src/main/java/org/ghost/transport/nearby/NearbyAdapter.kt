package org.ghost.transport.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.PacketValidator
import org.ghost.protocol.WireCodec
import org.ghost.transport.api.*

data class NearbyState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val message: String = "Connect to mesh to find nearby phones.",
)

/** Radio APIs only. The exchange layer verifies and persists received bytes. */
class NearbyAdapter(private val context: Context, private val scope: CoroutineScope,
    private val clientFactory: () -> ConnectionsClient = { Nearby.getConnectionsClient(context) },
    private val playServicesAvailable: () -> Boolean = {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    },
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : GhostTransport {
    override val id = "nearby"
    private var client: ConnectionsClient? = null
    private var generation = 0
    private var localName = "GHOST"
    private var meshScope: CoroutineScope? = null
    private val discovered = mutableMapOf<String, String>()
    private val pending = mutableMapOf<String, Long>()
    private val retryAt = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private val mutableState = MutableStateFlow(NearbyState())
    val state = mutableState.asStateFlow()
    private val mutablePeers = MutableStateFlow<List<PeerHandle>>(emptyList())
    override val peers = mutablePeers.asStateFlow()
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    override val events = mutableEvents.asSharedFlow()
    private val transfers = ConcurrentHashMap<Pair<String, Long>, CompletableDeferred<Unit>>()
    private val accepted = ConcurrentHashMap.newKeySet<String>()

    fun missingPermissions(): Array<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    private fun event(value: TransportEvent) {
        if (!mutableEvents.tryEmit(value)) value.peer?.let { peer ->
            accepted.remove(peer.endpointId); pending.remove(peer.endpointId)
            try { client?.disconnectFromEndpoint(peer.endpointId) } catch (_: Exception) { }
            mutablePeers.update { it.filterNot { p -> p.endpointId == peer.endpointId } }
            backoff(peer.endpointId)
            mutableState.update { it.copy(message = "Receive queue full. The mesh will reconnect automatically.") }
        }
    }
    private fun error(message: String) {
        mutableState.update { it.copy(message = message) }
        event(TransportEvent(TransportEventType.TRANSPORT_ERROR, message = message))
    }
    fun permissionDenied() { close(); error("Nearby permissions are missing. Allow them in app settings, then try again.") }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (peers.value.none { it.endpointId == endpointId }) return
            val bytes = if (payload.type == Payload.Type.BYTES) payload.asBytes() else null
            if (bytes == null || bytes.size > PacketValidator.MAX_PACKET) {
                runCatching { client?.cancelPayload(payload.id) }; disconnect(endpointId); error("Unsupported or oversized Nearby packet."); return
            }
            event(TransportEvent(TransportEventType.PACKET_RECEIVED, PeerHandle(id, endpointId), packetBytes = bytes))
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS) return
            val transfer = transfers.remove(endpointId to update.payloadId) ?: return
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) transfer.complete(Unit)
            else transfer.completeExceptionally(IOException("Nearby transfer did not complete"))
        }
    }

    suspend fun start(name: String): Result<Unit> {
        if (state.value.active || state.value.starting) return Result.success(Unit)
        if (missingPermissions().isNotEmpty()) { permissionDenied(); return Result.failure(SecurityException()) }
        if (!playServicesAvailable()) {
            error("Google Play services is unavailable. Nearby cannot start on this device.")
            return Result.failure(IOException("Play services unavailable"))
        }
        val token = ++generation
        // A per-run suffix breaks ties when both phones advertise and discover.
        localName = "${name.take(24)}-${UUID.randomUUID().toString().take(8)}"
        mutableState.value = NearbyState(starting = true, message = "Connecting to mesh…")
        val api = try { client ?: clientFactory().also { client = it } }
        catch (_: Exception) { close(); error("Mesh service unavailable. Check Google Play services and try again."); return Result.failure(IOException("Nearby unavailable")) }
        meshScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                if (token != generation || !(state.value.active || state.value.starting) ||
                    (endpointId !in pending && pending.size + peers.value.size >= 4)) {
                    runCatching { api.rejectConnection(endpointId) }; return
                }
                if (endpointId in accepted || peers.value.any { it.endpointId == endpointId }) return
                if (missingPermissions().isNotEmpty()) { permissionDenied(); return }
                pending[endpointId] = elapsed() + 15000
                accepted.add(endpointId)
                // Joining opts into open neighbor links. Key possession and original
                // report signatures are still verified by the exchange layer.
                runCatching {
                    api.acceptConnection(endpointId, payloads).addOnFailureListener {
                        if (token == generation) disconnect(endpointId)
                    }
                }.onFailure { if (token == generation) disconnect(endpointId) }
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (token != generation || !(state.value.active || state.value.starting)) { runCatching { api.disconnectFromEndpoint(endpointId) }; return }
                pending.remove(endpointId)
                if (result.status.isSuccess) {
                    if (endpointId !in accepted) { runCatching { api.disconnectFromEndpoint(endpointId) }; return }
                    val peer = PeerHandle(id, endpointId)
                    if (peer in peers.value) return
                    failures.remove(endpointId); retryAt.remove(endpointId)
                    mutablePeers.update { (it + peer).distinct() }
                    mutableState.update { it.copy(message = "Mesh is on. Nearby connections are automatic.") }
                    event(TransportEvent(TransportEventType.PEER_CONNECTED, peer))
                } else { accepted.remove(endpointId); backoff(endpointId) }
            }
            override fun onDisconnected(endpointId: String) {
                if (token != generation) return
                forgetConnection(endpointId)
            }
        }
        return try {
            withTimeout(15000) {
                api.startAdvertising(localName, SERVICE, callback, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).waitForTask()
                if (token != generation) return@withTimeout
                api.startDiscovery(SERVICE, object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                        if (token != generation || !(state.value.active || state.value.starting) || discovered.size >= 20) return
                        discovered[endpointId] = info.endpointName.take(80)
                    }
                    override fun onEndpointLost(endpointId: String) {
                        if (token == generation) {
                            discovered.remove(endpointId)
                            if (endpointId !in pending && peers.value.none { it.endpointId == endpointId }) {
                                failures.remove(endpointId); retryAt.remove(endpointId)
                            }
                        }
                    }
                }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).waitForTask()
            }
            if (token != generation) return Result.failure(IOException("Nearby stopped"))
            mutableState.update { it.copy(active = true, starting = false, message = "Searching for nearby phones. Connections are automatic.") }
            meshScope?.launch {
                while (isActive && token == generation) {
                    pending.filterValues { it <= elapsed() }.keys.toList().forEach(::disconnect)
                    // One side requests; both accept. Names select an initiator only,
                    // and are never treated as a trusted signing identity.
                    for ((endpoint, remoteName) in discovered.toMap()) {
                        if (pending.size + peers.value.size >= 4) break
                        if (localName >= remoteName || endpoint in pending || endpoint in accepted ||
                            peers.value.any { it.endpointId == endpoint } || elapsed() < (retryAt[endpoint] ?: 0)) continue
                        pending[endpoint] = elapsed() + 15000
                        launch {
                            try { withTimeout(15000) { api.requestConnection(localName, endpoint, callback).waitForTask() } }
                            catch (_: TimeoutCancellationException) { if (token == generation) disconnect(endpoint) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { if (token == generation) disconnect(endpoint) }
                        }
                    }
                    delay(1000)
                }
            }
            Result.success(Unit)
        } catch (_: TimeoutCancellationException) {
            if (token == generation) { close(); error("Nearby startup timed out. Check radios and retry.") }
            Result.failure(IOException("Nearby startup timed out"))
        } catch (cancelled: CancellationException) { if (token == generation) close(); throw cancelled }
          catch (_: Exception) {
            if (token == generation) { close(); error("Mesh could not start. Check permissions, Bluetooth, Wi-Fi and location services, then retry.") }
            Result.failure(IOException("Nearby startup failed"))
        }
    }
    override suspend fun start(): Result<Unit> = start(localName)

    private fun backoff(endpointId: String) {
        if (endpointId !in discovered) { failures.remove(endpointId); retryAt.remove(endpointId); return }
        val attempt = ((failures[endpointId] ?: 0) + 1).coerceAtMost(4)
        failures[endpointId] = attempt
        retryAt[endpointId] = elapsed() + (10000L * (1 shl (attempt - 1))).coerceAtMost(60000)
        mutableState.update { it.copy(message = "Mesh is on. Reconnecting automatically when phones are available.") }
    }
    private fun forgetConnection(endpointId: String) {
        val wasPending = pending.remove(endpointId) != null
        val wasAccepted = accepted.remove(endpointId)
        val wasConnected = peers.value.any { it.endpointId == endpointId }
        if (!wasPending && !wasAccepted && !wasConnected) return
        mutablePeers.update { it.filterNot { p -> p.endpointId == endpointId } }
        if (state.value.active || state.value.starting) backoff(endpointId)
        event(TransportEvent(TransportEventType.PEER_DISCONNECTED, PeerHandle(id, endpointId)))
    }
    override fun disconnect(endpointId: String) {
        transfers.keys.filter { it.first == endpointId }.forEach { key ->
            transfers.remove(key)?.completeExceptionally(IOException("Phone disconnected"))
        }
        try { client?.disconnectFromEndpoint(endpointId) } catch (_: Exception) { /* connection is already unusable */ }
        forgetConnection(endpointId)
    }
    override suspend fun stop() {
        close()
    }
    fun close() {
        generation++
        meshScope?.cancel(); meshScope = null
        runCatching { client?.stopAdvertising() }
        runCatching { client?.stopDiscovery() }
        runCatching { client?.stopAllEndpoints() }
        transfers.values.forEach { it.completeExceptionally(IOException("Nearby stopped")) }; transfers.clear()
        accepted.clear()
        discovered.clear(); pending.clear(); retryAt.clear(); failures.clear()
        mutablePeers.value = emptyList()
        mutableState.value = NearbyState()
    }
    override suspend fun send(peer: PeerHandle, packet: WirePacket): SendResult {
        if (missingPermissions().isNotEmpty()) { permissionDenied(); return SendResult(SendResultCode.REJECTED_NO_PERMISSION) }
        if (peer !in peers.value || peer.transportId != id) return SendResult(SendResultCode.REJECTED_NO_PEER)
        val bytes = try { WireCodec.encode(packet) } catch (_: Exception) { return SendResult(SendResultCode.REJECTED_TOO_LARGE) }
        if (bytes.size > Connections.MAX_BYTES_DATA_SIZE || transfers.size >= 32) return SendResult(SendResultCode.REJECTED_TOO_LARGE)
        val payload = Payload.fromBytes(bytes)
        val key = peer.endpointId to payload.id
        val completion = CompletableDeferred<Unit>()
        transfers[key] = completion
        return try {
            withTimeout(10000) { client!!.sendPayload(peer.endpointId, payload).waitForTask(); completion.await() }
            SendResult(SendResultCode.ACCEPTED_BY_TRANSPORT)
        } catch (cancelled: TimeoutCancellationException) { SendResult(SendResultCode.FAILED_RETRYABLE) }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { SendResult(SendResultCode.FAILED_RETRYABLE) }
          finally { transfers.remove(key); if (!completion.isCompleted) runCatching { client?.cancelPayload(payload.id) } }
    }

    companion object {
        private const val SERVICE = "org.ghost.sos.v1"
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT in 29..32) { add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray()
    }
}

private suspend fun Task<Void>.waitForTask(): Unit = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener({ action -> action.run() }) { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(Unit)
            else continuation.resumeWithException(task.exception ?: IOException("Nearby operation failed"))
        }
    }
}
