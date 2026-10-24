package org.ghost.feature.network

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ghost.design.*
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.label

@Composable
fun NetworkScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GhostScreen("Network", "Local links and cloud reachability are shown separately.", modifier) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("radio") { InfoCard("Radio simulation", "No Bluetooth, Wi-Fi discovery or device permissions are used in this preview.") }
                item("cloud") { InfoCard(state.cloud.label(), "Reachability alone does not confirm an uploaded report or assistance.") }
                item("peers") {
                    InfoCard(if (state.peers.isEmpty()) "No nearby peers" else "Simulated nearby peers",
                        "These are direct-link observations. They do not prove a relay path or recipient delivery.")
                }
                items(state.peers, key = { "${it.transportId}:${it.endpointId}" }) { peer ->
                    InfoCard("Peer ${peer.endpointId}", "Temporary connection handle · ${peer.transportId}")
                }
                item("sharing") { InfoCard("Gateway sharing is off", "Sharing through a gateway will need your permission. This preview does not upload reports.") }
            }
        }
    }
}
