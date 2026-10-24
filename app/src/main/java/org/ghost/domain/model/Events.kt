package org.ghost.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Consent(
    @SerialName("public_mesh_allowed") val publicMeshAllowed: Boolean = false,
    @SerialName("cloud_processing_allowed") val cloudProcessingAllowed: Boolean = false,
)

@Serializable
data class RelayMetadata(
    @SerialName("remaining_hops") val remainingHops: Int,
    @SerialName("hop_count") val hopCount: Int,
    @SerialName("previous_node_id") val previousNodeId: NodeId? = null,
)

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
    CANCELLED,
    RESOLVED,
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
    val location: SosLocation? = null,
)

@Serializable
data class SosLocation(
    val latitude: Double,
    val longitude: Double,
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
enum class CloudAckStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
}
