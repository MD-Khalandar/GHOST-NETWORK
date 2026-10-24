package org.ghost.simulation

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghost.domain.model.PacketId
import org.ghost.domain.model.WirePacket
import org.ghost.transport.api.*

data class FakeSendAttempt(
    val peer: PeerHandle,
    val packet: WirePacket,
    val attemptedAtMs: Long,
    val result: SendResult,
)

/** Scripted neighbor adapter, with no routing, persistence, or automatic receive callbacks.
 * Events are live (no replay); current peers are replayed. A slow event collector applies
 * backpressure after the small buffer fills. Start collectors before scripting events.
 */
class FakeTransport(private val clock: FakeClock) : GhostTransport {
    override val id = "simulation"
    private val mutex = Mutex()
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    private val mutablePeers = MutableStateFlow<List<PeerHandle>>(emptyList())
    private val mutableAttempts = MutableStateFlow<List<FakeSendAttempt>>(emptyList())
    private val sendResults = ArrayDeque<SendResult>()
    private var started = false
    private var permissionGranted = true
    override val events = mutableEvents.asSharedFlow()
    override val peers = mutablePeers.asStateFlow()
    val attempts = mutableAttempts.asStateFlow()

    override suspend fun start(): Result<Unit> {
        val allowed = mutex.withLock {
            if (permissionGranted) started = true
            permissionGranted
        }
        return if (!allowed) {
            mutableEvents.emit(TransportEvent(TransportEventType.TRANSPORT_ERROR, message = "Simulated: permission denied"))
            Result.failure(IllegalStateException("Simulated transport permission denied"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun stop() {
        val disconnected = mutex.withLock {
            started = false
            disconnectAll()
        }
        disconnected.forEach { mutableEvents.emit(it) }
    }

    suspend fun setPermissionGranted(granted: Boolean) {
        val disconnected = mutex.withLock {
            permissionGranted = granted
            if (!granted) {
                started = false
                disconnectAll()
            } else emptyList()
        }
        disconnected.forEach { mutableEvents.emit(it) }
        if (!granted) {
            mutableEvents.emit(TransportEvent(TransportEventType.TRANSPORT_ERROR, message = "Simulated: permission denied"))
        }
    }

    suspend fun connect(peer: PeerHandle) {
        val added = mutex.withLock {
            check(started && permissionGranted) { "Start the simulated transport before connecting peers" }
            require(peer.transportId == id) { "Peer belongs to another transport" }
            (peer !in peers.value).also { absent ->
                if (absent) mutablePeers.value = (peers.value + peer).sortedBy { it.endpointId }.snapshot()
            }
        }
        if (added) mutableEvents.emit(TransportEvent(TransportEventType.PEER_CONNECTED, peer = peer))
    }

    suspend fun disconnect(peer: PeerHandle) {
        val removed = mutex.withLock {
            (peer in peers.value).also { present ->
                if (present) mutablePeers.value = peers.value.filterNot { it == peer }.snapshot()
            }
        }
        if (removed) mutableEvents.emit(TransportEvent(TransportEventType.PEER_DISCONNECTED, peer = peer))
    }

    /** Consumed by the next send to a connected peer; offline attempts do not consume it. */
    suspend fun enqueueSendResult(result: SendResult) = mutex.withLock {
        sendResults.addLast(result)
        Unit
    }

    override suspend fun send(peer: PeerHandle, packet: WirePacket): SendResult = mutex.withLock {
        val result = when {
            !permissionGranted -> SendResult(SendResultCode.REJECTED_NO_PERMISSION)
            !started -> SendResult(SendResultCode.FAILED_RETRYABLE, "Simulated: transport stopped")
            peer !in peers.value -> SendResult(SendResultCode.REJECTED_NO_PEER)
            sendResults.isNotEmpty() -> sendResults.removeFirst()
            else -> SendResult(SendResultCode.ACCEPTED_BY_TRANSPORT, "Simulated: neighbor send only")
        }
        mutableAttempts.value = (attempts.value + FakeSendAttempt(peer, packet, clock.nowMs, result)).snapshot()
        result
    }

    /** The supplied contract exposes only an observed ID, not packet bytes or a signed receipt. */
    suspend fun receive(peer: PeerHandle, packetId: PacketId) {
        mutex.withLock {
            check(started && peer in peers.value) { "Simulated receive needs a connected peer" }
        }
        // Never hold the state mutex while applying event backpressure: a collector may send.
        mutableEvents.emit(TransportEvent(TransportEventType.PACKET_RECEIVED, peer, packetId))
    }

    private fun disconnectAll(): List<TransportEvent> {
        val previous = peers.value
        mutablePeers.value = emptyList()
        return previous.map { TransportEvent(TransportEventType.PEER_DISCONNECTED, peer = it) }
    }
}
