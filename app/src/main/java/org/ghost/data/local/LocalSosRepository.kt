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
    val assistance = LocalAssistanceRepository(db, identity, now)
    val resources = LocalResourceRepository(db, identity, now)
    val closures = LocalSosClosureRepository(db, identity, now)
    private val writes = Mutex()
    private val json = Json
    fun observeDraft(): Flow<SosDraft?> = dao.observeDraft().map { it?.let { row -> json.decodeFromString<SosDraft>(row.value).withRequiredSharing() } }
    suspend fun initializeDraft() = withContext(Dispatchers.IO) {
        db.withTransaction {
            val draft = dao.draft()?.let { json.decodeFromString<SosDraft>(it.value) } ?: SosDraft()
            dao.saveDraft(DraftRow(value = json.encodeToString(draft.withRequiredSharing())))
        }
    }
    suspend fun saveDraft(draft: SosDraft) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = dao.draft()?.let { json.decodeFromString<SosDraft>(it.value) }
            // Ignore late form updates after successful publication replaced this draft.
            if (current == null || current.requestId == draft.requestId) dao.saveDraft(DraftRow(value = json.encodeToString(draft.withRequiredSharing())))
        }
    }
    override fun observeRequests(): Flow<List<DashboardSosSummary>> = combine(dao.observe(), closures.observe(), flow {
        while (true) { emit(now()); delay(30000) }
    }) { rows, closing, timestamp ->
        rows.map { status ->
            val row = status.report
            val closure = closing.firstOrNull { it.requestId == row.requestId }
            val relay = closure?.let { WireCodec.decode(it.wire).relay } ?: relayMetadata(row)
            val expired = row.expiresAt <= timestamp
            DashboardSosSummary(RequestId(row.requestId), SosCategory.valueOf(row.category), closure?.let { SosState.entries[it.state - 1] } ?: SosState.OPEN,
                row.description, row.people, row.area, NodeId(row.sender), row.createdAt, row.receivedAt, expired,
                when {
                    expired -> LocalDelivery.EXPIRED
                    closure != null -> if (!closure.publicSharing) LocalDelivery.SAVED_LOCALLY else if (closure.owned) LocalDelivery.QUEUED else LocalDelivery.RECEIVED_FROM_PEER
                    !row.owned && status.acknowledgedNeighbors > 0 -> LocalDelivery.RELAYED
                    !row.owned && relay.remainingHops == 0 -> LocalDelivery.RELAY_LIMIT_REACHED
                    !row.owned -> LocalDelivery.RECEIVED_FROM_PEER
                    !row.publicSharing -> LocalDelivery.SAVED_LOCALLY
                    row.receivedBy != null -> LocalDelivery.RECEIVED_BY_PHONE
                    row.sentAt != null -> LocalDelivery.SENT_TO_NEIGHBOR
                    else -> LocalDelivery.QUEUED
                }, row.owned, relay.hopCount, relay.remainingHops, if (closure != null) 0 else status.acknowledgedNeighbors,
                when {
                    closure != null -> when {
                        closure.cloudAck != null -> LocalCloudState.ACCEPTED
                        !closure.publicSharing || !closure.cloudConsent -> LocalCloudState.NOT_SHARED
                        expired -> LocalCloudState.EXPIRED
                        closure.cloudError != null -> LocalCloudState.REJECTED
                        else -> LocalCloudState.QUEUED
                    }
                    row.cloudAck != null -> LocalCloudState.ACCEPTED
                    !row.publicSharing || !row.cloudConsent -> LocalCloudState.NOT_SHARED
                    expired -> LocalCloudState.EXPIRED
                    row.cloudError != null -> LocalCloudState.REJECTED
                    else -> LocalCloudState.QUEUED
                }, closure?.publicSharing == true)
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult> = publish(payload, null)

    suspend fun publish(payload: SosCreatePayload, locationSharing: Boolean?): Result<LocalPublishResult> = withContext(Dispatchers.IO) {
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
                            SosDraft(locationSharing = locationSharing ?: draft.locationSharing))))
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
    suspend fun activePacketIds(): Set<String> = withContext(Dispatchers.IO) { (dao.activePacketIds(now()) + db.resources().active(now()) + db.assistance().observe().first().filter { it.expiresAt>now() }.map {it.packetId}).toSet() }

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

    suspend fun cloudOutgoing(): List<UploadCandidate> = withContext(Dispatchers.IO) {
        db.assistance().uploads(now()).map { UploadCandidate(it.eventId,it.packetId,it.wire) } +
        db.closures().uploads(now()).map { UploadCandidate(it.eventId, it.packetId, it.wire) } +
            dao.cloudOutgoing(now()).map { UploadCandidate(it.eventId, it.packetId, it.wire) } +
            db.resources().uploads(now()).map { UploadCandidate(it.eventId, it.packetId, it.wire) }
    }
    suspend fun prepareCloudUpload(eventId: String): UploadCandidate? = withContext(Dispatchers.IO) {
        db.assistance().event(eventId)?.takeIf{it.kind in 17..18 && it.cloudAck==null && it.cloudError==null && it.expiresAt>now()}
            ?.let{UploadCandidate(it.eventId,it.packetId,it.wire)} ?: db.closures().event(eventId)?.takeIf { it.publicSharing && it.cloudConsent && it.cloudAck == null && it.cloudError == null && it.expiresAt > now() }
            ?.let { UploadCandidate(it.eventId, it.packetId, it.wire) }
            ?: dao.event(eventId)?.takeIf { it.publicSharing && it.cloudConsent && it.cloudAck == null && it.cloudError == null && it.expiresAt > now() && db.closures().latest(it.requestId) == null }
            ?.let { UploadCandidate(it.eventId, it.packetId, it.wire) }
            ?: db.resources().event(eventId)?.takeIf { it.publicSharing && it.cloudConsent && it.cloudAck == null && it.cloudError == null && it.expiresAt > now() &&
                resources.isCurrentShared(it) }
                ?.let { UploadCandidate(it.eventId, it.packetId, it.wire) }
    }
    suspend fun retryCloudUploads() = withContext(Dispatchers.IO) { db.assistance().retry(now()); dao.retryCloudUploads(now()); db.resources().retry(now()); db.closures().retry(now()) }
    suspend fun rejectCloudUpload(eventId: String, code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[A-Z_]{1,64}")))
        db.assistance().reject(eventId,code)
        dao.rejectCloudUpload(eventId, code)
        db.resources().reject(eventId, code)
        db.closures().reject(eventId, code)
    }
    /** HTTP status is not proof: only a pinned signature for this exact report is saved. */
    suspend fun acceptCloudAck(eventId: String, packet: WirePacket, cloudKey: ByteArray, expectedRevision: Long): Long = withContext(Dispatchers.IO) {
        val verified = PacketValidator(trustedCloudPublicKey = cloudKey).verify(packet, now())
        require(verified.envelope.kindValue == 7)
        val ack = verified.payload as CloudAckV1
        require(ack.originEventId == eventId && ack.statusValue in 1..2 && ack.cloudRevision == expectedRevision)
        db.withTransaction {
            val row = dao.event(eventId)
            val resource = db.resources().event(eventId)
            val closure = db.closures().event(eventId)
            val decision = db.assistance().event(eventId)?.takeIf{it.kind in 17..18}
            require(row != null || resource != null || closure != null || decision!=null) { "Unknown report" }
            require(if (row != null) row.publicSharing && row.cloudConsent else if (resource != null) resource.publicSharing && resource.cloudConsent else if(closure!=null) closure.publicSharing && closure.cloudConsent else true)
            require(verified.envelope.destination.kindValue == 2 &&
                verified.envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(row?.sender ?: resource?.owner ?: closure?.sender ?: SigningIdentity.hex(org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(decision!!.wire).immutableEnvelopeBytesBase64,PacketValidator.MAX_ENVELOPE)).senderNodeId.toByteArray()))))
            storeCloudReturn(verified)
        }
        ack.cloudRevision
    }

    /** Seed the new return queue from acknowledgements saved by earlier app versions. */
    suspend fun restoreCloudReturns(cloudKey: ByteArray) = withContext(Dispatchers.IO) {
        for (ack in dao.storedCloudAcks().mapNotNull { it.cloudAck } + db.resources().storedAcks().mapNotNull { it.cloudAck } + db.closures().storedAcks().mapNotNull { it.cloudAck }) {
            try {
                val verified = PacketValidator(trustedCloudPublicKey = cloudKey).verify(WireCodec.decode(ack), now())
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
        require(db.assistance().packet(e.packetId)==null && db.assistance().event(e.eventId)==null)
        val ack = verified.payload as CloudAckV1
        require(ack.statusValue in 1..2)
        val destination = SigningIdentity.hex(e.destination.nodeId.toByteArray())
        val report = dao.event(ack.originEventId)
        val resource = db.resources().event(ack.originEventId)
        val closure = db.closures().event(ack.originEventId)
        if (report != null) require(report.publicSharing && report.cloudConsent && report.sender == destination)
        if (resource != null) require(resource.publicSharing && resource.cloudConsent && resource.owner == destination)
        if (closure != null) require(closure.publicSharing && closure.cloudConsent && closure.sender == destination)
        require(db.closures().packet(e.packetId) == null && db.closures().event(e.eventId) == null)
        require(dao.packet(e.packetId) == null && dao.event(e.eventId) == null && db.resources().packet(e.packetId) == null && db.resources().event(e.eventId) == null) { "Conflicting packet identifier." }
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
        if (resource != null) db.resources().acknowledge(ack.originEventId, stored.wire)
        if (closure != null) db.closures().acknowledge(ack.originEventId, stored.wire)
        db.assistance().event(ack.originEventId)?.let { require(it.kind in 17..18); val original=org.ghost.contracts.v1.ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(it.wire).immutableEnvelopeBytesBase64,PacketValidator.MAX_ENVELOPE));require(SigningIdentity.hex(original.senderNodeId.toByteArray())==destination);db.assistance().acknowledge(ack.originEventId,stored.wire) }
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
            require(db.assistance().packet(result.envelope.packetId)==null && db.assistance().event(result.envelope.eventId)==null)
            require(db.resources().packet(result.envelope.packetId) == null && db.resources().event(result.envelope.eventId) == null)
            require(db.closures().packet(result.envelope.packetId) == null && db.closures().event(result.envelope.eventId) == null)
            require(dao.cloudReturn(result.envelope.packetId) == null && dao.cloudReturnEvent(result.envelope.eventId) == null) { "Conflicting packet identifier." }
            val existing = dao.event(result.envelope.eventId) ?: dao.packet(result.envelope.packetId) ?: dao.request(p.requestId)
            if (existing != null) {
                val stored = WireCodec.decode(existing.wire)
                require(existing.publicSharing && stored.immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting report identifier." }
                if (!existing.owned && receivedPacket.relay.hopCount < relayMetadata(existing).hopCount) {
                    // Keep the origin bytes/signature and delivery history; improve only the route.
                    dao.updateRelay(existing.packetId, WireCodec.encode(stored.copy(relay = receivedPacket.relay)))
                }
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
        val resource = db.resources().packet(p.packetId)
        val closure = db.closures().packet(p.packetId)
        val coordination = db.assistance().packet(p.packetId)
        val key = identity()
        require((coordination!=null || original?.publicSharing == true || returned != null || resource?.publicSharing == true || closure?.publicSharing == true) && p.packetId in sentPacketIds && p.receiptTypeValue == 1) { "Receipt does not match a packet sent to this peer." }
        val valid = PacketValidator(lookup = AuthorityLookup { kind, id, _ ->
            if (kind == AuthorityKind.RECEIPT && id == p.packetId) Authority(peerNode) else null
        }).verify(packet, now())
        require(valid.envelope.destination.kindValue == 2 && valid.envelope.destination.nodeId.toByteArray().contentEquals(WireCodec.bytes(key.nodeId.value))) { "Receipt addressed to another phone." }
        db.withTransaction {
            val bytes = WireCodec.encode(packet)
            if(coordination!=null) { db.assistance().delivered(AssistanceDelivery(p.packetId,peerNode,bytes)) } else if (returned != null) {
                val delivery = dao.returnDelivery(p.packetId, peerNode) ?: CloudReturnDeliveryRow(p.packetId, peerNode)
                dao.saveReturnDelivery(delivery.copy(receiptFromPeer = bytes))
            } else if (closure != null) {
                val delivery = db.closures().delivery(p.packetId, peerNode) ?: SosClosureDeliveryRow(p.packetId, peerNode)
                db.closures().saveDelivery(delivery.copy(receiptFromPeer = bytes))
            } else if (resource != null) {
                val delivery = db.resources().delivery(p.packetId, peerNode) ?: ResourceDeliveryRow(p.packetId, peerNode)
                db.resources().saveDelivery(delivery.copy(receiptFromPeer = bytes))
            } else {
                val delivery = dao.delivery(p.packetId, peerNode) ?: NeighborDeliveryRow(p.packetId, peerNode)
                dao.saveDelivery(delivery.copy(receiptFromPeer = bytes))
                if (original!!.owned) dao.markReceived(p.packetId, peerNode, bytes)
            }
        }
    }
}
