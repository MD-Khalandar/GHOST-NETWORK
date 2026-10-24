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
    GhostScreen("Saved requests", "Requests for help saved on this phone.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                if (state.requests.isEmpty()) item("empty") {
                    InfoCard("No requests yet", "Ask for help from Home, or connect to GHOST to receive requests from nearby phones.")
                }
                items(state.requests, key = { "request-${it.requestId.value}" }) { request ->
                    InfoCard(request.category.name.displayName(), request.description ?: "No description provided") {
                        StatusLabel(request.localDelivery.label())
                        if (!request.createdHere) Text("Passed along ${request.relayHops} ${if (request.relayHops == 1) "time" else "times"}. Can be passed on ${request.remainingRelayHops} more ${if (request.remainingRelayHops == 1) "time" else "times"}.")
                        if (request.acknowledgedNeighbors > 0) Text("Saved by ${request.acknowledgedNeighbors} nearby ${if (request.acknowledgedNeighbors == 1) "phone" else "phones"}.")
                        request.peopleCount?.let { Text("People needing help: $it") }
                        Text(request.coarseArea ?: "Area not provided")
                        Text("${if (request.createdHere) "You asked for help" else "Help requested"} · ${fixtureTime(request.reportedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(when (request.cloudState) {
                            LocalCloudState.ACCEPTED -> "Received by server · receipt verified. Help is not confirmed."
                            LocalCloudState.QUEUED -> "Waiting to be saved online by a phone with internet. Help is not confirmed."
                            LocalCloudState.REJECTED -> "Server couldn't save this request. Check gateway setup and try again."
                            LocalCloudState.EXPIRED -> "Too old to upload. Help is not confirmed."
                            LocalCloudState.NOT_SHARED -> "Not shared online. Help is not confirmed."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(state.offers, key = { "offer-${it.offerId.value}" }) { offer ->
                    InfoCard("${offer.resourceType.name.displayName()} offer", offer.coarseArea ?: "Area not provided") {
                        StatusLabel(offer.state.label() + if (offer.stale) " · may be out of date" else " · as reported by the person offering help")
                        Text(offer.quantity?.let { "$it ${offer.unit ?: "units (unspecified)"}" } ?: "Quantity not provided")
                        Text("Last updated ${fixtureTime(offer.updatedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        offer.availableUntilMs?.let { Text("Available until ${fixtureTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
