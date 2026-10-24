package org.ghost.feature.network

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import org.ghost.app.meshStatus
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings

@Composable
fun NetworkScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                  contentPadding: PaddingValues = PaddingValues(0.dp), trust: DeviceTrustState = DeviceTrustState(),
                  onRetryIdentity: (() -> Unit)? = null, local: LocalUiState = LocalUiState(), actions: LocalActions? = null) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf(false) }
    if (setup) GatewaySetupDialog(local.gateway, local.saving, local.error, onClose = { setup = false },
        onSave = { url, token -> actions?.saveGateway?.invoke(url, token) { setup = false } },
        onRemove = { actions?.clearGateway?.invoke(); setup = false })
    var showTechnicalDetails by rememberSaveable { mutableStateOf(false) }
    GhostScreen("Connections", "Connect with nearby phones to pass along requests for help.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("radio") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(local.meshStatus(), Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (local.network.active || local.network.starting || local.gateway.active) actions?.stopNearby?.invoke() else actions?.startNearby?.invoke() }, enabled = actions != null,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (local.network.active || local.network.starting || local.gateway.active) "Disconnect from GHOST" else "Connect to GHOST") }
                    }
                }
                item("mesh-graph") {
                    NearbyMeshGraph(state.peers, local.peerIdentities, local.network.active, local.network.starting, topology = local.topology)
                }
                item("radio-details") {
                    InfoCard("Connecting nearby", "Keep Bluetooth and Wi-Fi on.") {
                        Text(local.network.message.userMessage())
                        Text("Keep GHOST open so nearby phones can connect and pass along requests you've chosen to share.")
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app settings") }
                    }
                }
                local.error?.let { message -> item("local-error") { InfoCard("Something went wrong", message.userMessage()) } }
                (local.exchangeStatus ?: local.notice)?.let { message -> item("exchange") { InfoCard("Latest update", message.userMessage()) } }
                item("cloud") { InfoCard(state.cloud.label(), "An online connection doesn't mean your request has been saved online or that help is confirmed.") }
                item("retry") { TextButton(onClick = { actions?.retrySends?.invoke() }, enabled = actions != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try sending again") } }
                item("sharing") {
                    InfoCard("Online sharing", local.gateway.message.userMessage()) {
                        Text("GHOST connects to the server automatically over cellular or Wi-Fi. Local LAN servers use Wi-Fi. Only requests and offers whose owners allowed online sharing are saved online. Help is confirmed separately.")
                    }
                }
                item("technical-toggle") {
                    TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (showTechnicalDetails) "Expanded" else "Collapsed"
                        }) { Text(if (showTechnicalDetails) "Hide technical details" else "Technical details") }
                }
                if (showTechnicalDetails) {
                    item("server-settings") {
                        InfoCard("Server connection", local.gateway.serverUrl.ifBlank { "No server address in this app build." }) {
                            Text(if (local.gateway.automatic) "This app manages its own gateway device credentials."
                                else "This build can use manual gateway settings for compatibility.")
                            if (!local.gateway.automatic) TextButton({ setup = true }, enabled = actions?.saveGateway != null,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (local.gateway.configured) "Edit gateway setup" else "Configure gateway")
                            }
                        }
                    }
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
}
