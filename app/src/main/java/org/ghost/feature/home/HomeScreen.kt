package org.ghost.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.Json
import org.ghost.app.LocalUiState
import org.ghost.design.*
import org.ghost.domain.model.*
import org.ghost.feature.foundation.*

@Composable
fun HomeScreen(state: FoundationState, onSendSos: () -> Unit, onOpenRequests: () -> Unit, onOpenNetwork: () -> Unit,
               onRetry: () -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
               local: LocalUiState = LocalUiState()) {
    GhostScreen("Help starts nearby", "Save an SOS here. Share it with paired nearby phones.", modifier, contentPadding, state.simulated) {
        item("sos") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Need assistance?", style = MaterialTheme.typography.titleLarge)
                    Text("Describe what you need, then review who can see your report.")
                    Button(onClick = onSendSos, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) { Text("Send SOS") }
                    Text("Saved before sending. No peers are required to save.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        local.error?.let { item("error") { InfoCard("Action unavailable", it) } }
        local.notice?.let { item("notice") { InfoCard("Local status", it) } }
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("reports") {
                    InfoCard("Saved reports", "${state.requests.size} on this phone") {
                        Text("${state.requests.count { it.localDelivery == LocalDelivery.QUEUED }} queued for nearby phones")
                        TextButton(onOpenRequests, Modifier.heightIn(min = 48.dp)) { Text("View requests") }
                    }
                }
                item("connection") {
                    InfoCard(if (state.peers.isEmpty()) "No connected phones" else "${state.peers.size} connected phones", state.cloud.label()) {
                        Text("Keep the app open to exchange and relay public SOS reports. Cloud sync is not implemented yet.")
                        TextButton(onOpenNetwork, Modifier.heightIn(min = 48.dp)) { Text("View network") }
                    }
                }
            }
        }
    }
}

private val DraftSaver = Saver<SosDraft, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })

@Composable
fun SosFormDialog(initial: SosDraft, busy: Boolean, error: String?, canSave: Boolean,
                  onChange: (SosDraft) -> Unit, onSave: (SosDraft) -> Unit, onClose: (SosDraft) -> Unit) {
    var draft by rememberSaveable(initial.requestId, stateSaver = DraftSaver) { mutableStateOf(initial) }
    var review by rememberSaveable { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    fun update(value: SosDraft) { draft = value; validation = null; onChange(value) }
    Dialog(onDismissRequest = { if (!busy) onClose(draft) }, properties = DialogProperties(
        dismissOnClickOutside = false, dismissOnBackPress = !busy, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (review) "Review SOS" else "Create SOS", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (review) {
                        Text(draft.category.name.displayName(), style = MaterialTheme.typography.titleMedium)
                        Text(draft.description.trim())
                        if (draft.people.isNotBlank()) Text("People: ${draft.people}")
                        Text(draft.area.trim().ifBlank { "No area provided" })
                        Text(if (draft.publicSharing) "Audience: nearby phones, including through relays. Other phones can read and forward this report." else "Audience: this phone only.")
                        Text(if (draft.cloudConsent) "Cloud permission: on. No upload happens until cloud sync is implemented." else "Cloud permission: off.")
                        Text("Saving or sending does not mean help has been arranged.")
                    } else {
                        Box {
                            OutlinedButton({ categoryMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Category: ${draft.category.name.displayName()}") }
                            DropdownMenu(categoryMenu, { categoryMenu = false }) {
                                SosCategory.entries.forEach { category -> DropdownMenuItem(text = { Text(category.name.displayName()) }, onClick = { update(draft.copy(category = category)); categoryMenu = false }) }
                            }
                        }
                        OutlinedTextField(draft.description, { if (it.length <= 2000) update(draft.copy(description = it)) }, enabled = !busy,
                            label = { Text("What help do you need?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.people, { if (it.length <= 4 && it.all(Char::isDigit)) update(draft.copy(people = it)) }, enabled = !busy,
                            label = { Text("People (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.area, { if (it.length <= 120) update(draft.copy(area = it)) }, enabled = !busy,
                            label = { Text("Area or landmark (optional)") }, modifier = Modifier.fillMaxWidth())
                        ConsentChoice("Share publicly with nearby phones", draft.publicSharing, !busy) { update(draft.copy(publicSharing = it)) }
                        Text("Other phones can read and forward public reports. Signing protects the report from changes; it does not hide its content.")
                        ConsentChoice("Allow cloud coordination", draft.cloudConsent, !busy) { update(draft.copy(cloudConsent = it)) }
                        Text("Cloud consent is separate. No cloud upload is implemented yet.")
                    }
                    (validation ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!canSave) Text("UI preview: saving is disabled.")
                }
                Button(onClick = {
                    if (!review) {
                        validation = when {
                            draft.description.isBlank() -> "Describe what help you need."
                            draft.people.isNotBlank() && (draft.people.toIntOrNull() ?: 0) !in 1..1000 -> "People count must be between 1 and 1000."
                            else -> null
                        }
                        if (validation == null) review = true
                    } else onSave(draft)
                }, enabled = !busy && (!review || canSave), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (busy) "Saving?" else if (!review) "Review SOS" else if (draft.publicSharing) "Save & send SOS" else "Save SOS")
                }
                if (review) TextButton({ review = false }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit report") }
                TextButton({ onClose(draft) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (canSave) "Close & save draft" else "Close") }
            }
        }
    }
}

@Composable
private fun ConsentChoice(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
