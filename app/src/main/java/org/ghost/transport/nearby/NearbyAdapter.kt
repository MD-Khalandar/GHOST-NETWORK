package org.ghost.transport.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.PacketValidator
import org.ghost.protocol.WireCodec
import org.ghost.transport.api.*

data class DiscoveredPhone(val endpointId: String, val name: String)
data class PairingRequest(val endpointId: String, val name: String, val code: String)
data class NearbyState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val message: String = "Nearby is stopped",
    val discovered: List<DiscoveredPhone> = emptyList(),
    val pairing: List<PairingRequest> = emptyList(),
)

/** Radio APIs only. The exchange layer verifies and persists received bytes. */
class NearbyAdapter(private val context: Context) : GhostTransport {
    override val id = "nearby"
    private var client: ConnectionsClient? = null
    private var generation = 0
    private var lifecycle: ConnectionLifecycleCallback? = null
    private var localName = "GHOST"
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
            try { client?.disconnectFromEndpoint(peer.endpointId) } catch (_: Exception) { }
            mutablePeers.update { it.filterNot { p -> p.endpointId == peer.endpointId } }
            mutableState.update { it.copy(message = "Receive queue full. Reconnect to retry.") }
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
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            error("Google Play services is unavailable. Nearby cannot start on this device.")
            return Result.failure(IOException("Play services unavailable"))
        }
        val token = ++generation
        localName = name.take(40)
        mutableState.value = NearbyState(starting = true, message = "Starting Nearby…")
        val api = client ?: Nearby.getConnectionsClient(context).also { client = it }
        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                if (token != generation || !(state.value.active || state.value.starting) || state.value.pairing.size + peers.value.size >= 4 || info.authenticationDigits.isBlank()) {
                    runCatching { api.rejectConnection(endpointId) }; return
                }
                mutableState.update { it.copy(pairing = it.pairing.filterNot { p -> p.endpointId == endpointId } +
                    PairingRequest(endpointId, info.endpointName.take(60), info.authenticationDigits)) }
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (token != generation || !(state.value.active || state.value.starting)) { runCatching { api.disconnectFromEndpoint(endpointId) }; return }
                mutableState.update { it.copy(pairing = it.pairing.filterNot { p -> p.endpointId == endpointId }) }
                if (result.status.isSuccess) {
                    if (endpointId !in accepted) { runCatching { api.disconnectFromEndpoint(endpointId) }; return }
                    val peer = PeerHandle(id, endpointId)
                    mutablePeers.update { (it + peer).distinct() }
                    event(TransportEvent(TransportEventType.PEER_CONNECTED, peer))
                } else { accepted.remove(endpointId); error("Connection was declined or failed. Try connecting again.") }
            }
            override fun onDisconnected(endpointId: String) {
                if (token != generation) return
                accepted.remove(endpointId)
                mutablePeers.update { it.filterNot { p -> p.endpointId == endpointId } }
                mutableState.update { it.copy(message = "A phone disconnected. Check radios and reconnect to retry saved sends.") }
                event(TransportEvent(TransportEventType.PEER_DISCONNECTED, PeerHandle(id, endpointId)))
            }
        }
        lifecycle = callback
        return try {
            withTimeout(15000) {
                api.startAdvertising(localName, SERVICE, callback, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).waitForTask()
                if (token != generation) return@withTimeout
                api.startDiscovery(SERVICE, object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                        if (token != generation || !(state.value.active || state.value.starting) || state.value.discovered.size >= 20) return
                        mutableState.update { it.copy(discovered = it.discovered.filterNot { p -> p.endpointId == endpointId } + DiscoveredPhone(endpointId, info.endpointName.take(60))) }
                    }
                    override fun onEndpointLost(endpointId: String) {
                        if (token == generation) mutableState.update { it.copy(discovered = it.discovered.filterNot { p -> p.endpointId == endpointId }) }
                    }
                }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()).waitForTask()
            }
            if (token != generation) return Result.failure(IOException("Nearby stopped"))
            mutableState.update { it.copy(active = true, starting = false, message = "Nearby is on. Choose a phone to connect.") }
            Result.success(Unit)
        } catch (_: TimeoutCancellationException) {
            if (token == generation) { close(); error("Nearby startup timed out. Check radios and retry.") }
            Result.failure(IOException("Nearby startup timed out"))
        } catch (cancelled: CancellationException) { if (token == generation) close(); throw cancelled }
          catch (_: Exception) {
            stop(); error("Nearby could not start. Check permissions, Bluetooth, Wi-Fi and location services, then retry.")
            Result.failure(IOException("Nearby startup failed"))
        }
    }
    override suspend fun start(): Result<Unit> = start(localName)

    suspend fun connect(endpointId: String) {
        if (!state.value.active) { error("Start Nearby before connecting."); return }
        if (missingPermissions().isNotEmpty()) { permissionDenied(); return }
        val callback = lifecycle ?: return
        try { withTimeout(15000) { client?.requestConnection(localName, endpointId, callback)?.waitForTask() } }
        catch (_: TimeoutCancellationException) { disconnect(endpointId); error("Connection timed out. Try again.") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error("Could not connect. Keep both phones open and try again.") }
    }
    suspend fun confirm(endpointId: String, accept: Boolean) {
        if (state.value.pairing.none { it.endpointId == endpointId }) return
        mutableState.update { it.copy(pairing = it.pairing.filterNot { p -> p.endpointId == endpointId }) }
        try {
            withTimeout(10000) {
                if (accept && missingPermissions().isEmpty()) {
                    accepted.add(endpointId)
                    client?.acceptConnection(endpointId, payloads)?.waitForTask()
                } else { accepted.remove(endpointId); client?.rejectConnection(endpointId)?.waitForTask() }
            }
        } catch (_: TimeoutCancellationException) { disconnect(endpointId); error("Pairing timed out. Reconnect to try again.") }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { disconnect(endpointId); error("Pairing failed. Check permissions and reconnect.") }
    }
    fun disconnect(endpointId: String) {
        accepted.remove(endpointId)
        try { client?.disconnectFromEndpoint(endpointId) } catch (_: Exception) { /* connection is already unusable */ }
        mutablePeers.update { it.filterNot { p -> p.endpointId == endpointId } }
        event(TransportEvent(TransportEventType.PEER_DISCONNECTED, PeerHandle(id, endpointId)))
    }
    override suspend fun stop() {
        close()
    }
    fun close() {
        generation++
        try { client?.stopAdvertising(); client?.stopDiscovery(); client?.stopAllEndpoints() } catch (_: Exception) { /* revoked permissions */ }
        transfers.values.forEach { it.completeExceptionally(IOException("Nearby stopped")) }; transfers.clear()
        accepted.clear()
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
          finally { transfers.remove(key) }
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
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(Unit)
            else continuation.resumeWithException(task.exception ?: IOException("Nearby operation failed"))
        }
    }
}
