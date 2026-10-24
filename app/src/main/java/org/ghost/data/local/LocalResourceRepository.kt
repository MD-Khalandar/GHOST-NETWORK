package org.ghost.data.local

import androidx.room.withTransaction
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.domain.repository.*
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity

class LocalResourceRepository(private val db: GhostDatabase, private val identity: () -> SigningIdentity,
                              private val now: () -> Long) : HelperOfferRepository {
    private val dao = db.resources()
    fun observeRows() = dao.observe()
    private fun timedRows() = combine(dao.observe(), flow { while (true) { emit(now()); delay(30000) } }) { rows, time -> rows to time }
    fun observeSummaries(): Flow<List<ResourceSummary>> = timedRows().map { (rows, time) ->
        rows.distinctBy { it.kind to it.entityId }.map { row ->
            val data = envelope(row).payloadBytes
            val title = if (row.kind == 3) HelperProfilePublishV1.parseFrom(data).let {
                (it.displayName.takeIf(String::isNotBlank) ?: "Helper") + " · " + it.helpTypesList.joinToString { type -> type.name.removePrefix("HELP_TYPE_").lowercase().replace('_', ' ') }
            } else OfferPublishV1.parseFrom(data).let {
                it.resourceType.name.removePrefix("RESOURCE_TYPE_").lowercase().replace('_', ' ') +
                    if (it.hasQuantity()) " · ${it.quantity} ${it.unit}" else ""
            }
            val area = if (row.kind == 3) HelperProfilePublishV1.parseFrom(data).coarseArea else OfferPublishV1.parseFrom(data).coarseArea
            val available = row.expiresAt > time && (row.kind != 3 || HelperProfilePublishV1.parseFrom(data).availabilityValue == 1)
            ResourceSummary(row.entityId, row.owner, row.owned, row.kind == 3, title, area.takeIf(String::isNotBlank), available, row.expiresAt,
                when { row.cloudAck != null -> LocalCloudState.ACCEPTED
                    !row.publicSharing || !row.cloudConsent -> LocalCloudState.NOT_SHARED
                    row.expiresAt <= time -> LocalCloudState.EXPIRED
                    row.cloudError != null -> LocalCloudState.REJECTED
                    else -> LocalCloudState.QUEUED })
        }
    }.flowOn(Dispatchers.IO)

    override fun observeOffers(requestId: RequestId?): Flow<List<OfferSummary>> = timedRows().map { (rows, time) ->
        rows.filter { it.kind == 5 }.distinctBy { it.entityId }.map { row ->
            val p = OfferPublishV1.parseFrom(envelope(row).payloadBytes)
            OfferSummary(OfferId(row.entityId), NodeId(row.owner), ResourceType.entries[p.resourceTypeValue - 1],
                if (p.hasQuantity()) p.quantity else null, p.unit.takeIf(String::isNotBlank), p.coarseArea.takeIf(String::isNotBlank),
                row.expiresAt, if (row.expiresAt <= time) OfferState.EXPIRED else OfferState.AVAILABLE, row.createdAt, row.expiresAt <= time)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun publish(draft: ResourceDraft): Result<LocalPublishResult> {
        val owner = withContext(Dispatchers.IO) { identity().nodeId }
        val consent = Consent(draft.publicSharing, draft.cloudConsent)
        return if (!draft.supply) publishHelper(HelperProfilePublishPayload(owner, draft.name.trim().takeIf(String::isNotBlank),
            draft.area.trim().takeIf(String::isNotBlank), listOf(draft.helpType),
            if (draft.available) HelperAvailability.AVAILABLE else HelperAvailability.UNAVAILABLE, consent))
        else {
            if (draft.quantity.isNotBlank() && draft.quantity.toIntOrNull() !in 1..1000000)
                return Result.failure(IllegalArgumentException("Enter a quantity between 1 and 1,000,000."))
            publishOffer(OfferPublishPayload(OfferId(draft.id), owner, draft.resourceType, draft.quantity.toIntOrNull(),
                draft.unit.trim().takeIf(String::isNotBlank), draft.area.trim().takeIf(String::isNotBlank), consent = consent))
        }
    }
    override suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult> = publishResult {
        require(payload.ownerNodeId == identity().nodeId)
        require(payload.helpTypes.isNotEmpty() && payload.helpTypes.size <= 10) { "Choose what help you can provide." }
        require((payload.displayName?.length ?: 0) <= 80 && (payload.coarseArea?.length ?: 0) <= 120) { "Keep the name and area short." }
        val p = HelperProfilePublishV1.newBuilder().setOwnerNodeId(ByteString.copyFrom(WireCodec.bytes(payload.ownerNodeId.value)))
            .addAllHelpTypesValue(payload.helpTypes.distinct().map { when (it) { HelpType.FIRST_AID -> 1; HelpType.WATER -> 6; else -> it.ordinal + 1 } })
            .setAvailabilityValue(payload.availability.ordinal + 1).setConsent(consent(payload.consent))
            .apply { payload.displayName?.let { displayName = it }; payload.coarseArea?.let { coarseArea = it } }.build()
        save(3, payload.ownerNodeId.value, p.toByteString(), payload.consent)
    }
    override suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult> = publishResult {
        require(payload.ownerNodeId == identity().nodeId)
        require(payload.quantity == null || payload.quantity in 1..1000000) { "Enter a positive quantity." }
        require((payload.unit?.length ?: 0) <= 40 && (payload.coarseArea?.length ?: 0) <= 120) { "Keep the unit and area short." }
        require(payload.offerId.value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        val until = minOf(payload.availableUntilMs ?: dao.entity(payload.offerId.value)?.expiresAt ?: (now() + LIFETIME), now() + LIFETIME)
        require(until > now()) { "Availability has already ended." }
        val p = OfferPublishV1.newBuilder().setOfferId(payload.offerId.value).setOwnerNodeId(ByteString.copyFrom(WireCodec.bytes(payload.ownerNodeId.value)))
            .setResourceTypeValue(payload.resourceType.ordinal + 1).setAvailableUntilMs(until).setConsent(consent(payload.consent))
            .apply { payload.quantity?.let { quantity = it }; payload.unit?.let { unit = it }; payload.coarseArea?.let { coarseArea = it } }.build()
        save(5, payload.offerId.value, p.toByteString(), payload.consent, until)
    }
    private suspend fun publishResult(work: suspend () -> LocalPublishResult): Result<LocalPublishResult> = withContext(Dispatchers.IO) {
        try { Result.success(work()) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    private fun consent(value: Consent) = ConsentV1.newBuilder().setPublicMeshAllowed(value.publicMeshAllowed).setCloudProcessingAllowed(value.cloudProcessingAllowed).build()
    private suspend fun save(kind: Int, entity: String, data: ByteString, consent: Consent, until: Long = now() + LIFETIME): LocalPublishResult = db.withTransaction {
        val key = identity()
        val existing = dao.entity(entity)
        if (kind == 5 && existing != null) {
            require(existing.owned && existing.owner == key.nodeId.value && envelope(existing).payloadBytes == data) { "This offer was already saved. Create a new offer for different supplies." }
            return@withTransaction LocalPublishResult(existing.eventId, existing.publicSharing)
        }
        // Monotonic owner publications prevent a delayed profile from replacing a newer one.
        val time = maxOf(now(), (existing?.createdAt ?: 0) + 1)
        val packet = SignedPackets.create(key, kind, data, time, ttl = (until - time).coerceAtLeast(1))
        if (consent.publicMeshAllowed) PacketValidator().verify(packet, now())
        val e = parse(packet)
        dao.prune(now()); dao.pruneOwned(now())
        require(dao.count() < 1000) { "Saved help queue is full. Wait for old reports to expire." }
        require(db.sos().event(e.eventId) == null && db.sos().packet(e.packetId) == null && db.sos().cloudReturn(e.packetId) == null)
        dao.insert(ResourceRow(e.eventId, e.packetId, entity, kind, key.nodeId.value, true, consent.publicMeshAllowed,
            consent.cloudProcessingAllowed, e.createdAtMs, e.expiresAtMs, WireCodec.encode(packet)))
        LocalPublishResult(e.eventId, consent.publicMeshAllowed)
    }

    suspend fun outgoing(peer: String, excluded: Set<String>): List<ResourceRow> = withContext(Dispatchers.IO) {
        dao.outgoing(now(), peer).filterNot { it.packetId in excluded }.mapNotNull { prepare(it, peer) }
    }
    suspend fun prepare(packetId: String, peer: String): ResourceRow? = withContext(Dispatchers.IO) {
        if (dao.delivery(packetId, peer) != null) null else dao.packet(packetId)?.let { prepare(it, peer) }
    }
    private fun prepare(row: ResourceRow, peer: String): ResourceRow? {
        val p = WireCodec.decode(row.wire)
        val local = identity().nodeId
        if (!row.publicSharing || row.expiresAt <= now() || row.owner == peer || peer == local.value || p.relay.remainingHops == 0 || p.relay.previousNodeId?.value == peer) return null
        return row.copy(wire = WireCodec.encode(p.copy(relay = RelayMetadata(p.relay.remainingHops - 1, p.relay.hopCount + 1, local))))
    }
    suspend fun receive(packet: WirePacket, peer: String): ByteArray = withContext(Dispatchers.IO) {
        val verified = PacketValidator().verify(packet, now()); val e = verified.envelope
        require(e.kindValue in setOf(3, 5) && packet.relay.hopCount > 0 && packet.relay.previousNodeId?.value == peer)
        val owner = SigningIdentity.hex(e.senderNodeId.toByteArray()); val key = identity()
        val helper = verified.payload as? HelperProfilePublishV1
        val offer = verified.payload as? OfferPublishV1
        require(helper == null || helper.helpTypesCount > 0)
        require(offer == null || !offer.hasQuantity() || offer.quantity > 0)
        val entity = helper?.let { owner } ?: offer!!.offerId
        val shared = helper?.consent ?: offer!!.consent
        val expires = minOf(e.expiresAtMs, offer?.takeIf { it.hasAvailableUntilMs() }?.availableUntilMs ?: e.expiresAtMs)
        require(expires > now())
        val receipt = WireCodec.encode(SignedPackets.create(key, 12, DeliveryReceiptV1.newBuilder().setPacketId(e.packetId)
            .setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(key.nodeId.value))).setReceiptTypeValue(1).setObservedAtMs(now()).build().toByteString(), now(), destination = peer))
        db.withTransaction {
            require(db.sos().event(e.eventId) == null && db.sos().packet(e.packetId) == null && db.sos().cloudReturn(e.packetId) == null && db.sos().cloudReturnEvent(e.eventId) == null)
            val old = dao.event(e.eventId) ?: dao.packet(e.packetId) ?: if (offer != null) dao.entity(entity) else null
            if (old != null) require(old.publicSharing && WireCodec.decode(old.wire).immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting help publication." }
            else {
                require(owner != key.nodeId.value && peer != key.nodeId.value)
                dao.prune(now()); require(dao.count() < 1000) { "Saved help queue is full." }
                dao.insert(ResourceRow(e.eventId, e.packetId, entity, e.kindValue, owner, false, true, shared.cloudProcessingAllowed,
                    e.createdAtMs, expires, WireCodec.encode(packet)))
            }
            val delivery = dao.delivery(e.packetId, peer) ?: ResourceDeliveryRow(e.packetId, peer)
            val cached = delivery.receiptToPeer?.takeIf { parse(WireCodec.decode(it)).expiresAtMs > now() }
            val response = cached ?: receipt
            dao.saveDelivery(delivery.copy(receiptToPeer = response)); response
        }
    }
    private fun envelope(row: ResourceRow) = parse(WireCodec.decode(row.wire))
    private fun parse(packet: WirePacket) = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
    companion object { const val LIFETIME = 21600000L }
}
