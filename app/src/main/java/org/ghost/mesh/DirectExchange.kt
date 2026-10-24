package org.ghost.mesh

import com.google.protobuf.ByteString
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ghost.contracts.v1.DeliveryReceiptV1
import org.ghost.contracts.v1.ImmutableEnvelopeV1
import org.ghost.contracts.v1.PeerHelloV1
import org.ghost.contracts.v1.NetworkAnnouncementV1
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity
import org.ghost.transport.api.*

/** Bounded store-and-forward over neighbor links after the user joins the mesh.
 * Key possession authenticates a node ID, not a person's real-world identity.
 * Session state belongs to the caller's single-threaded scope (the app's main scope).
 */
class DirectExchange(private val scope: CoroutineScope, private val transport: GhostTransport,
    private val repository: LocalSosRepository, private val identity: () -> SigningIdentity,
    private val now: () -> Long = System::currentTimeMillis, private val cloudKey: ByteArray? = null,
    private val capabilities: () -> NodeCapabilities = { NodeCapabilities() }) {
    private data class Outgoing(val packetId: String, val wire: ByteArray, val cloudReturn: Boolean)
    private class Session(val scope: CoroutineScope, val startedAt: Long) {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var nodeId: String? = null
        var helloNode: String? = null
        var replied = false
        val incoming = Channel<ByteArray>(16)
        val controls = Channel<WirePacket>(16)
        val pending = mutableListOf<WirePacket>()
        val sent = mutableMapOf<String, Boolean>() // packet ID -> server return rather than SOS
        val confirmed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val lastAttempt = mutableMapOf<String, Long>()
        val receipts = mutableMapOf<String, CompletableDeferred<Unit>>()
        var sending: Job? = null
        var flushAgain = false
        var sendingReports = false
        var supportsTopology = false
        var supportsResources = false
        var supportsOfferUpdates = false
        var topologySending: Job? = null
        val topologySent = mutableMapOf<String, Pair<String, Int>>()
        val topologyLastSent = mutableMapOf<String, Long>()
    }
    private val sessions = mutableMapOf<String, Session>()
    private val mutableIdentities = MutableStateFlow<Map<String, String>>(emptyMap())
    val identities = mutableIdentities.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val topologyCache = MeshTopology()
    private val mutableTopology = MutableStateFlow(TopologySnapshot())
    val topology = mutableTopology.asStateFlow()
    private val topologyWake = Channel<Unit>(Channel.CONFLATED)
    private var localAnnouncement: WirePacket? = null
    private var localNeighbors = emptyList<String>()
    private var localCapabilities = NodeCapabilities()
    private var localAnnouncedAt = 0L

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { event ->
                val peer = event.peer ?: return@collect
                when (event.type) {
                    TransportEventType.PEER_CONNECTED -> if (peer in transport.peers.value) connect(peer)
                    TransportEventType.PEER_DISCONNECTED -> remove(peer.endpointId)
                    TransportEventType.PACKET_RECEIVED -> event.packetBytes?.let { bytes ->
                        val session = sessions[peer.endpointId] ?: return@let
                        if (bytes.size > PacketValidator.MAX_PACKET || !session.incoming.trySend(bytes).isSuccess)
                            disconnect(peer, "Receive queue full. Reconnect to retry saved reports.")
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            transport.peers.collect { peers ->
                sessions.keys.toList().filter { id -> peers.none { it.endpointId == id } }.forEach(::remove)
            }
        }
        scope.launch {
            repository.observeRequests().catch { mutableMessage.value = "Local storage unavailable. Relay is waiting." }
                .collect { flush() }
        }
        scope.launch {
            repository.observeCloudReturns().catch { mutableMessage.value = "Return queue unavailable. Saved acknowledgements will wait." }
                .collect { flush() }
        }
        scope.launch { repository.resources.observeRows().catch { }.collect { flush() } }
        scope.launch {
            while (isActive) {
                delay(RETRY_DELAY)
                sessions.toMap().forEach { (endpoint, session) ->
                    if (session.nodeId == null && now() - session.startedAt >= 30000)
                        disconnect(PeerHandle(transport.id, endpoint), "Identity verification timed out. Reconnect to try again.")
                }
                flush()
            }
        }
        scope.launch {
            while (isActive) {
                withTimeoutOrNull(5000) { topologyWake.receive() }
                try { updateTopology() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Graph metadata must never interrupt durable SOS delivery. */ }
                delay(1000)
            }
        }
    }

    private fun current(peer: PeerHandle, session: Session) =
        sessions[peer.endpointId] === session && peer in transport.peers.value

    private fun remove(endpoint: String) {
        sessions.remove(endpoint)?.let { session ->
            session.incoming.close(); session.controls.close(); session.scope.cancel()
        }
        mutableIdentities.update { it - endpoint }
        if (sessions.isEmpty()) {
            topologyCache.clear(); localAnnouncement = null
            mutableTopology.value = TopologySnapshot()
        }
        topologyWake.trySend(Unit)
    }

    private fun disconnect(peer: PeerHandle, message: String) {
        mutableMessage.value = message
        remove(peer.endpointId)
        transport.disconnect(peer.endpointId)
    }

    private fun connect(peer: PeerHandle) {
        remove(peer.endpointId)
        if (sessions.size >= 4) { transport.disconnect(peer.endpointId); return }
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val session = Session(child, now())
        sessions[peer.endpointId] = session
        // Independent, bounded workers prevent one slow phone blocking the others.
        child.launch {
            for (bytes in session.incoming) {
                try { if (current(peer, session)) receive(peer, session, WireCodec.decode(bytes)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: ProtocolException) {
                    if (error.code == Rejection.EXPIRED) mutableMessage.value = "Expired packet ignored. Other SOS reports can still relay."
                    else disconnect(peer, "Packet rejected: ${error.code.name}. Check clocks and app versions.")
                }
                catch (_: Exception) { disconnect(peer, "Packet rejected or local storage unavailable. Reports remain saved; reconnect to retry.") }
            }
        }
        child.launch {
            try {
                for (packet in session.controls) {
                    if (!current(peer, session)) break
                    if (transport.send(peer, packet).code != SendResultCode.ACCEPTED_BY_TRANSPORT)
                        disconnect(peer, "Reply interrupted. Reports remain saved; reconnect to retry.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { disconnect(peer, "Reply unavailable. Reconnect to retry saved reports.") }
        }
        child.launch {
            try { sendHello(peer, session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { disconnect(peer, "Signing identity unavailable. Reconnect to retry.") }
        }
    }

    private fun control(peer: PeerHandle, session: Session, packet: WirePacket) {
        if (current(peer, session) && !session.controls.trySend(packet).isSuccess)
            disconnect(peer, "Reply queue full. Reconnect to retry saved reports.")
    }

    private suspend fun sendHello(peer: PeerHandle, session: Session, replyTo: ByteString = ByteString.EMPTY) {
        val packet = withContext(Dispatchers.IO) {
            SignedPackets.create(identity(), 13, PeerHelloV1.newBuilder().setChallenge(ByteString.copyFrom(session.challenge))
                .setReplyTo(replyTo).setSupportsTopology(true).setSupportsResources(true).setSupportsOfferUpdates(true).build().toByteString(), now(), ttl = 300000)
        }
        control(peer, session, packet)
    }

    private suspend fun receive(peer: PeerHandle, session: Session, packet: WirePacket) {
        val envelope = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
        if (envelope.kindValue == 13) {
            val verified = withContext(Dispatchers.IO) { PacketValidator().verify(packet, now()) }
            val hello = verified.payload as PeerHelloV1
            val node = SigningIdentity.hex(verified.envelope.senderNodeId.toByteArray())
            require(node != withContext(Dispatchers.IO) { identity().nodeId.value })
            if (!current(peer, session)) return
            require(session.helloNode == null || session.helloNode == node)
            session.helloNode = node
            session.supportsTopology = hello.supportsTopology
            session.supportsResources = hello.supportsResources
            session.supportsOfferUpdates = hello.supportsOfferUpdates
            if (hello.replyTo.isEmpty) {
                if (!session.replied) { session.replied = true; sendHello(peer, session, hello.challenge) }
            } else {
                require(hello.replyTo.toByteArray().contentEquals(session.challenge))
                if (session.nodeId != null) return
                session.nodeId = node
                mutableIdentities.update { it + (peer.endpointId to node) }
                mutableMessage.value = "Phone identity verified. Public SOS relay is ready."
                val waiting = session.pending.toList(); session.pending.clear()
                waiting.forEach { receive(peer, session, it) }
                flush()
                topologyWake.trySend(Unit)
            }
            return
        }
        val node = session.nodeId
        if (node == null) {
            require(envelope.kindValue in setOf(1, 3, 5, 6, 7, 12, 15) && session.pending.size < 4)
            session.pending.add(packet); return
        }
        when (envelope.kindValue) {
            3, 5, 6 -> {
                require(session.supportsResources && (envelope.kindValue != 6 || session.supportsOfferUpdates))
                val receipt = repository.resources.receive(packet, node)
                if (!current(peer, session)) return
                session.confirmed.add(envelope.packetId)
                session.receipts[envelope.packetId]?.complete(Unit)
                control(peer, session, WireCodec.decode(receipt))
                mutableMessage.value = "Helper or supply offer saved from a nearby phone."
                flush()
            }
            15 -> {
                if (!session.supportsTopology) return
                // Graph updates are best-effort and have no durable delivery receipts.
                val verified = withContext(Dispatchers.IO) { PacketValidator().verify(packet, now()) }
                if (!current(peer, session)) return
                if (topologyCache.accept(verified, node, identity().nodeId.value, now())) {
                    publishTopology()
                    topologyWake.trySend(Unit)
                }
            }
            1 -> {
                val receipt = repository.receiveSos(packet, node)
                if (!current(peer, session)) return
                session.confirmed.add(envelope.packetId)
                session.receipts[envelope.packetId]?.complete(Unit)
                control(peer, session, WireCodec.decode(receipt))
                mutableMessage.value = "SOS saved from a nearby phone. Eligible reports are queued for relay."
                flush()
            }
            12 -> {
                repository.receiveReceipt(packet, node, session.sent.keys.toSet())
                if (!current(peer, session)) return
                val id = DeliveryReceiptV1.parseFrom(envelope.payloadBytes).packetId
                session.confirmed.add(id)
                session.receipts[id]?.complete(Unit)
                mutableMessage.value = if (session.sent[id] == true) "Signed receipt received. The next phone saved the server acknowledgement."
                    else "A nearby phone saved the shared report."
            }
            7 -> {
                val pin = cloudKey
                if (pin == null) { mutableMessage.value = "Cloud signing key is not configured. Server acknowledgement ignored."; return }
                val receipt = repository.receiveCloudReturn(packet, node, pin)
                if (!current(peer, session)) return
                session.confirmed.add(envelope.packetId)
                session.receipts[envelope.packetId]?.complete(Unit)
                control(peer, session, WireCodec.decode(receipt))
                mutableMessage.value = if (envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(identity().nodeId.value)))
                    "Received by server. The signed acknowledgement reached this phone."
                    else "Signed server acknowledgement saved for relay to its destination."
                flush()
            }
            else -> throw ProtocolException(Rejection.UNSUPPORTED_KIND)
        }
    }

    private fun publishTopology() {
        mutableTopology.value = mutableTopology.value.copy(announcements = topologyCache.reports())
    }

    private suspend fun updateTopology() {
        if (sessions.isEmpty()) return
        val own = withContext(Dispatchers.IO) { identity() }
        val direct = mutableIdentities.value.values.distinct().sorted()
        val status = capabilities()
        topologyCache.prune(now())
        if (localAnnouncement == null || direct != localNeighbors || status != localCapabilities || now() - localAnnouncedAt >= 20000) {
            val timestamp = maxOf(now(), localAnnouncedAt + 1)
            val packet = withContext(Dispatchers.IO) {
                SignedPackets.create(own, 15, NetworkAnnouncementV1.newBuilder()
                    .addAllNeighborNodeIds(direct.map { ByteString.copyFrom(WireCodec.bytes(it)) })
                    .setInternetValue(status.internet.ordinal).setGatewayConfigured(status.gatewayConfigured)
                    .setServerValue(status.server.ordinal).build().toByteString(), timestamp, ttl = MeshTopology.TTL)
            }
            if (sessions.isEmpty()) return
            localNeighbors = direct; localCapabilities = status; localAnnouncedAt = timestamp; localAnnouncement = packet
        }
        mutableTopology.value = TopologySnapshot(own.nodeId.value, status, topologyCache.reports())
        sessions.toMap().forEach { (endpoint, session) ->
            val peer = PeerHandle(transport.id, endpoint)
            val target = session.nodeId ?: return@forEach
            if (!session.supportsTopology || session.topologySending?.isActive == true || session.sendingReports) return@forEach
            val outgoing = listOf(own.nodeId.value to MeshTopology.forward(localAnnouncement!!, own.nodeId.value)) +
                topologyCache.outgoing(own.nodeId.value, target)
            session.topologySent.keys.retainAll(outgoing.map { it.first }.toSet())
            session.topologyLastSent.keys.retainAll(outgoing.map { it.first }.toSet())
            session.topologySending = session.scope.launch {
                for ((origin, packet) in outgoing.filter {
                    session.topologySent[it.first] != (it.second.immutableEnvelopeBytesBase64 to it.second.relay.hopCount)
                }.sortedBy { session.topologyLastSent[it.first] ?: Long.MIN_VALUE }.take(4)) {
                    if (!current(peer, session) || session.sendingReports) break
                    try {
                        val result = withTimeoutOrNull(2000) { transport.send(peer, packet) }
                        if (result?.code != SendResultCode.ACCEPTED_BY_TRANSPORT) break
                        session.topologySent[origin] = packet.immutableEnvelopeBytesBase64 to packet.relay.hopCount
                        session.topologyLastSent[origin] = now()
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { break }
                }
            }
        }
    }

    fun flush() {
        transport.peers.value.forEach { peer ->
            val session = sessions[peer.endpointId]?.takeIf { it.nodeId != null } ?: return@forEach
            if (session.sending?.isActive == true) { session.flushAgain = true; return@forEach }
            session.sending = session.scope.launch {
                do {
                    session.flushAgain = false
                    try {
                        val active = repository.activePacketIds()
                        if (!current(peer, session)) return@launch
                        session.sent.keys.retainAll(active); session.confirmed.retainAll(active)
                        session.attempts.keys.retainAll(active); session.lastAttempt.keys.retainAll(active)
                        val excluded = session.confirmed + session.attempts.filterValues { it >= MAX_ATTEMPTS }.keys +
                            session.lastAttempt.filterValues { now() - it < RETRY_DELAY }.keys
                        val reports = repository.outgoing(session.nodeId!!, excluded).map { Outgoing(it.packetId, it.wire, false) }
                        val returns = if (cloudKey != null) repository.outgoingCloudReturns(session.nodeId!!, excluded)
                            .map { Outgoing(it.packetId, it.wire, true) } else emptyList()
                        val resources = if (session.supportsResources) repository.resources.outgoing(session.nodeId!!, excluded)
                            .filter { it.kind != 6 || session.supportsOfferUpdates }
                            .map { Outgoing(it.packetId, it.wire, false) } else emptyList()
                        // Interleave return traffic with SOS so neither queue starves the other.
                        val candidates = buildList {
                            for (index in 0 until maxOf(reports.size, returns.size)) {
                                reports.getOrNull(index)?.let(::add)
                                returns.getOrNull(index)?.let(::add)
                            }
                            addAll(resources)
                        }
                        if (candidates.isNotEmpty()) { session.sendingReports = true; session.topologySending?.cancel() }
                        for (candidate in candidates) {
                            if (!current(peer, session)) break
                            // Reload because a receipt or expiry can arrive during a batch.
                            val row = if (candidate.cloudReturn) repository.prepareCloudReturn(candidate.packetId, session.nodeId!!)
                                ?.let { Outgoing(it.packetId, it.wire, true) }
                            else repository.prepareOutgoing(candidate.packetId, session.nodeId!!)?.let { Outgoing(it.packetId, it.wire, false) }
                                ?: repository.resources.prepare(candidate.packetId, session.nodeId!!)?.let { Outgoing(it.packetId, it.wire, false) }
                            if (row == null) continue
                            if (!current(peer, session) || row.packetId in session.confirmed) continue
                            val completion = CompletableDeferred<Unit>()
                            session.receipts[row.packetId] = completion
                            session.sent[row.packetId] = row.cloudReturn
                            session.attempts[row.packetId] = (session.attempts[row.packetId] ?: 0) + 1
                            session.lastAttempt[row.packetId] = now()
                            try {
                                val result = transport.send(peer, WireCodec.decode(row.wire))
                                if (result.code == SendResultCode.ACCEPTED_BY_TRANSPORT) {
                                    if (!row.cloudReturn) repository.markSent(row.packetId)
                                    // One unacknowledged report per neighbor bounds pressure.
                                    // The receive worker verifies and commits the receipt.
                                    if (withTimeoutOrNull(RETRY_DELAY) { completion.await(); true } != true)
                                        mutableMessage.value = "Receipt pending. The ${if (row.cloudReturn) "server acknowledgement" else "report"} stays saved for retry."
                                } else mutableMessage.value = "Send interrupted. The packet stays saved for retry."
                                if (row.packetId !in session.confirmed && session.attempts.getValue(row.packetId) >= MAX_ATTEMPTS)
                                    mutableMessage.value = "Automatic retries paused for ${if (row.cloudReturn) "a server acknowledgement" else "a report"}. Use Retry queued sends or reconnect."
                                if (result.code != SendResultCode.ACCEPTED_BY_TRANSPORT) break
                            } finally {
                                if (session.receipts[row.packetId] === completion) session.receipts.remove(row.packetId)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { mutableMessage.value = "Relay unavailable. Reports remain saved locally; retry when storage and radios are ready." }
                      finally { session.sendingReports = false }
                    } while (session.flushAgain && current(peer, session))
            }
        }
    }

    fun retry() {
        sessions.values.forEach { session ->
            session.sending?.cancel(); session.sending = null
            session.attempts.clear(); session.lastAttempt.clear()
        }
        mutableMessage.value = "Retrying unacknowledged reports. Saved receipts are preserved."
        flush()
    }

    companion object {
        private const val RETRY_DELAY = 10000L
        private const val MAX_ATTEMPTS = 3
    }
}
