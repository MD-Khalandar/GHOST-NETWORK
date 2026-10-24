package org.ghost.transport.api

import kotlinx.coroutines.flow.Flow
import org.ghost.domain.model.PacketId
import org.ghost.domain.model.WirePacket

/** Ephemeral neighbor identity supplied by a concrete transport. */
data class PeerHandle(
    val transportId: String,
    val endpointId: String,
)

enum class TransportEventType {
    PEER_CONNECTED,
    PEER_DISCONNECTED,
    PACKET_RECEIVED,
    TRANSPORT_ERROR,
}

data class TransportEvent(
    val type: TransportEventType,
    val peer: PeerHandle? = null,
    val packetId: PacketId? = null,
    val message: String? = null,
)

enum class SendResultCode {
    ACCEPTED_BY_TRANSPORT,
    REJECTED_NO_PERMISSION,
    REJECTED_NO_PEER,
    REJECTED_TOO_LARGE,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}

data class SendResult(
    val code: SendResultCode,
    val message: String? = null,
)

/**
 * Boundary owned by A. Nearby, MeshEngine, and future adapters depend on this
 * contract rather than on one another's SDKs or persistence implementations.
 */
interface GhostTransport {
    val id: String
    val events: Flow<TransportEvent>
    val peers: Flow<List<PeerHandle>>

    suspend fun start(): Result<Unit>

    suspend fun stop()

    suspend fun send(peer: PeerHandle, packet: WirePacket): SendResult
}
