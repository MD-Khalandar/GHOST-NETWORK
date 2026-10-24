package org.ghost.feature.requests

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ghost.design.*
import org.ghost.feature.foundation.*

@Composable
fun RequestsScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GhostScreen("Requests & offers", "Reports known to this phone. Availability is self-reported.", modifier) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                if (state.requests.isEmpty()) item("empty") {
                    InfoCard("No local requests yet", "Shared requests will appear here. This preview does not send reports.")
                }
                items(state.requests, key = { "request-${it.requestId.value}" }) { request ->
                    InfoCard(request.category.name.displayName(), request.description ?: "No description provided") {
                        StatusLabel(if (request.stale) "Stale report · check freshness" else "Local report · ${request.state.name.displayName()}")
                        request.peopleCount?.let { Text("People needing help: $it") }
                        Text(request.coarseArea ?: "Area not provided")
                        Text("Source ${request.sourceNodeId.value.take(8)} · reported ${fixtureTime(request.reportedAtMs)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Delivery and cloud acceptance are unconfirmed.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.offers.isEmpty()) item("no-offers") {
                    InfoCard("No local offers yet", "An offer is a report of availability. Assistance needs confirmation.")
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
