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
import org.ghost.contracts.v1.CloudAckV1
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
        db.withTransaction { if (dao.draft() == null) dao.saveDraft(DraftRow(value = json.encodeToString(SosDraft(publicSharing = true)))) }
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
        rows.map { status ->
            val row = status.report
            val relay = relayMetadata(row)
            val expired = row.expiresAt <= timestamp
            DashboardSosSummary(RequestId(row.requestId), SosCategory.valueOf(row.category), SosState.OPEN,
                row.description, row.people, row.area, NodeId(row.sender), row.createdAt, row.receivedAt, expired,
                when {
                    expired -> LocalDelivery.EXPIRED
                    !row.owned && status.acknowledgedNeighbors > 0 -> LocalDelivery.RELAYED
                    !row.owned && relay.remainingHops == 0 -> LocalDelivery.RELAY_LIMIT_REACHED
                    !row.owned -> LocalDelivery.RECEIVED_FROM_PEER
                    !row.publicSharing -> LocalDelivery.SAVED_LOCALLY
                    row.receivedBy != null -> LocalDelivery.RECEIVED_BY_PHONE
                    row.sentAt != null -> LocalDelivery.SENT_TO_NEIGHBOR
                    else -> LocalDelivery.QUEUED
                }, row.owned, relay.hopCount, relay.remainingHops, status.acknowledgedNeighbors,
                when {
                    row.cloudAck != null -> LocalCloudState.ACCEPTED
                    !row.publicSharing || !row.cloudConsent -> LocalCloudState.NOT_SHARED
                    expired -> LocalCloudState.EXPIRED
                    row.cloudError != null -> LocalCloudState.REJECTED
                    else -> LocalCloudState.QUEUED
                })
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult> = withContext(Dispatchers.IO) {
        try {
            writes.withLock {
                require(payload.description.isNotBlank() && payload.description.length <= 2000) { "Describe the help you need (up to 2000 characters)." }
                require(payload.peopleCount == null || payload.peopleCount in 1..1000) { "People count must be between 1 and 1000." }
                require((payload.coarseArea?.length ?: 0) <= 120 && (payload.landmark?.length ?: 0) <= 120) { "Keep the area short (up to 120 characters)." }
                payload.location?.let {
                    require(it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0) { "Invalid location." }
                }
                require(UUID.fromString(payload.requestId.value).toString() == payload.requestId.value) { "Invalid request identifier." }
                val key = identity()
                val timestamp = now()
                val data = SosCreateV1.newBuilder().setRequestId(payload.requestId.value).setCategoryValue(payload.category.ordinal + 1)
                    .setDescription(payload.description).setConsent(ConsentV1.newBuilder()
                        .setPublicMeshAllowed(payload.consent.publicMeshAllowed).setCloudProcessingAllowed(payload.consent.cloudProcessingAllowed))
                    .apply {
                        payload.peopleCount?.let { peopleCount = it }
                        payload.coarseArea?.let { coarseArea = it }
                        payload.landmark?.let { landmark = it }
                        payload.location?.let {
                            latitude = it.latitude
                            longitude = it.longitude
                        }
                    }.build()
                val packet = SignedPackets.create(key, 1, data.toByteString(), timestamp)
                val verified = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                db.withTransaction {
                    val existing = dao.request(payload.requestId.value)
                    if (existing != null) {
                        val original = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(existing.wire).immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                        require(existing.owned && existing.sender == key.nodeId.value && original.payloadBytes == data.toByteString()) { "This request has already been saved with different content." }
                        LocalPublishResult(existing.eventId, existing.publicSharing)
                    } else {
                        dao.pruneReceived(timestamp)
                        dao.pruneOldOwned(timestamp)
                        require(dao.count() < 1000) { "Local report storage is full. Wait for older reports to expire." }
                        require(dao.activeOwned(timestamp) < 100) { "Local send queue is full. Wait for older reports to expire." }
                        val row = ReportRow(verified.eventId, verified.packetId, payload.requestId.value, key.nodeId.value,
                            payload.category.name, payload.description, payload.peopleCount, payload.coarseArea, timestamp, timestamp,
                            verified.expiresAtMs, true, payload.consent.publicMeshAllowed, payload.consent.cloudProcessingAllowed, WireCodec.encode(packet))
                        check(dao.insert(row) != -1L) { "Request could not be saved." }
                        val draft = dao.draft()?.let { json.decodeFromString<SosDraft>(it.value) }
                        if (draft?.requestId == payload.requestId.value) dao.saveDraft(DraftRow(value = json.encodeToString(
                            SosDraft(publicSharing = payload.consent.publicMeshAllowed, cloudConsent = payload.consent.cloudProcessingAllowed))))
                        LocalPublishResult(verified.eventId, payload.consent.publicMeshAllowed)
                    }
                }.let { Result.success(it) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { Result.failure(error) }
    }

    /** With a neighbor, prepare one transfer without changing the stored signed envelope. */
    suspend fun outgoing(peerNode: String? = null, excludedPacketIds: Set<String> = emptySet()): List<ReportRow> = withContext(Dispatchers.IO) {
        dao.pruneDeliveries(now())
        val rows = dao.outgoing(now(), peerNode).filterNot { it.packetId in excludedPacketIds }
        if (peerNode == null) return@withContext rows
        val localNode = identity().nodeId
        rows.asSequence().mapNotNull { prepare(it, peerNode, localNode) }.take(100).toList()
    }
    suspend fun activePacketIds(): Set<String> = withContext(Dispatchers.IO) { dao.activePacketIds(now()).toSet() }

    suspend fun prepareOutgoing(packetId: String, peerNode: String): ReportRow? = withContext(Dispatchers.IO) {
        dao.outgoingPacket(packetId, peerNode, now())?.let { prepare(it, peerNode, identity().nodeId) }
    }

    private fun relayMetadata(row: ReportRow): RelayMetadata {
        val relay = WireCodec.decode(row.wire).relay
        // Also count the first transfer for reports saved by the old direct-only app.
        return if (!row.owned && relay.hopCount == 0) RelayMetadata(3, 1, NodeId(row.sender)) else relay
    }

    private fun prepare(row: ReportRow, peerNode: String, localNode: NodeId): ReportRow? {
        val relay = relayMetadata(row)
        if (peerNode == localNode.value || row.sender == peerNode || row.receivedBy == peerNode ||
            relay.previousNodeId?.value == peerNode || relay.remainingHops == 0) return null
        return row.copy(wire = WireCodec.encode(WireCodec.decode(row.wire).copy(relay = RelayMetadata(
            relay.remainingHops - 1, relay.hopCount + 1, localNode))))
    }
    suspend fun markSent(packetId: String) = withContext(Dispatchers.IO) { dao.markSent(packetId, now()) }

    suspend fun cloudOutgoing(): List<ReportRow> = withContext(Dispatchers.IO) { dao.cloudOutgoing(now()) }
    suspend fun prepareCloudUpload(eventId: String): ReportRow? = withContext(Dispatchers.IO) {
        dao.event(eventId)?.takeIf { it.publicSharing && it.cloudConsent && it.cloudAck == null && it.cloudError == null && it.expiresAt > now() }
    }
    suspend fun retryCloudUploads() = withContext(Dispatchers.IO) { dao.retryCloudUploads(now()) }
    suspend fun rejectCloudUpload(eventId: String, code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[A-Z_]{1,64}")))
        dao.rejectCloudUpload(eventId, code)
    }
    /** HTTP status is not proof: only a pinned signature for this exact SOS is saved. */
    suspend fun acceptCloudAck(eventId: String, packet: WirePacket, cloudKey: ByteArray, expectedRevision: Long): Long = withContext(Dispatchers.IO) {
        val verified = PacketValidator(trustedCloudPublicKey = cloudKey).verify(packet, now())
        require(verified.envelope.kindValue == 7)
        val ack = verified.payload as CloudAckV1
        require(ack.originEventId == eventId && ack.statusValue in 1..2 && ack.cloudRevision == expectedRevision)
        db.withTransaction {
            val row = dao.event(eventId) ?: error("Unknown SOS")
            require(row.publicSharing && row.cloudConsent)
            require(verified.envelope.destination.kindValue == 2 &&
                verified.envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(row.sender)))
            storeCloudReturn(verified)
        }
        ack.cloudRevision
    }

    /** Seed the new return queue from acknowledgements saved by earlier app versions. */
    suspend fun restoreCloudReturns(cloudKey: ByteArray) = withContext(Dispatchers.IO) {
        for (row in dao.storedCloudAcks()) {
            try {
                val verified = PacketValidator(trustedCloudPublicKey = cloudKey).verify(WireCodec.decode(row.cloudAck!!), now())
                db.withTransaction { storeCloudReturn(verified) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: ProtocolException) { /* Expired or no longer pinned acknowledgements are not relayed. */ }
        }
    }

    fun observeCloudReturns(): Flow<List<String>> = dao.observeReturns(now())

    suspend fun outgoingCloudReturns(peerNode: String, excluded: Set<String> = emptySet()): List<CloudReturnRow> = withContext(Dispatchers.IO) {
        dao.pruneReturns(now())
        dao.outgoingReturns(now(), identity().nodeId.value, peerNode).asSequence()
            .filterNot { it.packetId in excluded }.mapNotNull { prepareReturn(it, peerNode) }.take(20).toList()
    }

    suspend fun prepareCloudReturn(packetId: String, peerNode: String): CloudReturnRow? = withContext(Dispatchers.IO) {
        if (dao.returnDelivery(packetId, peerNode) != null) return@withContext null
        dao.cloudReturn(packetId)?.let { prepareReturn(it, peerNode) }
    }

    private fun prepareReturn(row: CloudReturnRow, peerNode: String): CloudReturnRow? {
        val packet = WireCodec.decode(row.wire)
        val localNode = identity().nodeId
        if (row.expiresAt <= now() || row.destination == localNode.value || peerNode == localNode.value ||
            packet.relay.remainingHops == 0 || packet.relay.previousNodeId?.value == peerNode) return null
        return row.copy(wire = WireCodec.encode(packet.copy(relay = RelayMetadata(
            packet.relay.remainingHops - 1, packet.relay.hopCount + 1, localNode))))
    }

    /** No original SOS is required to carry an authenticated return toward its destination. */
    suspend fun receiveCloudReturn(packet: WirePacket, peerNode: String, cloudKey: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val verified = PacketValidator(trustedCloudPublicKey = cloudKey).verify(packet, now())
        require(packet.relay.hopCount > 0 && packet.relay.previousNodeId?.value == peerNode)
        val local = identity()
        require(peerNode != local.nodeId.value)
        db.withTransaction {
            val stored = storeCloudReturn(verified)
            val delivery = dao.returnDelivery(stored.packetId, peerNode) ?: CloudReturnDeliveryRow(stored.packetId, peerNode)
            val cached = delivery.receiptToPeer?.takeIf {
                org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(it).immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)).expiresAtMs > now()
            }
            val receipt = cached ?: WireCodec.encode(SignedPackets.create(local, 12, DeliveryReceiptV1.newBuilder()
                .setPacketId(stored.packetId).setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(local.nodeId.value)))
                .setReceiptTypeValue(1).setObservedAtMs(now()).build().toByteString(), now(), destination = peerNode))
            dao.saveReturnDelivery(delivery.copy(receiptToPeer = receipt))
            receipt
        }
    }

    /** Called inside the same transaction as the local projection and hop receipt. */
    private suspend fun storeCloudReturn(verified: VerifiedPacket): CloudReturnRow {
        val e = verified.envelope
        require(e.kindValue == 7 && e.destination.kindValue == 2)
        val ack = verified.payload as CloudAckV1
        require(ack.statusValue in 1..2)
        val destination = SigningIdentity.hex(e.destination.nodeId.toByteArray())
        val report = dao.event(ack.originEventId)
        if (report != null) require(report.publicSharing && report.cloudConsent && report.sender == destination)
        require(dao.packet(e.packetId) == null && dao.event(e.eventId) == null) { "Conflicting packet identifier." }
        dao.pruneReturns(now())
        val existing = dao.cloudReturn(e.packetId) ?: dao.cloudReturnEvent(e.eventId)
        val stored = if (existing != null) {
            require(WireCodec.decode(existing.wire).immutableEnvelopeBytesBase64 == verified.packet.immutableEnvelopeBytesBase64) { "Conflicting cloud acknowledgement." }
            existing
        } else {
            require(dao.countReturns() < 1000) { "Return queue full." }
            CloudReturnRow(e.packetId, e.eventId, ack.originEventId, destination, e.expiresAtMs, WireCodec.encode(verified.packet)).also {
                check(dao.insertReturn(it) != -1L)
            }
        }
        if (report != null) dao.saveCloudAck(ack.originEventId, stored.wire)
        return stored
    }

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
            require(dao.cloudReturn(result.envelope.packetId) == null && dao.cloudReturnEvent(result.envelope.eventId) == null) { "Conflicting packet identifier." }
            val existing = dao.event(result.envelope.eventId) ?: dao.packet(result.envelope.packetId) ?: dao.request(p.requestId)
            if (existing != null) {
                require(existing.publicSharing && WireCodec.decode(existing.wire).immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting report identifier." }
            } else {
                require(origin != key.nodeId.value) { "Unknown report signed by this phone." }
                dao.pruneReceived(now())
                require(dao.count() < 1000) { "Local report storage is full." }
                val bytes = WireCodec.encode(receipt)
                check(dao.insert(ReportRow(result.envelope.eventId, result.envelope.packetId, p.requestId, origin,
                    SosCategory.entries[p.categoryValue - 1].name, p.description, if (p.hasPeopleCount()) p.peopleCount else null,
                    if (p.hasCoarseArea()) p.coarseArea else null, result.envelope.createdAtMs, now(), result.envelope.expiresAtMs,
                    false, true, p.consent.cloudProcessingAllowed, WireCodec.encode(receivedPacket), receipt = bytes)) != -1L)
            }
            // Save the report, neighbor knowledge and reply together before acknowledging.
            val delivery = dao.delivery(result.envelope.packetId, peerNode)
                ?: NeighborDeliveryRow(result.envelope.packetId, peerNode)
            val cached = (delivery.receiptToPeer ?: existing?.receipt)?.let { bytes ->
                val e = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(bytes).immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                bytes.takeIf { e.expiresAtMs > now() && e.senderNodeId.toByteArray().contentEquals(WireCodec.bytes(key.nodeId.value)) &&
                    e.destination.kindValue == 2 && e.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(peerNode)) }
            }
            val response = cached ?: WireCodec.encode(receipt)
            dao.saveDelivery(delivery.copy(receiptToPeer = response))
            response
        }
    }

    suspend fun receiveReceipt(packet: WirePacket, peerNode: String, sentPacketIds: Set<String>) = withContext(Dispatchers.IO) {
        val envelope = org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
        require(envelope.kindValue == 12)
        val p = DeliveryReceiptV1.parseFrom(envelope.payloadBytes)
        val original = dao.packet(p.packetId)
        val returned = dao.cloudReturn(p.packetId)
        val key = identity()
        require((original?.publicSharing == true || returned != null) && p.packetId in sentPacketIds && p.receiptTypeValue == 1) { "Receipt does not match a packet sent to this peer." }
        val valid = PacketValidator(lookup = AuthorityLookup { kind, id, _ ->
            if (kind == AuthorityKind.RECEIPT && id == p.packetId) Authority(peerNode) else null
        }).verify(packet, now())
        require(valid.envelope.destination.kindValue == 2 && valid.envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(key.nodeId.value))) { "Receipt addressed to another phone." }
        db.withTransaction {
            val bytes = WireCodec.encode(packet)
            if (returned != null) {
                val delivery = dao.returnDelivery(p.packetId, peerNode) ?: CloudReturnDeliveryRow(p.packetId, peerNode)
                dao.saveReturnDelivery(delivery.copy(receiptFromPeer = bytes))
            } else {
                val delivery = dao.delivery(p.packetId, peerNode) ?: NeighborDeliveryRow(p.packetId, peerNode)
                dao.saveDelivery(delivery.copy(receiptFromPeer = bytes))
                if (original!!.owned) dao.markReceived(p.packetId, peerNode, bytes)
            }
        }
    }
}
