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
        rows.distinctBy { (if (it.kind == 3) "helper:" else "offer:") + it.entityId }.map { row ->
            val data = envelope(row).payloadBytes
            val helper = if (row.kind == 3) HelperProfilePublishV1.parseFrom(data) else null
            val offer = if (row.kind != 3) offer(row) else null
            val withdrawn = row.kind == 6 && OfferUpdateV1.parseFrom(data).stateValue == 2
            val title = if (helper != null) helper.let {
                (it.displayName.takeIf(String::isNotBlank) ?: "Helper") + " · " + it.helpTypesList.joinToString { type -> type.name.removePrefix("HELP_TYPE_").lowercase().replace('_', ' ') }
            } else offer!!.let {
                it.resourceType.name.removePrefix("RESOURCE_TYPE_").lowercase().replace('_', ' ') +
                    if (it.hasQuantity()) " · ${it.quantity} ${it.unit}" else ""
            }
            val area = helper?.coarseArea ?: offer!!.coarseArea
            val available = !withdrawn && row.expiresAt > time && (helper == null || helper.availabilityValue == 1)
            val editor = when {
                row.owned && helper != null -> ResourceDraft(row.entityId, name = helper.displayName, area = helper.coarseArea,
                    helpType = when (val type = helper.helpTypesValueList.firstOrNull()) {
                        1 -> HelpType.FIRST_AID; 6 -> HelpType.WATER
                        else -> HelpType.entries.getOrElse((type ?: 7) - 1) { HelpType.OTHER_HELP }
                    }, available = available, publicSharing = row.publicSharing, cloudConsent = row.cloudConsent, baseEventId = row.eventId)
                row.owned && offer != null && available -> ResourceDraft(row.entityId, supply = true, area = offer.coarseArea,
                    resourceType = ResourceType.entries[offer.resourceTypeValue - 1],
                    quantity = if (offer.hasQuantity()) offer.quantity.toString() else "", unit = offer.unit,
                    publicSharing = row.publicSharing, cloudConsent = row.cloudConsent, editing = true, baseEventId = row.eventId)
                else -> null
            }
            val withdrawal = if (row.owned && helper != null && (!row.publicSharing || !row.cloudConsent)) {
                val shared = rows.filter { it.entityId == row.entityId && it.publicSharing }
                listOfNotNull(shared.firstOrNull(), shared.firstOrNull { it.cloudConsent })
                    .filter { it.eventId != row.eventId && it.expiresAt > time &&
                        HelperProfilePublishV1.parseFrom(envelope(it).payloadBytes).availabilityValue == 2 }
                    .maxByOrNull { it.cloudConsent }
            } else null
            ResourceSummary(row.entityId, row.owner, row.owned, row.kind == 3, title, area.takeIf(String::isNotBlank), available, row.expiresAt,
                cloudState(row, time), withdrawn, editor, withdrawal?.let { cloudState(it, time) })
        }
    }.flowOn(Dispatchers.IO)

    override fun observeOffers(requestId: RequestId?): Flow<List<OfferSummary>> = timedRows().map { (rows, time) ->
        rows.filter { it.kind in setOf(5, 6) }.distinctBy { it.entityId }.map { row ->
            val p = offer(row)
            val withdrawn = row.kind == 6 && OfferUpdateV1.parseFrom(envelope(row).payloadBytes).stateValue == 2
            OfferSummary(OfferId(row.entityId), NodeId(row.owner), ResourceType.entries[p.resourceTypeValue - 1],
                if (p.hasQuantity()) p.quantity else null, p.unit.takeIf(String::isNotBlank), p.coarseArea.takeIf(String::isNotBlank),
                row.expiresAt, if (withdrawn) OfferState.WITHDRAWN else if (row.expiresAt <= time) OfferState.EXPIRED else OfferState.AVAILABLE, row.createdAt, row.expiresAt <= time)
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
            if (draft.editing) return updateOffer(draft)
            publishOffer(OfferPublishPayload(OfferId(draft.id), owner, draft.resourceType, draft.quantity.toIntOrNull(),
                draft.unit.trim().takeIf(String::isNotBlank), draft.area.trim().takeIf(String::isNotBlank), consent = consent))
        }
    }
    suspend fun updateOffer(draft: ResourceDraft, withdraw: Boolean = false): Result<LocalPublishResult> = publishResult {
        db.withTransaction {
            val row = dao.entity(draft.id) ?: error("Offer not found.")
            val key = identity()
            require(row.kind in setOf(5, 6) && row.owned && row.owner == key.nodeId.value) { "Only the owner can change this offer." }
            require(row.eventId == draft.baseEventId) { "This offer changed. Reopen it before saving." }
            require(draft.publicSharing == row.publicSharing && draft.cloudConsent == row.cloudConsent) { "Sharing choices stay the same when editing an offer." }
            require(row.expiresAt > now() && (row.kind != 6 || OfferUpdateV1.parseFrom(envelope(row).payloadBytes).stateValue != 2)) { "This offer is withdrawn or expired." }
            require(withdraw || draft.quantity.isBlank() || draft.quantity.toIntOrNull() in 1..1000000) { "Enter a positive quantity up to 1,000,000." }
            require(draft.unit.length <= 40 && draft.area.length <= 120)
            val original = original(row)
            val base = OfferPublishV1.parseFrom(original.first.payloadBytes)
            require(draft.resourceType.ordinal + 1 == base.resourceTypeValue) { "Create a new offer to change its type." }
            val current = offer(row)
            val timestamp = maxOf(now(), row.createdAt + 1)
            require(timestamp < row.expiresAt) { "This offer has expired." }
            val p = OfferUpdateV1.newBuilder().setOfferId(row.entityId).setOwnerNodeId(base.ownerNodeId)
                .setStateValue(if (withdraw) 2 else 1).setUnit(if (withdraw) current.unit else draft.unit.trim())
                .setCoarseArea(if (withdraw) current.coarseArea else draft.area.trim()).setAvailableUntilMs(row.expiresAt)
                .setConsent(base.consent).setOriginalOfferEnvelope(original.third)
                .setOriginalOfferSignature(original.second)
                .apply { if (withdraw && current.hasQuantity()) quantity = current.quantity
                    else if (!withdraw) draft.quantity.toIntOrNull()?.let { quantity = it } }.build()
            val packet = SignedPackets.create(key, 6, p.toByteString(), timestamp, ttl = row.expiresAt - timestamp)
            if (row.publicSharing) PacketValidator().verify(packet, now())
            val e = parse(packet)
            dao.prune(now()); dao.pruneOwned(now()); require(dao.count() < 1000) { "Saved help queue is full." }
            dao.insert(ResourceRow(e.eventId, e.packetId, row.entityId, 6, row.owner, true, row.publicSharing,
                row.cloudConsent, timestamp, row.expiresAt, WireCodec.encode(packet)))
            LocalPublishResult(e.eventId, row.publicSharing)
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
        db.withTransaction {
            val previous = dao.sharedHelpers(payload.ownerNodeId.value).filter {
                it.expiresAt > now() && HelperProfilePublishV1.parseFrom(envelope(it).payloadBytes).availabilityValue == 1 &&
                    (!payload.consent.publicMeshAllowed || (it.cloudConsent && !payload.consent.cloudProcessingAllowed))
            }
            if (previous.isNotEmpty()) {
                // Withdraw only previously shared availability, never the new private name/area.
                // Reuse the prior audience so an online withdrawal can still reach a gateway.
                val sharing = Consent(true, previous.any { it.cloudConsent })
                val withdrawal = HelperProfilePublishV1.newBuilder().setOwnerNodeId(p.ownerNodeId)
                    .addHelpTypesValue(7).setAvailabilityValue(2).setConsent(consent(sharing)).build()
                save(3, payload.ownerNodeId.value, withdrawal.toByteString(), sharing, previous.maxOf { it.expiresAt })
            }
            save(3, payload.ownerNodeId.value, p.toByteString(), payload.consent)
        }
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

    suspend fun outgoing(peer: String, excluded: Set<String>, supportsOfferUpdates: Boolean = true): List<ResourceRow> = withContext(Dispatchers.IO) {
        dao.outgoing(now(), peer).asSequence().filterNot { it.packetId in excluded }
            .filter { it.kind != 6 || supportsOfferUpdates }
            .mapNotNull { prepare(it, peer) }.take(100).toList()
    }
    suspend fun prepare(packetId: String, peer: String): ResourceRow? = withContext(Dispatchers.IO) {
        if (dao.delivery(packetId, peer) != null) null else dao.packet(packetId)?.let {
            if (!isCurrentShared(it)) null else prepare(it, peer)
        }
    }
    suspend fun isCurrentShared(row: ResourceRow): Boolean = if (row.kind == 3)
        dao.sharedHelpers(row.entityId).any { it.eventId == row.eventId }
        else dao.entity(row.entityId)?.eventId == row.eventId
    private fun prepare(row: ResourceRow, peer: String): ResourceRow? {
        val p = WireCodec.decode(row.wire)
        val local = identity().nodeId
        if (!row.publicSharing || row.expiresAt <= now() || row.owner == peer || peer == local.value || p.relay.remainingHops == 0 || p.relay.previousNodeId?.value == peer) return null
        return row.copy(wire = WireCodec.encode(p.copy(relay = RelayMetadata(p.relay.remainingHops - 1, p.relay.hopCount + 1, local))))
    }
    suspend fun receive(packet: WirePacket, peer: String): ByteArray = withContext(Dispatchers.IO) {
        val verified = PacketValidator().verify(packet, now()); val e = verified.envelope
        require(e.kindValue in setOf(3, 5, 6) && packet.relay.hopCount > 0 && packet.relay.previousNodeId?.value == peer)
        val owner = SigningIdentity.hex(e.senderNodeId.toByteArray()); val key = identity()
        val helper = verified.payload as? HelperProfilePublishV1
        val offer = verified.payload as? OfferPublishV1
        val update = verified.payload as? OfferUpdateV1
        require(update == null || !update.originalOfferEnvelope.isEmpty) { "Offer update needs its ownership proof." }
        val base = offer ?: update?.let { OfferPublishV1.parseFrom(ImmutableEnvelopeV1.parseFrom(it.originalOfferEnvelope).payloadBytes) }
        require(helper == null || helper.helpTypesCount > 0)
        require(offer == null || !offer.hasQuantity() || offer.quantity > 0)
        val entity = helper?.let { owner } ?: base!!.offerId
        val shared = helper?.consent ?: base!!.consent
        val expires = minOf(e.expiresAtMs, base?.takeIf { it.hasAvailableUntilMs() }?.availableUntilMs ?: e.expiresAtMs)
        require(expires > now())
        val receipt = WireCodec.encode(SignedPackets.create(key, 12, DeliveryReceiptV1.newBuilder().setPacketId(e.packetId)
            .setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(key.nodeId.value))).setReceiptTypeValue(1).setObservedAtMs(now()).build().toByteString(), now(), destination = peer))
        db.withTransaction {
            require(db.closures().event(e.eventId) == null && db.closures().packet(e.packetId) == null)
            require(db.sos().event(e.eventId) == null && db.sos().packet(e.packetId) == null && db.sos().cloudReturn(e.packetId) == null && db.sos().cloudReturnEvent(e.eventId) == null)
            val old = dao.event(e.eventId) ?: dao.packet(e.packetId)
            if (old != null) require(old.publicSharing && WireCodec.decode(old.wire).immutableEnvelopeBytesBase64 == packet.immutableEnvelopeBytesBase64) { "Conflicting help publication." }
            else {
                require(owner != key.nodeId.value && peer != key.nodeId.value)
                if (base != null) dao.entity(entity)?.let { known ->
                    require(known.kind in setOf(5, 6) && known.owner == owner && original(known).third ==
                        (update?.originalOfferEnvelope ?: ByteString.copyFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)))) { "Conflicting offer ownership." }
                }
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
    private fun original(row: ResourceRow): Triple<ImmutableEnvelopeV1, ByteString, ByteString> {
        val packet = WireCodec.decode(row.wire)
        val e = parse(packet)
        return if (row.kind == 5) Triple(e, ByteString.copyFrom(WireCodec.unbase64(packet.signature, 72)),
            ByteString.copyFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)))
        else OfferUpdateV1.parseFrom(e.payloadBytes).let { Triple(ImmutableEnvelopeV1.parseFrom(it.originalOfferEnvelope), it.originalOfferSignature, it.originalOfferEnvelope) }
    }
    private fun offer(row: ResourceRow): OfferPublishV1 {
        val base = OfferPublishV1.parseFrom(original(row).first.payloadBytes)
        if (row.kind == 5) return base
        val update = OfferUpdateV1.parseFrom(envelope(row).payloadBytes)
        return base.toBuilder().clearQuantity().apply { if (update.hasQuantity()) quantity = update.quantity }
            .setUnit(update.unit).setCoarseArea(update.coarseArea).setAvailableUntilMs(update.availableUntilMs).build()
    }
    private fun envelope(row: ResourceRow) = parse(WireCodec.decode(row.wire))
    private fun cloudState(row: ResourceRow, time: Long) = when {
        row.cloudAck != null -> LocalCloudState.ACCEPTED
        !row.publicSharing || !row.cloudConsent -> LocalCloudState.NOT_SHARED
        row.expiresAt <= time -> LocalCloudState.EXPIRED
        row.cloudError != null -> LocalCloudState.REJECTED
        else -> LocalCloudState.QUEUED
    }
    private fun parse(packet: WirePacket) = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
    companion object { const val LIFETIME = 21600000L }
}
