package org.ghost.data.local

import androidx.room.withTransaction
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.domain.repository.LocalPublishResult
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity

class LocalSosClosureRepository(private val db: GhostDatabase, private val identity: () -> SigningIdentity,
    private val now: () -> Long) {
    private val dao = db.closures()
    fun observe() = dao.observe()
    suspend fun close(requestId: String, state: SosState): Result<LocalPublishResult> = withContext(Dispatchers.IO) {
        try {
            Result.success(db.withTransaction {
                require(state in setOf(SosState.CANCELLED, SosState.RESOLVED))
                val original = db.sos().request(requestId) ?: error("Request not found.")
                val key = identity()
                require(original.owned && original.sender == key.nodeId.value) { "Only the requester can close this SOS." }
                dao.latest(requestId)?.let {
                    require(it.state == state.ordinal + 1) { "This SOS is already closed." }
                    return@withTransaction LocalPublishResult(it.eventId, it.publicSharing)
                }
                val time = maxOf(now(), original.createdAt + 1)
                require(time < original.expiresAt) { "This SOS has expired." }
                val wire = WireCodec.decode(original.wire)
                val payload = SosUpdateV1.newBuilder().setRequestId(requestId).setStateValue(state.ordinal + 1)
                    .setOriginalSosEnvelope(ByteString.copyFrom(WireCodec.unbase64(wire.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)))
                    .setOriginalSosSignature(ByteString.copyFrom(WireCodec.unbase64(wire.signature, 72))).build()
                val packet = SignedPackets.create(key, 2, payload.toByteString(), time, ttl = original.expiresAt - time)
                if (original.publicSharing) PacketValidator().verify(packet, now())
                val e = envelope(packet)
                require(dao.count() < 1000) { "Saved closure queue is full." }
                dao.insert(SosClosureRow(e.eventId, e.packetId, requestId, key.nodeId.value, state.ordinal + 1, true,
                    original.publicSharing, original.cloudConsent, time, original.expiresAt, WireCodec.encode(packet)))
                LocalPublishResult(e.eventId, original.publicSharing)
            })
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { Result.failure(error) }
    }
    suspend fun outgoing(peer: String, excluded: Set<String>): List<SosClosureRow> = withContext(Dispatchers.IO) {
        dao.outgoing(now(), peer).asSequence().filterNot { it.packetId in excluded }
            .mapNotNull { prepare(it, peer) }.take(100).toList()
    }
    suspend fun prepare(packet: String, peer: String): SosClosureRow? = withContext(Dispatchers.IO) {
        if (dao.delivery(packet, peer) != null) null else dao.packet(packet)?.let { prepare(it, peer) }
    }
    private fun prepare(row: SosClosureRow, peer: String): SosClosureRow? {
        val packet = WireCodec.decode(row.wire); val local = identity().nodeId
        if (!row.publicSharing || row.expiresAt <= now() || row.sender == peer || peer == local.value ||
            packet.relay.remainingHops == 0 || packet.relay.previousNodeId?.value == peer) return null
        return row.copy(wire = WireCodec.encode(packet.copy(relay = RelayMetadata(
            packet.relay.remainingHops - 1, packet.relay.hopCount + 1, local))))
    }
    suspend fun receive(packet: WirePacket, peer: String): ByteArray = withContext(Dispatchers.IO) {
        val verified = PacketValidator().verify(packet, now()); val e = verified.envelope
        require(e.kindValue == 2 && packet.relay.hopCount > 0 && packet.relay.previousNodeId?.value == peer)
        val p = verified.payload as SosUpdateV1
        require(p.stateValue in 5..6 && !p.originalSosEnvelope.isEmpty)
        val base = ImmutableEnvelopeV1.parseFrom(p.originalSosEnvelope)
        val sos = SosCreateV1.parseFrom(base.payloadBytes)
        val owner = SigningIdentity.hex(e.senderNodeId.toByteArray()); val local = identity()
        require(peer != local.nodeId.value)
        db.withTransaction {
            require(db.resources().packet(e.packetId) == null && db.resources().event(e.eventId) == null &&
                db.sos().packet(e.packetId) == null && db.sos().event(e.eventId) == null &&
                db.sos().cloudReturn(e.packetId) == null && db.sos().cloudReturnEvent(e.eventId) == null)
            val known = db.sos().request(p.requestId)
            if (known != null) {
                require(known.publicSharing && WireCodec.decode(known.wire).immutableEnvelopeBytesBase64 == WireCodec.base64(p.originalSosEnvelope.toByteArray())) { "Conflicting SOS ownership proof." }
            } else {
                require(owner != local.nodeId.value) { "Unknown SOS signed by this phone." }
                require(db.resources().packet(base.packetId) == null && db.resources().event(base.eventId) == null &&
                    db.sos().event(base.eventId) == null && db.sos().packet(base.packetId) == null &&
                    db.sos().cloudReturn(base.packetId) == null && db.sos().cloudReturnEvent(base.eventId) == null &&
                    dao.packet(base.packetId) == null && dao.event(base.eventId) == null)
                db.sos().pruneReceived(now()); require(db.sos().count() < 1000) { "Local report storage is full." }
                val original = WirePacket(WireCodec.base64(p.originalSosEnvelope.toByteArray()), WireCodec.base64(p.originalSosSignature.toByteArray()), RelayMetadata(0, 4, NodeId(peer)))
                check(db.sos().insert(ReportRow(base.eventId, base.packetId, p.requestId, owner,
                    SosCategory.entries[sos.categoryValue - 1].name, sos.description, if (sos.hasPeopleCount()) sos.peopleCount else null,
                    if (sos.hasCoarseArea()) sos.coarseArea else null, base.createdAtMs, now(), base.expiresAtMs,
                    false, true, sos.consent.cloudProcessingAllowed, WireCodec.encode(original))) != -1L)
            }
            require(db.assistance().packet(e.packetId)==null && db.assistance().event(e.eventId)==null)
            val old = dao.event(e.eventId) ?: dao.packet(e.packetId)
            if (old != null) {
                val stored = WireCodec.decode(old.wire)
                require(stored.immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting closure." }
                if (!old.owned && packet.relay.hopCount < stored.relay.hopCount)
                    dao.updateRelay(old.packetId, WireCodec.encode(stored.copy(relay = packet.relay)))
            } else {
                require(dao.count() < 1000) { "Saved closure queue is full." }
                dao.insert(SosClosureRow(e.eventId, e.packetId, p.requestId, owner, p.stateValue, false, true,
                    sos.consent.cloudProcessingAllowed, e.createdAtMs, e.expiresAtMs, WireCodec.encode(packet)))
            }
            val delivery = dao.delivery(e.packetId, peer) ?: SosClosureDeliveryRow(e.packetId, peer)
            val receipt = delivery.receiptToPeer?.takeIf { envelope(WireCodec.decode(it)).expiresAtMs > now() }
                ?: WireCodec.encode(SignedPackets.create(local, 12, DeliveryReceiptV1.newBuilder().setPacketId(e.packetId)
                    .setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(local.nodeId.value))).setReceiptTypeValue(1)
                    .setObservedAtMs(now()).build().toByteString(), now(), destination = peer))
            dao.saveDelivery(delivery.copy(receiptToPeer = receipt)); receipt
        }
    }
    private fun envelope(packet: WirePacket) = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
}
