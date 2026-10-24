package org.ghost.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.Json
import org.ghost.app.LocalUiState
import org.ghost.app.LocalActions
import org.ghost.app.meshStatus
import org.ghost.design.*
import org.ghost.domain.model.*
import org.ghost.feature.foundation.*

@Composable
fun HomeScreen(state: FoundationState, onSendSos: () -> Unit, onOpenRequests: () -> Unit, onOpenNetwork: () -> Unit,
               onRetry: () -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
               local: LocalUiState = LocalUiState(), actions: LocalActions? = null) {
    GhostScreen("Help starts nearby", "Ask for help, even without internet.", modifier, contentPadding, state.simulated) {
        item("mesh") {
            InfoCard("Connect with nearby phones", local.meshStatus()) {
                Text("Connect with nearby phones to pass along requests for help. Keep GHOST open to stay connected.")
                Button(onClick = { if (local.network.active || local.network.starting) actions?.stopNearby?.invoke() else actions?.startNearby?.invoke() },
                    enabled = actions != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (local.network.active || local.network.starting) "Disconnect from GHOST" else "Connect to GHOST")
                }
                TextButton(onOpenNetwork, modifier = Modifier.heightIn(min = 48.dp)) { Text("See nearby phones") }
            }
        }
        item("sos") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Need help?", style = MaterialTheme.typography.titleLarge)
                    Text("Describe what you need and send a request for help.")
                    Button(onClick = onSendSos, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) { Text("Send SOS") }
                    Text("Your request is saved on this phone, even when no other phones are nearby.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        local.error?.let { item("error") { InfoCard("Something went wrong", it.userMessage()) } }
        local.notice?.let { item("notice") { InfoCard("Your request", it.userMessage()) } }
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> {
                item("reports") {
                    InfoCard("Saved requests", "${state.requests.size} on this phone") {
                        Text("${state.requests.count { it.localDelivery == LocalDelivery.QUEUED }} waiting to be shared with nearby phones")
                        TextButton(onOpenRequests, Modifier.heightIn(min = 48.dp)) { Text("View requests") }
                    }
                }
            }
        }
    }
}

private val DraftSaver = Saver<SosDraft, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })

@Composable
fun SosFormDialog(initial: SosDraft, busy: Boolean, error: String?, canSave: Boolean,
                  onChange: (SosDraft) -> Unit, onSave: (SosDraft) -> Unit, onClose: (SosDraft) -> Unit,
                  onRequestLocation: (() -> Unit)? = null) {
    var draft by rememberSaveable(initial.requestId, stateSaver = DraftSaver) { mutableStateOf(initial) }
    var details by rememberSaveable { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    fun update(value: SosDraft) { draft = value; validation = null; onChange(value) }
    LaunchedEffect(Unit) { if (draft.locationSharing) onRequestLocation?.invoke() }
    Dialog(onDismissRequest = { if (!busy) onClose(draft) }, properties = DialogProperties(
        dismissOnClickOutside = false, dismissOnBackPress = !busy, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New SOS", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft.description, { if (it.length <= 2000) update(draft.copy(description = it)) }, enabled = !busy,
                        label = { Text("What help do you need?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    TextButton({ details = !details }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (details) "Hide optional details" else "Optional details")
                    }
                    if (details) {
                        Box {
                            OutlinedButton({ categoryMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Category: ${draft.category.name.displayName()}") }
                            DropdownMenu(categoryMenu, { categoryMenu = false }) {
                                SosCategory.entries.forEach { category -> DropdownMenuItem(text = { Text(category.name.displayName()) }, onClick = { update(draft.copy(category = category)); categoryMenu = false }) }
                            }
                        }
                        val people = draft.people.toIntOrNull()?.coerceIn(1..1000) ?: 1
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("People needing help", Modifier.weight(1f))
                            TextButton({ update(draft.copy(people = (people - 1).toString())) }, enabled = !busy && people > 1,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Fewer people" }) { Text("−") }
                            Text("$people")
                            TextButton({ update(draft.copy(people = (people + 1).toString())) }, enabled = !busy && people < 1000,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "More people" }) { Text("+") }
                        }
                        OutlinedTextField(draft.area, { if (it.length <= 120) update(draft.copy(area = it)) }, enabled = !busy,
                            label = { Text("Area or landmark (optional)") }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(if (draft.publicSharing) "Nearby people can read this request. Their phones can pass it along." else "This request will be saved only on your phone.")
                    Text(if (draft.cloudConsent) "Online help coordination: allowed" else "Online help coordination: off", style = MaterialTheme.typography.bodySmall)
                    TextButton({ sharing = !sharing }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sharing settings") }
                    if (sharing) {
                        ConsentChoice("Share with nearby phones", "Nearby people can read your request and pass it to more phones.", draft.publicSharing, !busy) { update(draft.copy(publicSharing = it)) }
                        ConsentChoice("Allow online help coordination", "Allow a phone with internet to save your request online. Saving online does not mean help has been arranged.", draft.cloudConsent, !busy) { update(draft.copy(cloudConsent = it)) }
                        ConsentChoice("Share GPS location if available", "Include a cached location up to five minutes old. If unavailable, the SOS is sent without it.", draft.locationSharing, !busy) {
                            update(draft.copy(locationSharing = it))
                            if (it) onRequestLocation?.invoke()
                        }
                        Text("We'll remember these choices for your next request.", style = MaterialTheme.typography.bodySmall)
                    }
                    (validation ?: error)?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
                    if (!canSave) Text("This is a preview. Requests can't be saved.")
                }
                Button(onClick = {
                    validation = when {
                        draft.description.isBlank() -> "Describe what help you need."
                        draft.people.isNotBlank() && (draft.people.toIntOrNull() ?: 0) !in 1..1000 -> "Check the people count in Optional details."
                        else -> null
                    }
                    if (validation == null) onSave(draft)
                }, enabled = !busy && canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sos-send")) {
                    Text(if (busy) "Saving…" else if (draft.publicSharing) "Send SOS" else "Save on this phone")
                }
                TextButton({ onClose(draft) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ConsentChoice(label: String, explanation: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
