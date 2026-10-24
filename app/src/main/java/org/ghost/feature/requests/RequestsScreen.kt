package org.ghost.feature.requests

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.*
import org.ghost.domain.model.LocalCloudState

@Composable
fun RequestsScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                   contentPadding: PaddingValues = PaddingValues(0.dp)) {
    GhostScreen("Saved requests", "Reports stored on this phone.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                if (state.requests.isEmpty()) item("empty") {
                    InfoCard("No local requests yet", "Create an SOS from Home, or connect to another phone to receive one.")
                }
                items(state.requests, key = { "request-${it.requestId.value}" }) { request ->
                    InfoCard(request.category.name.displayName(), request.description ?: "No description provided") {
                        StatusLabel(request.localDelivery.label())
                        if (!request.createdHere) Text("${request.relayHops} transfers from source · ${request.remainingRelayHops} remaining")
                        if (request.acknowledgedNeighbors > 0) Text("${request.acknowledgedNeighbors} next phones acknowledged saving this SOS")
                        request.peopleCount?.let { Text("People needing help: $it") }
                        Text(request.coarseArea ?: "Area not provided")
                        Text("Source ${request.sourceNodeId.value.take(8)} · reported ${fixtureTime(request.reportedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(when (request.cloudState) {
                            LocalCloudState.ACCEPTED -> "Received by server · signed acknowledgement saved. Assistance is unconfirmed."
                            LocalCloudState.QUEUED -> "Queued for cloud upload. Assistance is unconfirmed."
                            LocalCloudState.REJECTED -> "Cloud upload rejected. Check gateway setup and retry."
                            LocalCloudState.EXPIRED -> "Cloud upload expired. Assistance is unconfirmed."
                            LocalCloudState.NOT_SHARED -> "Not shared with cloud. Assistance is unconfirmed."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(state.offers, key = { "offer-${it.offerId.value}" }) { offer ->
                    InfoCard("${offer.resourceType.name.displayName()} offer", offer.coarseArea ?: "Area not provided") {
                        StatusLabel(offer.state.name.displayName() + if (offer.stale) " · stale" else " · owner reported")
                        Text(offer.quantity?.let { "$it ${offer.unit ?: "units (unspecified)"}" } ?: "Quantity not provided")
                        Text("Owner ${offer.ownerNodeId.value.take(8)} · updated ${fixtureTime(offer.updatedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        offer.availableUntilMs?.let { Text("Available until ${fixtureTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
