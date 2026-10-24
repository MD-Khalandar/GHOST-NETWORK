package org.ghost.protocol

import com.google.protobuf.MessageLite
import org.ghost.contracts.v1.*
import org.ghost.domain.model.WirePacket
import org.ghost.security.SigningIdentity

enum class Rejection {
    MALFORMED_PACKET, TOO_LARGE, UNSUPPORTED_VERSION, UNSUPPORTED_KIND, INVALID_TIME, EXPIRED,
    INVALID_HOPS, INVALID_KEY, SIGNER_MISMATCH, BAD_SIGNATURE, INVALID_PAYLOAD, INVALID_ACTOR,
    UNKNOWN_AUTHORITY, UNTRUSTED_CLOUD, CONSENT_REQUIRED,
}
class ProtocolException(val code: Rejection) : IllegalArgumentException(code.name)
enum class AuthorityKind { REQUEST, OFFER, HELPER, PROPOSAL, RECEIPT }
data class Authority(val ownerNodeId: String, val cloudProcessingAllowed: Boolean = false)
fun interface AuthorityLookup { fun lookup(kind: AuthorityKind, id: String, version: Int): Authority? }
data class VerifiedPacket(val envelope: ImmutableEnvelopeV1, val payload: MessageLite, val packet: WirePacket)

