package org.ghost.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Consent(
    @SerialName("public_mesh_allowed") val publicMeshAllowed: Boolean = false,
    @SerialName("cloud_processing_allowed") val cloudProcessingAllowed: Boolean = false,
)

@Serializable
enum class EventKind {
    SOS_CREATE,
    SOS_UPDATE,
    HELPER_PROFILE_PUBLISH,
    HELPER_PROFILE_UPDATE,
    OFFER_PUBLISH,
    OFFER_UPDATE,
    CLOUD_ACK,
    MATCH_PROPOSED,
    MATCH_DECLINED,
    MATCH_ACCEPTED,
    MATCH_CONFIRMED,
    DELIVERY_RECEIPT,
    PEER_HELLO,
    INVENTORY,
}

@Serializable
enum class Priority {
    ROUTINE,
    IMPORTANT,
    SOS,
}

@Serializable
enum class PayloadFormat {
    PROTOBUF,
}

@Serializable
enum class DestinationKind {
    BROADCAST,
    NODE,
}

@Serializable
data class Destination(
    val kind: DestinationKind,
    @SerialName("node_id") val nodeId: NodeId? = null,
)

@Serializable
data class ImmutableEnvelope(
    val version: Int,
    @SerialName("packet_id") val packetId: PacketId,
    @SerialName("event_id") val eventId: EventId,
    @SerialName("sender_node_id") val senderNodeId: NodeId,
    @SerialName("sender_signing_public_key") val senderSigningPublicKeyBase64: String,
    val destination: Destination,
    val kind: EventKind,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
    val priority: Priority,
    @SerialName("payload_format") val payloadFormat: PayloadFormat,
    @SerialName("payload_bytes") val payloadBytesBase64: String,
)

@Serializable
data class RelayMetadata(
    @SerialName("remaining_hops") val remainingHops: Int,
    @SerialName("hop_count") val hopCount: Int,
    @SerialName("previous_node_id") val previousNodeId: NodeId? = null,
)

/** Transport representation. Relays may update only [relay]. */
@Serializable
data class WirePacket(
    @SerialName("immutable_envelope_bytes") val immutableEnvelopeBytesBase64: String,
    val signature: String,
    val relay: RelayMetadata,
)

@Serializable
enum class SosCategory {
    MEDICAL,
    TRAPPED,
    WATER,
    FOOD,
    SHELTER,
    OTHER,
}

@Serializable
enum class SosState {
    OPEN,
    ACKNOWLEDGED,
    HELP_OFFERED,
    CLOSED,
}

@Serializable
data class SosCreatePayload(
    @SerialName("request_id") val requestId: RequestId,
    val category: SosCategory,
    val description: String,
    @SerialName("people_count") val peopleCount: Int? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    val landmark: String? = null,
    val consent: Consent,
)

@Serializable
data class SosUpdatePayload(
    @SerialName("request_id") val requestId: RequestId,
    val state: SosState,
    val note: String? = null,
    @SerialName("cloud_processing_allowed") val cloudProcessingAllowed: Boolean? = null,
)

@Serializable
enum class HelpType {
    WATER,
    TRANSPORT,
    SEARCH,
    SHELTER,
    FOOD,
    FIRST_AID,
    OTHER_HELP,
}

@Serializable
enum class HelperAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

@Serializable
data class HelperProfilePublishPayload(
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("help_types") val helpTypes: List<HelpType>,
    val availability: HelperAvailability,
    val consent: Consent,
)

@Serializable
data class HelperProfileUpdatePayload(
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("help_types") val helpTypes: List<HelpType> = emptyList(),
    val availability: HelperAvailability? = null,
    val consent: Consent? = null,
)

@Serializable
enum class ResourceType {
    WATER,
    FOOD,
    SHELTER,
    MEDICAL_SUPPLIES,
    TRANSPORT,
    OTHER_RESOURCE,
}

@Serializable
enum class OfferState {
    AVAILABLE,
    WITHDRAWN,
    EXPIRED,
    PARTIALLY_ALLOCATED,
    FULLY_ALLOCATED,
}

@Serializable
data class OfferPublishPayload(
    @SerialName("offer_id") val offerId: OfferId,
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    @SerialName("resource_type") val resourceType: ResourceType,
    val quantity: Int? = null,
    val unit: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("available_until_ms") val availableUntilMs: Long? = null,
    val consent: Consent,
)

@Serializable
data class OfferUpdatePayload(
    @SerialName("offer_id") val offerId: OfferId,
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    val state: OfferState,
    val quantity: Int? = null,
    val unit: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("available_until_ms") val availableUntilMs: Long? = null,
    val note: String? = null,
    val consent: Consent? = null,
)

@Serializable
enum class CloudAckStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
}

@Serializable
data class CloudAckPayload(
    @SerialName("origin_event_id") val originEventId: EventId,
    val status: CloudAckStatus,
    @SerialName("cloud_revision") val cloudRevision: Long,
    @SerialName("received_at_ms") val receivedAtMs: Long,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class MatchProposedPayload(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    @SerialName("request_id") val requestId: RequestId,
    @SerialName("offer_id") val offerId: OfferId,
    val explanation: String,
    @SerialName("approval_id") val approvalId: ApprovalId,
)

@Serializable
data class MatchDeclinedPayload(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    @SerialName("offer_owner_node_id") val offerOwnerNodeId: NodeId,
    val reason: String? = null,
)

@Serializable
data class MatchAcceptedPayload(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    @SerialName("offer_owner_node_id") val offerOwnerNodeId: NodeId,
    val note: String? = null,
)

@Serializable
data class MatchConfirmedPayload(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    @SerialName("request_id") val requestId: RequestId,
    @SerialName("offer_id") val offerId: OfferId,
    @SerialName("allocation_id") val allocationId: AllocationId,
)

@Serializable
enum class ReceiptType {
    PERSISTED_BY_RECIPIENT,
    READ_BY_RECIPIENT,
}

@Serializable
data class DeliveryReceiptPayload(
    @SerialName("packet_id") val packetId: PacketId,
    @SerialName("recipient_node_id") val recipientNodeId: NodeId,
    @SerialName("receipt_type") val receiptType: ReceiptType,
    @SerialName("observed_at_ms") val observedAtMs: Long,
)
