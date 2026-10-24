package org.ghost.feature.foundation

import org.ghost.domain.model.*
import org.ghost.gateway.api.GatewayReachability
import org.ghost.transport.api.PeerHandle

/** Presentation state. Queued/delivery counts are not inferred from request totals. */
data class FoundationState(
    val requests: List<DashboardSosSummary> = emptyList(),
    val offers: List<OfferSummary> = emptyList(),
    val mission: CloudCoordinationState = CloudCoordinationState(CloudCoordinationStatus.NOT_CONNECTED),
    val peers: List<PeerHandle> = emptyList(),
    val cloud: GatewayReachability = GatewayReachability.UNKNOWN,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val simulated: Boolean = false,
)

fun LocalDelivery.label(): String = when (this) {
    LocalDelivery.SAVED_LOCALLY -> "Saved on this phone only"
    LocalDelivery.QUEUED -> "Queued for nearby phones"
    LocalDelivery.SENT_TO_NEIGHBOR -> "Transferred to neighbor · receipt pending"
    LocalDelivery.RECEIVED_BY_PHONE -> "Saved by a nearby phone · signed receipt"
    LocalDelivery.RECEIVED_FROM_PEER -> "Received and saved · queued for relay"
    LocalDelivery.RELAYED -> "Relayed · next phone saved the SOS"
    LocalDelivery.RELAY_LIMIT_REACHED -> "Saved · relay hop limit reached"
    LocalDelivery.EXPIRED -> "Expired · no longer sent"
}

fun GatewayReachability.label(): String = when (this) {
    GatewayReachability.UNKNOWN -> "Cloud status unknown"
    GatewayReachability.OFFLINE -> "Cloud unavailable"
    GatewayReachability.ONLINE -> "Cloud reachable"
    GatewayReachability.AUTHENTICATION_FAILED -> "Cloud authentication failed"
}

fun CloudCoordinationStatus.label(): String = when (this) {
    CloudCoordinationStatus.NOT_CONNECTED -> "Cloud unavailable"
    CloudCoordinationStatus.WAITING_FOR_GATEWAY -> "Waiting for internet gateway"
    CloudCoordinationStatus.UPLOADED -> "Received by server"
    CloudCoordinationStatus.AWAITING_OPERATOR -> "Waiting for operator approval"
    CloudCoordinationStatus.PROPOSAL_SENT -> "Proposal sent"
    CloudCoordinationStatus.RECEIVED_BY_PHONE -> "Received by phone"
    CloudCoordinationStatus.ALLOCATION_CONFIRMED -> "Resource allocation confirmed"
    CloudCoordinationStatus.FAILED -> "Coordination unavailable"
}

internal fun String.displayName(): String = lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

internal fun fixtureTime(milliseconds: Long): String = java.text.SimpleDateFormat(
    "dd MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH,
).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(milliseconds))
