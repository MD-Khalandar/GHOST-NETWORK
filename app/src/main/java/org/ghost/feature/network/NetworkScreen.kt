package org.ghost.feature.network

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
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
import org.ghost.app.LocalActions
import org.ghost.app.LocalUiState
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings

@Composable
fun NetworkScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                  contentPadding: PaddingValues = PaddingValues(0.dp), trust: DeviceTrustState = DeviceTrustState(),
                  onRetryIdentity: (() -> Unit)? = null, local: LocalUiState = LocalUiState(), actions: LocalActions? = null) {
    val context = LocalContext.current
    GhostScreen("Network", "Direct phone links. Keep Bluetooth and Wi-Fi on.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("radio") {
                    InfoCard("Nearby phones", local.network.message) {
                        Text("Nearby runs while this app is open. Confirm the pairing code on both phones.")
                        Button(onClick = { if (local.network.active || local.network.starting) actions?.stopNearby?.invoke() else actions?.startNearby?.invoke() }, enabled = actions != null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (local.network.active || local.network.starting) "Stop nearby" else "Start nearby") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
                    }
                }
                local.error?.let { message -> item("local-error") { InfoCard("Local action unavailable", message) } }
                (local.exchangeStatus ?: local.notice)?.let { message -> item("exchange") { InfoCard("Exchange status", message) } }
                items(local.network.discovered.filterNot { found -> state.peers.any { it.endpointId == found.endpointId } }, key = { "discovered-${it.endpointId}" }) { phone ->
                    InfoCard(phone.name.ifBlank { "Nearby phone" }, "Discovered locally · not connected") {
                        TextButton(onClick = { actions?.connect?.invoke(phone.endpointId) }, enabled = actions != null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Connect") }
                    }
                }
                item("cloud") { InfoCard(state.cloud.label(), "Reachability alone does not confirm an uploaded report or assistance.") }
                item("peers") {
                    InfoCard(if (state.peers.isEmpty()) "No connected phones" else "Connected phones",
                        "These are direct-link observations. They do not prove a relay path or recipient delivery.")
                }
                items(state.peers, key = { "${it.transportId}:${it.endpointId}" }) { peer ->
                    InfoCard(local.peerIdentities[peer.endpointId]?.let { "Phone ${it.take(8)}" } ?: "Verifying phone identity…", "Direct link · ${peer.endpointId}") {
                        TextButton(onClick = { actions?.disconnect?.invoke(peer.endpointId) }, enabled = actions != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Disconnect") }
                    }
                }
                item("retry") { TextButton(onClick = { actions?.retrySends?.invoke() }, enabled = actions != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry queued sends") } }
                item("sharing") { InfoCard("Gateway sharing is off", "Cloud uploads and multi-hop relay are not implemented yet.") }
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