/** Validation precedes projection/forwarding. Ownership is supplied by verified local/cloud state. */
class PacketValidator(
    trustedCloudPublicKey: ByteArray? = null,
    private val lookup: AuthorityLookup = AuthorityLookup { _, _, _ -> null },
) {
    private val trustedCloudPublicKey = trustedCloudPublicKey?.copyOf()
    init { trustedCloudPublicKey?.let(SigningIdentity::decodePublicKey) }
    fun verify(packet: WirePacket, nowMs: Long, forCloud: Boolean = false): VerifiedPacket = try {
        validate(packet, nowMs, forCloud)
    } catch (error: ProtocolException) { throw error }
      catch (_: Exception) { throw ProtocolException(Rejection.MALFORMED_PACKET) }

    private fun check(condition: Boolean, code: Rejection = Rejection.INVALID_PAYLOAD) {
        if (!condition) throw ProtocolException(code)
    }
    private fun validate(packet: WirePacket, now: Long, forCloud: Boolean): VerifiedPacket {
        val raw = WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, MAX_ENVELOPE)
        val signature = WireCodec.unbase64(packet.signature, 72)
        check(signature.size >= 8, Rejection.BAD_SIGNATURE)
        val r = packet.relay
        check(r.remainingHops in 0..4 && r.hopCount in 0..4 && r.remainingHops + r.hopCount == 4 &&
            (if (r.hopCount == 0) r.previousNodeId == null else r.previousNodeId?.value?.matches(NODE) == true), Rejection.INVALID_HOPS)
        val e = ImmutableEnvelopeV1.parseFrom(raw)
        check(e.version == 1, Rejection.UNSUPPORTED_VERSION)
        check(e.kindValue in 1..12, Rejection.UNSUPPORTED_KIND)
        check(uuid(e.packetId) && uuid(e.eventId) && e.priorityValue in 1..3 && e.payloadFormatValue == 1)
        check(now in 1..MAX_SAFE && e.createdAtMs in 1..MAX_SAFE && e.expiresAtMs in 1..MAX_SAFE &&
            e.expiresAtMs > e.createdAtMs && e.expiresAtMs - e.createdAtMs <= MAX_TTL && e.createdAtMs <= now + CLOCK_SKEW, Rejection.INVALID_TIME)
        check(e.expiresAtMs > now, Rejection.EXPIRED)
        check(e.hasDestination() && (if (e.destination.kindValue == 1) e.destination.nodeId.isEmpty else e.destination.kindValue == 2 && e.destination.nodeId.size() == 32))
        check(!e.payloadBytes.isEmpty && e.payloadBytes.size() <= MAX_PAYLOAD, Rejection.TOO_LARGE)
        val publicKey = e.senderSigningPublicKey.toByteArray()
        try { SigningIdentity.decodePublicKey(publicKey) } catch (_: Exception) { throw ProtocolException(Rejection.INVALID_KEY) }
        val sender = SigningIdentity.hex(e.senderNodeId.toByteArray())
        check(e.senderNodeId.size() == 32 && SigningIdentity.nodeId(publicKey).value == sender, Rejection.SIGNER_MISMATCH)
        check(try { SigningIdentity.verify(publicKey, raw, signature) } catch (_: Exception) { false }, Rejection.BAD_SIGNATURE)
        val cloud = e.kindValue in setOf(7, 8, 11)
        check(!cloud || (!forCloud && trustedCloudPublicKey?.contentEquals(publicKey) == true), Rejection.UNTRUSTED_CLOUD)
        check(if (cloud) e.destination.kindValue == 2 else if (e.kindValue in 1..6 || e.kindValue in 9..10) e.destination.kindValue == 1 else true)
        var authority: Authority? = null
        fun owner(kind: AuthorityKind, id: String, version: Int = 0) {
            authority = lookup.lookup(kind, id, version)
            check(authority != null, Rejection.UNKNOWN_AUTHORITY)
            check(authority?.ownerNodeId == sender, Rejection.INVALID_ACTOR)
        }
        val data = e.payloadBytes
        val payload: MessageLite = when (e.kindValue) {
            1 -> SosCreateV1.parseFrom(data).also { p ->
                check(uuid(p.requestId) && p.categoryValue in 1..6 && text(p.description, 2000, true) &&
                    (!p.hasPeopleCount() || p.peopleCount in 1..1000) && text(p.coarseArea, 120) && text(p.landmark, 120))
                authority = lookup.lookup(AuthorityKind.REQUEST, p.requestId, 0)
                check(authority == null || authority?.ownerNodeId == sender, Rejection.INVALID_ACTOR)
                check(p.consent.publicMeshAllowed, Rejection.CONSENT_REQUIRED)
            }
            2 -> SosUpdateV1.parseFrom(data).also { p ->
                check(uuid(p.requestId) && p.stateValue in 1..4 && text(p.note, 500)); owner(AuthorityKind.REQUEST, p.requestId)
            }
            3 -> HelperProfilePublishV1.parseFrom(data).also { p ->
                check(SigningIdentity.hex(p.ownerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR)
                check(text(p.displayName, 80) && text(p.coarseArea, 120) && p.helpTypesCount <= 10 && p.helpTypesValueList.all { it in 1..7 } && p.availabilityValue in 1..3)
                check(p.consent.publicMeshAllowed, Rejection.CONSENT_REQUIRED)
            }
            4 -> HelperProfileUpdateV1.parseFrom(data).also { p ->
                check(SigningIdentity.hex(p.ownerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR)
                check(text(p.displayName, 80) && text(p.coarseArea, 120) && p.helpTypesCount <= 10 && p.helpTypesValueList.all { it in 1..7 } && (!p.hasAvailability() || p.availabilityValue in 1..3))
                authority = lookup.lookup(AuthorityKind.HELPER, sender, 0)
            }
            5 -> OfferPublishV1.parseFrom(data).also { p ->
                check(SigningIdentity.hex(p.ownerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR)
                check(uuid(p.offerId) && p.resourceTypeValue in 1..6 && (!p.hasQuantity() || p.quantity in 0..1000000) && text(p.unit, 40) && text(p.coarseArea, 120) &&
                    (!p.hasAvailableUntilMs() || p.availableUntilMs in (e.createdAtMs + 1)..MAX_SAFE))
                authority = lookup.lookup(AuthorityKind.OFFER, p.offerId, 0)
                check(authority == null || authority?.ownerNodeId == sender, Rejection.INVALID_ACTOR)
                check(p.consent.publicMeshAllowed, Rejection.CONSENT_REQUIRED)
            }
            6 -> OfferUpdateV1.parseFrom(data).also { p ->
                check(SigningIdentity.hex(p.ownerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR)
                check(uuid(p.offerId) && p.stateValue in 1..2 && (!p.hasQuantity() || p.quantity in 0..1000000) && text(p.unit, 40) && text(p.coarseArea, 120) && text(p.note, 500) &&
                    (!p.hasAvailableUntilMs() || p.availableUntilMs in (e.createdAtMs + 1)..MAX_SAFE))
                owner(AuthorityKind.OFFER, p.offerId)
            }
            7 -> CloudAckV1.parseFrom(data).also { p ->
                check(uuid(p.originEventId) && p.statusValue in 1..3 && p.cloudRevision in 1..MAX_SAFE && p.receivedAtMs in 1..(now + CLOCK_SKEW) && text(p.errorCode, 80))
            }
            8 -> MatchProposedV1.parseFrom(data).also { p ->
                check(uuid(p.proposalId) && p.version > 0 && uuid(p.requestId) && uuid(p.offerId) && uuid(p.approvalId) && text(p.explanation, 1000, true))
            }
            9 -> MatchDeclinedV1.parseFrom(data).also { p ->
                check(uuid(p.proposalId) && p.version > 0 && text(p.reason, 500))
                check(SigningIdentity.hex(p.offerOwnerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR); owner(AuthorityKind.PROPOSAL, p.proposalId, p.version)
            }
            10 -> MatchAcceptedV1.parseFrom(data).also { p ->
                check(uuid(p.proposalId) && p.version > 0 && text(p.note, 500))
                check(SigningIdentity.hex(p.offerOwnerNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR); owner(AuthorityKind.PROPOSAL, p.proposalId, p.version)
            }
            11 -> MatchConfirmedV1.parseFrom(data).also { p ->
                check(uuid(p.proposalId) && p.version > 0 && uuid(p.requestId) && uuid(p.offerId) && uuid(p.allocationId))
            }
            12 -> DeliveryReceiptV1.parseFrom(data).also { p ->
                check(uuid(p.packetId) && p.receiptTypeValue in 1..2 && p.observedAtMs in 1..(now + CLOCK_SKEW))
                check(SigningIdentity.hex(p.recipientNodeId.toByteArray()) == sender, Rejection.INVALID_ACTOR); owner(AuthorityKind.RECEIPT, p.packetId)
            }
            else -> throw ProtocolException(Rejection.UNSUPPORTED_KIND)
        }
        if (forCloud) {
            val allowed = when (payload) {
                is SosCreateV1 -> payload.consent.cloudProcessingAllowed
                is HelperProfilePublishV1 -> payload.consent.cloudProcessingAllowed
                is OfferPublishV1 -> payload.consent.cloudProcessingAllowed
                is SosUpdateV1 -> if (payload.hasCloudProcessingAllowed()) payload.cloudProcessingAllowed || !payload.hasNote() else authority?.cloudProcessingAllowed == true
                is HelperProfileUpdateV1 -> if (payload.hasConsent()) payload.consent.cloudProcessingAllowed ||
                    (!payload.hasDisplayName() && !payload.hasCoarseArea() && payload.helpTypesCount == 0 && !payload.hasAvailability()) else authority?.cloudProcessingAllowed == true
                is OfferUpdateV1 -> if (payload.hasConsent()) payload.consent.cloudProcessingAllowed ||
                    (!payload.hasNote() && !payload.hasCoarseArea() && !payload.hasQuantity() && !payload.hasUnit() && !payload.hasAvailableUntilMs()) else authority?.cloudProcessingAllowed == true
                else -> authority?.cloudProcessingAllowed == true
            }
            check(allowed, Rejection.CONSENT_REQUIRED)
            val revocation = when (payload) {
                is SosUpdateV1 -> if (payload.hasCloudProcessingAllowed() && !payload.cloudProcessingAllowed)
                    SosUpdateV1.newBuilder().setRequestId(payload.requestId).setStateValue(payload.stateValue).setCloudProcessingAllowed(false).build() else null
                is HelperProfileUpdateV1 -> if (payload.hasConsent() && !payload.consent.cloudProcessingAllowed)
                    HelperProfileUpdateV1.newBuilder().setOwnerNodeId(payload.ownerNodeId)
                        .setConsent(ConsentV1.newBuilder().setPublicMeshAllowed(payload.consent.publicMeshAllowed)).build() else null
                is OfferUpdateV1 -> if (payload.hasConsent() && !payload.consent.cloudProcessingAllowed)
                    OfferUpdateV1.newBuilder().setOfferId(payload.offerId).setOwnerNodeId(payload.ownerNodeId).setStateValue(payload.stateValue)
                        .setConsent(ConsentV1.newBuilder().setPublicMeshAllowed(payload.consent.publicMeshAllowed)).build() else null
                else -> null
            }
            if (revocation != null) check(revocation.toByteString() == data && WireCodec.knownEnvelopeBytes(e).contentEquals(raw), Rejection.CONSENT_REQUIRED)
        }
        return VerifiedPacket(e, payload, packet)
    }
    private fun uuid(value: String) = value.matches(UUID)
    private fun text(value: String, max: Int, required: Boolean = false) = value.length <= max && (!required || value.isNotBlank())
    companion object {
        const val MAX_PACKET = 32768
        const val MAX_ENVELOPE = 24576
        const val MAX_PAYLOAD = 16384
        const val MAX_TTL = 86400000L
        const val CLOCK_SKEW = 300000L
        private const val MAX_SAFE = 9007199254740991L
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val NODE = Regex("[a-f0-9]{64}")
    }
}
