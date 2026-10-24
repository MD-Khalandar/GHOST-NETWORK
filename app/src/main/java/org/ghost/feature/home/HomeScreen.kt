package org.ghost.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.label

@Composable
fun HomeScreen(state: FoundationState, onSendSos: () -> Unit, onOpenRequests: () -> Unit, onOpenNetwork: () -> Unit,
               onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GhostScreen("Help starts nearby", "Share a need. Stay informed. Keep people connected.", modifier) {
        item("sos") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Need assistance?", style = MaterialTheme.typography.titleLarge)
                    Text("An SOS can describe what you need and where help is needed.")
                    Button(onClick = onSendSos, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) { Text("Send SOS") }
                    Text("Preview only. Sending is unavailable.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("reports") {
                    InfoCard("Locally known reports", "${state.requests.size} reports on this phone") {
                        Text("Local visibility does not confirm cloud acceptance or delivery.")
                        TextButton(onOpenRequests, Modifier.heightIn(min = 48.dp)) { Text("View requests") }
                    }
                }
                item("connection") {
                    InfoCard(if (state.peers.isEmpty()) "No nearby peers" else "${state.peers.size} nearby peers", state.cloud.label()) {
                        Text("Cloud coordination needs an internet gateway.")
                        TextButton(onOpenNetwork, Modifier.heightIn(min = 48.dp)) { Text("View network") }
                    }
                }
            }
        }
    }
}

@Composable
fun SosPreviewDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("SOS preview") },
        text = { Text("Sending is unavailable in this preview. Nothing has been sent.\n\nWhen sending is available, you’ll review your report and choose public sharing and cloud consent separately.") },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close preview") } })
}
