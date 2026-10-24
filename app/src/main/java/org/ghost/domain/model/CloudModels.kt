package org.ghost.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncUploadRequest(
    @SerialName("idempotency_key") val idempotencyKey: String,
    val packets: List<WirePacket>,
    // CP02: legacy field stays empty. Receipts must be signed packets in packets.
    val receipts: List<DeliveryReceiptPayload> = emptyList(),
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
)

@Serializable
data class DashboardSosPage(
    val items: List<DashboardSosSummary>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("server_time_ms") val serverTimeMs: Long,
)

@Serializable
data class HelperSummary(
    @SerialName("owner_node_id") val ownerNodeId: NodeId,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("coarse_area") val coarseArea: String? = null,
    @SerialName("help_types") val helpTypes: List<HelpType>,
    val availability: HelperAvailability,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
    val stale: Boolean,
)

@Serializable
data class HelperPage(
    val items: List<HelperSummary>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("server_time_ms") val serverTimeMs: Long,
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
data class OfferPage(
    val items: List<OfferSummary>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("server_time_ms") val serverTimeMs: Long,
)

@Serializable
enum class ProposalState {
    DRAFT,
    AWAITING_OPERATOR,
    APPROVED,
    REJECTED,
    DECLINED,
    CONFIRMED,
    STALE,
}

@Serializable
data class ProposalSummary(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    @SerialName("request_id") val requestId: RequestId,
    @SerialName("offer_id") val offerId: OfferId,
    val state: ProposalState,
    val explanation: String? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("approval_id") val approvalId: ApprovalId? = null,
)

@Serializable
data class DashboardSosDetail(
    val request: DashboardSosSummary,
    val offers: List<OfferSummary>,
    val proposals: List<ProposalSummary>,
    val receipts: List<DeliveryReceiptPayload>,
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

@Serializable
enum class RunState {
    QUEUED,
    RUNNING,
    WAITING_FOR_OPERATOR,
    WAITING_FOR_OWNER,
    REPLAN_PENDING,
    COMPLETE,
    NO_MATCH,
    FAILED,
    TIMED_OUT,
}

@Serializable
data class RunSummary(
    @SerialName("run_id") val runId: String,
    @SerialName("request_id") val requestId: RequestId,
    val state: RunState,
    @SerialName("model_provider") val modelProvider: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("snapshot_revision") val snapshotRevision: Long,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

@Serializable
data class RunPage(
    val items: List<RunSummary>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("server_time_ms") val serverTimeMs: Long,
)

@Serializable
data class ProposalDecisionRequest(
    val version: Int,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val note: String? = null,
)

@Serializable
enum class ProposalDecisionOutcome {
    APPROVED,
    REJECTED,
    ALREADY_APPLIED,
}

@Serializable
data class ProposalDecisionResponse(
    @SerialName("proposal_id") val proposalId: ProposalId,
    val version: Int,
    val outcome: ProposalDecisionOutcome,
    @SerialName("cloud_revision") val cloudRevision: Long,
    @SerialName("outgoing_packet_ids") val outgoingPacketIds: List<PacketId> = emptyList(),
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    @SerialName("request_id") val requestId: String? = null,
)
