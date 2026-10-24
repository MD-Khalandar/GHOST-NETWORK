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
    LocalDelivery.QUEUED -> "Saved. Waiting for a nearby phone."
    LocalDelivery.SENT_TO_NEIGHBOR -> "Sent to a nearby phone. Waiting for it to confirm saving."
    LocalDelivery.RECEIVED_BY_PHONE -> "A nearby phone saved your request."
    LocalDelivery.RECEIVED_FROM_PEER -> "Saved from another phone. Waiting to pass it along."
    LocalDelivery.RELAYED -> "Passed along. Another phone saved the request."
    LocalDelivery.RELAY_LIMIT_REACHED -> "Saved. Reached the limit for passing between phones."
    LocalDelivery.EXPIRED -> "Too old to send. Still saved on this phone."
}

fun GatewayReachability.label(): String = when (this) {
    GatewayReachability.UNKNOWN -> "Online connection not checked"
    GatewayReachability.OFFLINE -> "No connection to online help"
    GatewayReachability.ONLINE -> "Connected to online help"
    GatewayReachability.AUTHENTICATION_FAILED -> "Couldn't sign in to online help"
}

fun CloudCoordinationStatus.label(): String = when (this) {
    CloudCoordinationStatus.NOT_CONNECTED -> "Online coordination isn't available yet."
    CloudCoordinationStatus.WAITING_FOR_GATEWAY -> "Waiting for a phone with internet"
    CloudCoordinationStatus.UPLOADED -> "Request saved online"
    CloudCoordinationStatus.AWAITING_OPERATOR -> "Waiting for a coordinator to review"
    CloudCoordinationStatus.PROPOSAL_SENT -> "Help suggestion sent"
    CloudCoordinationStatus.RECEIVED_BY_PHONE -> "Help suggestion saved on the phone"
    CloudCoordinationStatus.ALLOCATION_CONFIRMED -> "Assistance confirmed"
    CloudCoordinationStatus.FAILED -> "Couldn't arrange help online"
}

fun OfferState.label(): String = when (this) {
    OfferState.AVAILABLE -> "Available"
    OfferState.WITHDRAWN -> "No longer offered"
    OfferState.EXPIRED -> "Availability has ended"
    OfferState.PARTIALLY_ALLOCATED -> "Some supplies promised"
    OfferState.FULLY_ALLOCATED -> "All supplies promised"
}

internal fun String.displayName(): String = lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

internal fun fixtureTime(milliseconds: Long): String = java.text.SimpleDateFormat(
    "dd MMM yyyy, HH:mm 'UTC'", java.util.Locale.ENGLISH,
).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(milliseconds))
