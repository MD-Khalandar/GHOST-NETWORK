package org.ghost.data.local

import androidx.room.withTransaction
import com.google.protobuf.ByteString
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.ghost.contracts.v1.ConsentV1
import org.ghost.contracts.v1.DeliveryReceiptV1
import org.ghost.contracts.v1.SosCreateV1
import org.ghost.domain.model.*
import org.ghost.domain.repository.LocalPublishResult
import org.ghost.domain.repository.SosRepository
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity

class LocalSosRepository(
    private val db: GhostDatabase,
    private val identity: () -> SigningIdentity,
    private val now: () -> Long = System::currentTimeMillis,
) : SosRepository {
    private val dao = db.sos()
    private val writes = Mutex()
    private val json = Json
    fun observeDraft(): Flow<SosDraft?> = dao.observeDraft().map { it?.let { row -> json.decodeFromString<SosDraft>(row.value) } }
    suspend fun initializeDraft() = withContext(Dispatchers.IO) {
        db.withTransaction { if (dao.draft() == null) dao.saveDraft(DraftRow(value = json.encodeToString(SosDraft()))) }
    }
    suspend fun saveDraft(draft: SosDraft) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = dao.draft()?.let { json.decodeFromString<SosDraft>(it.value) }
            // Ignore late form updates after successful publication replaced this draft.
            if (current == null || current.requestId == draft.requestId) dao.saveDraft(DraftRow(value = json.encodeToString(draft)))
        }
    }
    override fun observeRequests(): Flow<List<DashboardSosSummary>> = combine(dao.observe(), flow {
        while (true) { emit(now()); delay(30000) }
    }) { rows, timestamp ->
        rows.map { row ->
            val expired = row.expiresAt <= timestamp
            DashboardSosSummary(RequestId(row.requestId), SosCategory.valueOf(row.category), SosState.OPEN,
                row.description, row.people, row.area, NodeId(row.sender), row.createdAt, row.receivedAt, expired,
                when {
                    expired -> LocalDelivery.EXPIRED
                    !row.owned -> LocalDelivery.RECEIVED_FROM_PEER
                    !row.publicSharing -> LocalDelivery.SAVED_LOCALLY
                    row.receivedBy != null -> LocalDelivery.RECEIVED_BY_PHONE
                    row.sentAt != null -> LocalDelivery.SENT_TO_NEIGHBOR
                    else -> LocalDelivery.QUEUED
                }, row.owned)
        }
    }
    override suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult> = withContext(Dispatchers.IO) {
        try {
            writes.withLock {
                require(payload.description.isNotBlank() && payload.description.length <= 2000) { "Describe the help you need (up to 2000 characters)." }
                require(payload.peopleCount == null || payload.peopleCount in 1..1000) { "People count must be between 1 and 1000." }
                require((payload.coarseArea?.length ?: 0) <= 120 && (payload.landmark?.length ?: 0) <= 120) { "Keep the area short (up to 120 characters)." }
                require(UUID.fromString(payload.requestId.value).toString() == payload.requestId.value) { "Invalid request identifier." }
                val key = identity()
                val timestamp = now()
                val data = SosCreateV1.newBuilder().setRequestId(payload.requestId.value).setCategoryValue(payload.category.ordinal + 1)
                    .setDescription(payload.description).setConsent(ConsentV1.newBuilder()
                        .setPublicMeshAllowed(payload.consent.publicMeshAllowed).setCloudProcessingAllowed(payload.consent.cloudProcessingAllowed))
                    .apply { payload.peopleCount?.let { peopleCount = it }; payload.coarseArea?.let { coarseArea = it }; payload.landmark?.let { landmark = it } }.build()
                val packet = SignedPackets.create(key, 1, data.toByteString(), timestamp)
                val verified = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                db.withTransaction {
                    val existing = dao.request(payload.requestId.value)
                    if (existing != null) {
                        val original = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(existing.wire).immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                        require(existing.owned && existing.sender == key.nodeId.value && original.payloadBytes == data.toByteString()) { "This request has already been saved with different content." }
                        LocalPublishResult(existing.eventId, existing.publicSharing)
                    } else {
                        require(dao.activeOwned(timestamp) < 100) { "Local send queue is full. Wait for older reports to expire." }
                        val row = ReportRow(verified.eventId, verified.packetId, payload.requestId.value, key.nodeId.value,
                            payload.category.name, payload.description, payload.peopleCount, payload.coarseArea, timestamp, timestamp,
                            verified.expiresAtMs, true, payload.consent.publicMeshAllowed, payload.consent.cloudProcessingAllowed, WireCodec.encode(packet))
                        check(dao.insert(row) != -1L) { "Request could not be saved." }
                        val draft = dao.draft()?.let { json.decodeFromString<SosDraft>(it.value) }
                        if (draft?.requestId == payload.requestId.value) dao.saveDraft(DraftRow(value = json.encodeToString(SosDraft())))
                        LocalPublishResult(verified.eventId, payload.consent.publicMeshAllowed)
                    }
                }.let { Result.success(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { Result.failure(error) }
    }

    /** With a neighbor, prepare one transfer without changing the stored signed envelope. */
    suspend fun outgoing(peerNode: String? = null, excludedPacketIds: Set<String> = emptySet()): List<ReportRow> = withContext(Dispatchers.IO) {
        val rows = dao.outgoing(now()).filterNot { it.packetId in excludedPacketIds }
        if (peerNode == null) return@withContext rows
        val localNode = identity().nodeId
        rows.asSequence().mapNotNull { row ->
            val packet = WireCodec.decode(row.wire)
            // Also count the first transfer for reports saved by the old direct-only app.
            val relay = if (!row.owned && packet.relay.hopCount == 0) RelayMetadata(3, 1, NodeId(row.sender)) else packet.relay
            if (peerNode == localNode.value || row.sender == peerNode || row.receivedBy == peerNode ||
                relay.previousNodeId?.value == peerNode || relay.remainingHops == 0) null
            else row.copy(wire = WireCodec.encode(packet.copy(relay = RelayMetadata(
                relay.remainingHops - 1, relay.hopCount + 1, localNode))))
        }.take(100).toList()
    }
    suspend fun markSent(packetId: String) = withContext(Dispatchers.IO) { dao.markSent(packetId, now()) }

    /** Returns a signed persistence receipt only after the report and receipt have committed. */
    suspend fun receiveSos(packet: WirePacket, peerNode: String): ByteArray = withContext(Dispatchers.IO) {
        val result = PacketValidator().verify(packet, now())
        require(result.envelope.kindValue == 1)
        val origin = SigningIdentity.hex(result.envelope.senderNodeId.toByteArray())
        val relay = packet.relay
        // The neighbor proves the last hop; the original author proves the content.
        require(if (relay.hopCount == 0) origin == peerNode else relay.previousNodeId?.value == peerNode) { "Relay does not match the connected phone." }
        // Older direct-only senders did not count their first radio transfer.
        val receivedPacket = if (relay.hopCount == 0) packet.copy(relay = RelayMetadata(3, 1, NodeId(peerNode))) else packet
        val p = result.payload as SosCreateV1
        val key = identity()
        require(peerNode != key.nodeId.value) { "Cannot connect to this phone's own identity." }
        val receipt = SignedPackets.create(key, 12, DeliveryReceiptV1.newBuilder().setPacketId(result.envelope.packetId)
            .setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(key.nodeId.value))).setReceiptTypeValue(1).setObservedAtMs(now()).build().toByteString(),
            now(), destination = peerNode)
        db.withTransaction {
            val existing = dao.event(result.envelope.eventId) ?: dao.packet(result.envelope.packetId) ?: dao.request(p.requestId)
            if (existing != null) {
                require(existing.publicSharing && WireCodec.decode(existing.wire).immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting report identifier." }
                // A duplicate can arrive from a different neighbor or loop back to its author.
                // Only reuse a cached receipt when this phone signed it for this neighbor.
                val cached = existing.receipt?.let { bytes ->
                    val e = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(bytes).immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                    bytes.takeIf { e.expiresAtMs > now() && e.senderNodeId.toByteArray().contentEquals(WireCodec.bytes(key.nodeId.value)) &&
                        e.destination.kindValue == 2 && e.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(peerNode)) }
                }
                cached ?: WireCodec.encode(receipt)
            } else {
                require(origin != key.nodeId.value) { "Unknown report signed by this phone." }
                dao.pruneReceived(now())
                require(dao.count() < 1000) { "Local report storage is full." }
                val bytes = WireCodec.encode(receipt)
                check(dao.insert(ReportRow(result.envelope.eventId, result.envelope.packetId, p.requestId, origin,
                    SosCategory.entries[p.categoryValue - 1].name, p.description, if (p.hasPeopleCount()) p.peopleCount else null,
                    if (p.hasCoarseArea()) p.coarseArea else null, result.envelope.createdAtMs, now(), result.envelope.expiresAtMs,
                    false, true, p.consent.cloudProcessingAllowed, WireCodec.encode(receivedPacket), receipt = bytes)) != -1L)
                bytes
            }
        }
    }

    suspend fun receiveReceipt(packet: WirePacket, peerNode: String, sentPacketIds: Set<String>) = withContext(Dispatchers.IO) {
        val envelope = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
        require(envelope.kindValue == 12)
        val p = DeliveryReceiptV1.parseFrom(envelope.payloadBytes)
        val original = dao.packet(p.packetId)
        val key = identity()
        require(original?.publicSharing == true && p.packetId in sentPacketIds && p.receiptTypeValue == 1) { "Receipt does not match a packet sent to this peer." }
        val valid = PacketValidator(lookup = AuthorityLookup { kind, id, _ ->
            if (kind == AuthorityKind.RECEIPT && id == p.packetId) Authority(peerNode) else null
        }).verify(packet, now())
        require(valid.envelope.destination.kindValue == 2 && valid.envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(key.nodeId.value))) { "Receipt addressed to another phone." }
        // Origin reports keep their delivery status. Relay acknowledgements stop this
        // session's retries; durable delivery history for every neighbor is a later step.
        if (original.owned) dao.markReceived(p.packetId, peerNode, WireCodec.encode(packet))
    }
}
