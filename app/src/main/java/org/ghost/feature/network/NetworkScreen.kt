package org.ghost.feature.network

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.label
import org.ghost.feature.foundation.DeviceTrustState
import org.ghost.feature.foundation.IdentityStatus
import org.ghost.feature.foundation.CloudTrustStatus
import org.ghost.feature.foundation.OperatorAccess

@Composable
fun NetworkScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                  contentPadding: PaddingValues = PaddingValues(0.dp), trust: DeviceTrustState = DeviceTrustState(),
                  onRetryIdentity: (() -> Unit)? = null) {
    GhostScreen("Network", "Local links and cloud reachability are shown separately.", modifier, contentPadding) {
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
                item("identity") {
                    InfoCard("Device signing identity", when (trust.identity) {
                        IdentityStatus.CHECKING -> "Checking this phone's signing key."
                        IdentityStatus.READY -> "A signing key is loaded from Android Keystore. Hardware protection depends on this device."
                        IdentityStatus.UNAVAILABLE -> "Signing key unavailable. Sending must wait until the key can be loaded."
                    }) {
                        trust.nodeId?.let { Text("Node ID: $it") }
                        if (trust.identity == IdentityStatus.UNAVAILABLE && onRetryIdentity != null) {
                            TextButton(onClick = onRetryIdentity, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry identity") }
                        }
                    }
                }
                item("trust") {
                    InfoCard("Cloud signature verification", when (trust.cloudTrust) {
                        CloudTrustStatus.NOT_CONFIGURED -> "Cloud signing key is not configured. Cloud results cannot be trusted yet."
                        CloudTrustStatus.PINNED -> "Cloud results must match the configured signing key. This does not confirm connectivity."
                        CloudTrustStatus.INVALID -> "Cloud signing key is invalid. Cloud results will be rejected."
                    }) { trust.cloudNodeId?.let { Text("Cloud identity: $it") } }
                }
                item("operator") {
                    InfoCard(when (trust.operatorAccess) {
                        OperatorAccess.SIGN_IN_REQUIRED -> "Operator sign-in required"
                        OperatorAccess.NOT_AUTHORIZED -> "Operator access denied"
                        OperatorAccess.AUTHENTICATED_OPERATOR -> "Operator session authenticated"
                    }, "Operator actions belong in the authenticated web dashboard. This phone cannot grant approval authority.")
                }
            }
        }
    }
}
