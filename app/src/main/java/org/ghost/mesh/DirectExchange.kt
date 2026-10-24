package org.ghost.mesh

import com.google.protobuf.ByteString
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.contracts.v1.PeerHelloV1
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity
import org.ghost.transport.api.*
import org.ghost.transport.nearby.NearbyAdapter

/** Paired neighbor exchange, including store-and-forward of public SOS reports. */
class DirectExchange(private val scope: CoroutineScope, private val transport: NearbyAdapter,
    private val repository: LocalSosRepository, private val identity: () -> SigningIdentity) {
    private data class Session(val challenge: ByteArray, val startedAt: Long = System.currentTimeMillis(),
        var nodeId: String? = null, var replied: Boolean = false, val pending: MutableList<WirePacket> = mutableListOf(),
        val sent: MutableSet<String> = mutableSetOf(), val confirmed: MutableSet<String> = mutableSetOf(),
        val attempts: MutableMap<String, Int> = mutableMapOf(), val lastAttempt: MutableMap<String, Long> = mutableMapOf())
    private val sessions = mutableMapOf<String, Session>()
    private val sending = mutableMapOf<String, Job>()
    private val mutableIdentities = MutableStateFlow<Map<String, String>>(emptyMap())
    val identities = mutableIdentities.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                val peer = event.peer ?: return@collect
                try {
                    when (event.type) {
                        TransportEventType.PEER_CONNECTED -> {
                            sessions[peer.endpointId] = Session(ByteArray(32).also { SecureRandom().nextBytes(it) })
                            sendHello(peer)
                        }
                        TransportEventType.PEER_DISCONNECTED -> remove(peer.endpointId)
                        TransportEventType.PACKET_RECEIVED -> event.packetBytes?.let { receive(peer, WireCodec.decode(it)) }
                        else -> Unit
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (error: ProtocolException) { mutableMessage.value = "Packet rejected: ${error.code.name}. Check clocks and app versions."; transport.disconnect(peer.endpointId) }
                  catch (_: Exception) { mutableMessage.value = "A packet was rejected or could not be saved."; transport.disconnect(peer.endpointId) }
            }
        }
        scope.launch {
            transport.peers.collect { peers -> sessions.keys.toList().filter { id -> peers.none { it.endpointId == id } }.forEach(::remove) }
        }
        scope.launch { repository.observeRequests().collect { flush() } }
        scope.launch {
            while (isActive) {
                delay(10000)
                if (transport.state.value.active) {
                    sessions.toMap().filterValues { it.nodeId == null && System.currentTimeMillis() - it.startedAt > 30000 }
                        .keys.forEach { transport.disconnect(it) }
                    flush()
                }
            }
        }
    }
    private fun remove(endpoint: String) {
        sessions.remove(endpoint); sending.remove(endpoint)?.cancel()
        mutableIdentities.update { it - endpoint }
    }
    private suspend fun sendHello(peer: PeerHandle, replyTo: ByteString = ByteString.EMPTY) {
        val session = sessions[peer.endpointId] ?: return
        val packet = withContext(Dispatchers.IO) {
            SignedPackets.create(identity(), 13, PeerHelloV1.newBuilder().setChallenge(ByteString.copyFrom(session.challenge))
                .setReplyTo(replyTo).build().toByteString(), System.currentTimeMillis(), ttl = 300000)
        }
        // Do not block the receive collector waiting for radio transfer callbacks.
        scope.launch { if (transport.send(peer, packet).code != SendResultCode.ACCEPTED_BY_TRANSPORT) transport.disconnect(peer.endpointId) }
    }
    private suspend fun receive(peer: PeerHandle, packet: WirePacket) {
        val session = sessions[peer.endpointId] ?: return
        if (peer !in transport.peers.value) return
        val envelope = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
        if (envelope.kindValue == 13) {
            val verified = withContext(Dispatchers.IO) { PacketValidator().verify(packet, System.currentTimeMillis()) }
            val hello = verified.payload as PeerHelloV1
            val node = SigningIdentity.hex(verified.envelope.senderNodeId.toByteArray())
            if (sessions[peer.endpointId] !== session) return
            require(session.nodeId == null || session.nodeId == node)
            if (hello.replyTo.isEmpty) {
                if (!session.replied) { session.replied = true; sendHello(peer, hello.challenge) }
            }
            else {
                require(hello.replyTo.toByteArray().contentEquals(session.challenge))
                require(node != withContext(Dispatchers.IO) { identity().nodeId.value })
                session.nodeId = node
                mutableIdentities.update { it + (peer.endpointId to node) }
                mutableMessage.value = "Phone identity verified. Public SOS reports can be exchanged."
                val waiting = session.pending.toList(); session.pending.clear()
                waiting.forEach { receive(peer, it) }
                flush()
            }
            return
        }
        if (session.nodeId == null) {
            require(envelope.kindValue in setOf(1, 12) && session.pending.size < 4)
            session.pending.add(packet); return
        }
        val node = session.nodeId!!
        when (envelope.kindValue) {
            1 -> {
                val receipt = repository.receiveSos(packet, node)
                session.confirmed.add(envelope.packetId) // This neighbor already holds the report.
                val outgoing = WireCodec.decode(receipt)
                scope.launch { transport.send(peer, outgoing) }
                mutableMessage.value = "SOS saved from a nearby phone. Public reports can be relayed."
                flush()
            }
            12 -> {
                repository.receiveReceipt(packet, node, session.sent.toSet())
                session.confirmed.add(org.ghost.contracts.v1.DeliveryReceiptV1.parseFrom(envelope.payloadBytes).packetId)
                mutableMessage.value = "Signed receipt received. A nearby phone saved the SOS."
            }
            else -> error("Unsupported direct-exchange packet")
        }
    }
    fun flush() {
        transport.peers.value.forEach { peer ->
            val session = sessions[peer.endpointId]?.takeIf { it.nodeId != null } ?: return@forEach
            if (sending[peer.endpointId]?.isActive == true) return@forEach
            sending[peer.endpointId] = scope.launch {
                try {
                    val excluded = session.confirmed + session.attempts.filterValues { it >= 3 }.keys
                    for (row in repository.outgoing(session.nodeId!!, excluded)) {
                        if (sessions[peer.endpointId] !== session || peer !in transport.peers.value) break
                        if (row.packetId in session.confirmed || row.receivedBy == session.nodeId ||
                            (session.attempts[row.packetId] ?: 0) >= 3 || System.currentTimeMillis() - (session.lastAttempt[row.packetId] ?: 0) < 10000) continue
                        session.attempts[row.packetId] = (session.attempts[row.packetId] ?: 0) + 1
                        session.lastAttempt[row.packetId] = System.currentTimeMillis()
                        session.sent.add(row.packetId)
                        if (transport.send(peer, WireCodec.decode(row.wire)).code == SendResultCode.ACCEPTED_BY_TRANSPORT) repository.markSent(row.packetId)
                        else { mutableMessage.value = "Send interrupted. The SOS remains saved for retry."; break }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { mutableMessage.value = "Send unavailable. Reports remain saved locally." }
            }
        }
    }
    fun retry() { sessions.values.forEach { it.attempts.clear(); it.lastAttempt.clear() }; flush() }
}
