package org.ghost.feature.requests

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import org.ghost.app.LocalActions
import org.ghost.app.LocalUiState
import org.ghost.domain.model.SosState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.*
import org.ghost.domain.model.LocalCloudState

@Composable
fun RequestsScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                   contentPadding: PaddingValues = PaddingValues(0.dp), local: LocalUiState = LocalUiState(), actions: LocalActions? = null) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    var closingId by rememberSaveable { mutableStateOf<String?>(null) }
    var resolving by rememberSaveable { mutableStateOf(false) }
    GhostScreen("Saved requests", "Requests for help saved on this phone.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                local.error?.let { item("close-error") { InfoCard("Couldn't update request", it) } }
                local.notice?.let { item("close-notice") { Text(it) } }
                item("help-explanation") {
                    TextButton({ showExplanation = !showExplanation }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (showExplanation) "Hide explanation" else "How getting help works")
                    }
                }
                if (showExplanation) item("help-policy") {
                    InfoCard("How getting help works", "Each request below shows its own sharing and server receipt status.") {
                        Text("A server receipt confirms storage. AI suggestions are reviewed in the web dashboard; helper acceptance and confirmed assistance are not available yet.")
                        Text("Nearby phones can pass requests along without internet. Saving online needs a configured gateway with internet and your online-sharing permission.")
                    }
                }
                if (state.requests.isEmpty()) item("empty") {
                    InfoCard("No requests yet", "Ask for help from Home, or connect to GHOST to receive requests from nearby phones.")
                }
                items(state.requests, key = { "request-${it.requestId.value}" }) { request ->
                    InfoCard(request.category.name.displayName(), request.description ?: "No description provided") {
                        val closed = request.state in setOf(SosState.CANCELLED, SosState.RESOLVED, SosState.CLOSED)
                        if (closed) {
                            StatusLabel(if (request.state == SosState.RESOLVED) "Reported resolved by requester" else "Cancelled by requester")
                            Text(if (request.closureShared) "Closure saved here. Other phones update when it reaches them or the original SOS expires."
                                else "Closed on this phone only.")
                        } else {
                        StatusLabel(request.localDelivery.label())
                        if (!request.createdHere) Text("Passed along ${request.relayHops} ${if (request.relayHops == 1) "time" else "times"}. Can be passed on ${request.remainingRelayHops} more ${if (request.remainingRelayHops == 1) "time" else "times"}.")
                        if (request.acknowledgedNeighbors > 0) Text("Saved by ${request.acknowledgedNeighbors} nearby ${if (request.acknowledgedNeighbors == 1) "phone" else "phones"}.")
                        }
                        request.peopleCount?.let { Text("People needing help: $it") }
                        Text(request.coarseArea ?: "Area not provided")
                        Text("${if (request.createdHere) "You asked for help" else "Help requested"} · ${fixtureTime(request.reportedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (closed) when (request.cloudState) {
                            LocalCloudState.ACCEPTED -> "Closure received by server; receipt verified."
                            LocalCloudState.QUEUED -> "Closure waiting for an online gateway."
                            LocalCloudState.REJECTED -> "Server rejected the closure. Retry from Connections."
                            LocalCloudState.EXPIRED -> "Closure sharing window ended; original SOS has also expired."
                            LocalCloudState.NOT_SHARED -> "Closure not shared online."
                        } else when (request.cloudState) {
                            LocalCloudState.ACCEPTED -> "Received by server · receipt verified. Help is not confirmed."
                            LocalCloudState.QUEUED -> "Waiting to be saved online by a phone with internet. Help is not confirmed."
                            LocalCloudState.REJECTED -> "Server couldn't save this request. Check gateway setup and try again."
                            LocalCloudState.EXPIRED -> "Too old to upload. Help is not confirmed."
                            LocalCloudState.NOT_SHARED -> "Not shared online. Help is not confirmed."
                        }, style = MaterialTheme.typography.bodySmall)
                        if (request.createdHere && !request.stale && !closed && actions?.closeSos != null) {
                            TextButton({ closingId = request.requestId.value; resolving = false }, enabled = !local.saving) { Text("Cancel SOS") }
                            TextButton({ closingId = request.requestId.value; resolving = true }, enabled = !local.saving) { Text("Mark resolved") }
                        }
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
    closingId?.let { id ->
        AlertDialog(onDismissRequest = { closingId = null }, title = { Text(if (resolving) "Mark this SOS resolved?" else "Cancel this SOS?") },
            text = { Text("This ends this request. Send a new SOS if you need help again. Other phones and the server update after delivery; offline copies can remain until expiry.") },
            confirmButton = { TextButton({ actions?.closeSos?.invoke(id, if (resolving) SosState.RESOLVED else SosState.CANCELLED); closingId = null }, enabled = !local.saving) { Text(if (resolving) "Mark resolved" else "Cancel SOS") } },
            dismissButton = { TextButton({ closingId = null }) { Text("Keep request open") } })
    }
}
