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
import org.ghost.app.LocalActions
import org.ghost.app.LocalUiState
import org.ghost.design.InfoCard
import org.ghost.domain.model.*
import java.util.UUID

private val ResourceSaver = Saver<ResourceDraft, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResourceFormDialog(local: LocalUiState, actions: LocalActions?, onClose: () -> Unit) {
    var draft by rememberSaveable(stateSaver = ResourceSaver) { mutableStateOf(ResourceDraft()) }
    var validation by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!local.saving) onClose() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (draft.editing) "Edit supply offer" else "Offer help", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClose, enabled = !local.saving) { Text("Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (draft.editing) "Update this offer's quantity, unit or area. Its original expiry and sharing choices stay the same."
                        else "Register your availability or supplies for the next 6 hours. Help is not assigned automatically.")
                    if (draft.editing) TextButton({ draft = ResourceDraft(); validation = null }, enabled = !local.saving) { Text("Cancel edit") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(!draft.supply, { draft = draft.copy(supply = false); validation = null }, { Text("Volunteer") }, enabled = !local.saving && !draft.editing)
                        FilterChip(draft.supply, { draft = draft.copy(supply = true); validation = null }, { Text("Supplies") }, enabled = !local.saving && !draft.editing)
                    }
                    if (!draft.supply) {
                        OutlinedTextField(draft.name, { if (it.length <= 80) draft = draft.copy(name = it) }, label = { Text("Name (optional)") }, enabled = !local.saving, modifier = Modifier.fillMaxWidth())
                        Text("What can you help with?")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HelpType.entries.forEach { type -> FilterChip(draft.helpType == type, { draft = draft.copy(helpType = type) },
                                { Text(type.name.lowercase().replace('_', ' ')) }, enabled = !local.saving && !draft.editing) }
                        }
                        ResourceChoice("Available to help", draft.available, !local.saving) { draft = draft.copy(available = it) }
                        Text("Saving a new volunteer report replaces your earlier shared availability once it reaches other phones and the server.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("What can you provide?")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResourceType.entries.forEach { type -> FilterChip(draft.resourceType == type, { draft = draft.copy(resourceType = type) },
                                { Text(type.name.lowercase().replace('_', ' ')) }, enabled = !local.saving && !draft.editing) }
                        }
                        OutlinedTextField(draft.quantity, { if (it.length <= 7) draft = draft.copy(quantity = it) }, label = { Text("Quantity (optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !local.saving, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.unit, { if (it.length <= 40) draft = draft.copy(unit = it) }, label = { Text("Unit (litres, beds, seats…)") }, enabled = !local.saving, modifier = Modifier.fillMaxWidth())
                        Text(if (draft.editing) "Save changes updates the same offer. Withdraw it when the supplies are no longer available."
                            else "Each new save creates a separate supply offer. You can edit or withdraw it below.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(draft.area, { if (it.length <= 120) draft = draft.copy(area = it) }, label = { Text("Area (optional)") }, enabled = !local.saving, modifier = Modifier.fillMaxWidth())
                    ResourceChoice("Share with nearby phones", draft.publicSharing, !local.saving && !draft.editing) { draft = draft.copy(publicSharing = it) }
                    ResourceChoice("Allow online help coordination", draft.cloudConsent, !local.saving && !draft.editing) { draft = draft.copy(cloudConsent = it) }
                    Text(if (draft.publicSharing) "Nearby phones can read and relay this information. Online sharing also needs your permission above."
                        else "Saved on this phone only. Nothing is uploaded while nearby sharing is off.", style = MaterialTheme.typography.bodySmall)
                    (validation ?: local.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    local.notice?.let { Text(it) }
                    if (actions?.publishResource == null) Text("Preview only. Saving is unavailable.")
                    if (local.resources.any { it.owned }) Text("Your latest saved help (up to 20)", style = MaterialTheme.typography.titleSmall)
                    local.resources.filter { it.owned }.take(20).forEach { resource ->
                        InfoCard(resource.title, resource.area ?: "Area not provided") {
                            Text(if (resource.withdrawn) "Withdrawn on this phone" else if (resource.available) "Reported available" else "Unavailable or expired")
                            Text(when (resource.cloudState) {
                                LocalCloudState.ACCEPTED -> "Received by server · receipt verified"
                                LocalCloudState.QUEUED -> "Saved · waiting for an online gateway"
                                LocalCloudState.NOT_SHARED -> "Not shared online"
                                LocalCloudState.REJECTED -> "Server upload rejected · retry from Connections"
                                LocalCloudState.EXPIRED -> "Sharing expired"
                            })
                            resource.editDraft?.let { editable ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({ draft = editable; validation = null }, enabled = !local.saving && actions?.publishResource != null) { Text("Edit offer") }
                                    TextButton({ actions?.withdrawResource?.invoke(editable) { draft = ResourceDraft() } },
                                        enabled = !local.saving && actions?.withdrawResource != null) { Text("Withdraw offer") }
                                }
                            }
                        }
                    }
                }
                Button(onClick = {
                    validation = if (draft.supply && draft.quantity.isNotBlank() && draft.quantity.toIntOrNull() !in 1..1000000) "Enter a positive quantity up to 1,000,000." else null
                    if (validation == null) actions?.publishResource?.invoke(draft) {
                        draft = if (draft.editing) ResourceDraft(supply = true) else draft.copy(id = UUID.randomUUID().toString())
                    }
                }, enabled = !local.saving && actions?.publishResource != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (local.saving) "Saving…" else if (draft.editing) "Save changes" else if (draft.publicSharing) "Save and share help" else "Save on this phone")
                }
            }
        }
    }
}

@Composable
private fun ResourceChoice(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
