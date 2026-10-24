package org.ghost.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncUploadRequest(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val packets: List<WirePacket>,
)

@Serializable
data class SyncUploadItemResult(
    @SerialName("event_id") val eventId: EventId,
    val status: CloudAckStatus,
    @SerialName("cloud_revision") val cloudRevision: Long? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class SyncUploadResponse(
    val results: List<SyncUploadItemResult>,
)

@Serializable
data class SyncDownloadResponse(
    val cursor: String,
    @SerialName("next_cursor") val nextCursor: String,
    val packets: List<WirePacket>,
)

@Serializable
data class SyncDownloadAckRequest(
    val cursor: String,
    @SerialName("packet_ids") val packetIds: List<PacketId>,
)

@Serializable
data class DashboardSosSummary(
    @SerialName("request_id") val requestId: RequestId,
    val category: SosCategory,
    val state: SosState,
    val description: String? = null,
    @SerialName("people_count") val peopleCount: Int? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("source_node_id") val sourceNodeId: NodeId,
    @SerialName("reported_at_ms") val reportedAtMs: Long,
    @SerialName("received_at_ms") val receivedAtMs: Long,
    val stale: Boolean,
    val localDelivery: LocalDelivery = LocalDelivery.SAVED_LOCALLY,
    val createdHere: Boolean = false,
)

@Serializable
data class OfferSummary(
    @SerialName("offer_id") val offerId: OfferId,
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    @SerialName("resource_type") val resourceType: ResourceType,
    val quantity: Int? = null,
    val unit: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("available_until_ms") val availableUntilMs: Long? = null,
    val state: OfferState,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    val stale: Boolean,
)

@Serializable
data class CloudCoordinationState(
    val status: CloudCoordinationStatus,
    @SerialName("request_id") val requestId: RequestId? = null,
    @SerialName("proposal_id") val proposalId: ProposalId? = null,
    val version: Int? = null,
    val message: String? = null,
)

@Serializable
enum class CloudCoordinationStatus {
    NOT_CONNECTED,
    WAITING_FOR_GATEWAY,
    UPLOADED,
    AWAITING_OPERATOR,
    PROPOSAL_SENT,
    RECEIVED_BY_PHONE,
    ALLOCATION_CONFIRMED,
    FAILED,
}
