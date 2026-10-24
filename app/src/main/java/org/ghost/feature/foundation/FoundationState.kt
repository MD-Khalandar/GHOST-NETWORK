package org.ghost.feature.foundation

import org.ghost.domain.model.*
import org.ghost.gateway.api.GatewayReachability
import org.ghost.transport.api.PeerHandle

/** Presentation-only CP01 state. Queued/delivery counts are not inferred from request totals. */
data class FoundationState(
    val requests: List<DashboardSosSummary> = emptyList(),
    val offers: List<OfferSummary> = emptyList(),
    val mission: CloudCoordinationState = CloudCoordinationState(CloudCoordinationStatus.NOT_CONNECTED),
    val peers: List<PeerHandle> = emptyList(),
    val cloud: GatewayReachability = GatewayReachability.UNKNOWN,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

fun GatewayReachability.label(): String = when (this) {
    GatewayReachability.UNKNOWN -> "Cloud status unknown"
    GatewayReachability.OFFLINE -> "Cloud unavailable"
    GatewayReachability.ONLINE -> "Cloud reachable"
    GatewayReachability.AUTHENTICATION_FAILED -> "Cloud authentication failed"
}

fun CloudCoordinationStatus.label(): String = when (this) {
    CloudCoordinationStatus.NOT_CONNECTED -> "Cloud unavailable"
    CloudCoordinationStatus.WAITING_FOR_GATEWAY -> "Waiting for internet gateway"
    CloudCoordinationStatus.UPLOADED -> "Uploaded to cloud"
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
