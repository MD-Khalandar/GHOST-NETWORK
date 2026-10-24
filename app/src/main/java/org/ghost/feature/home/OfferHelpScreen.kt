package org.ghost.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import org.ghost.app.LocalActions
import org.ghost.app.LocalUiState
import org.ghost.design.GhostScreen
import org.ghost.design.InfoCard
import org.ghost.domain.model.*
import org.ghost.feature.foundation.fixtureTime

@Composable
fun OfferHelpScreen(local: LocalUiState, actions: LocalActions?, modifier: Modifier = Modifier,
                    contentPadding: PaddingValues = PaddingValues(0.dp), simulated: Boolean = false) {
    var withdrawing by remember {mutableStateOf<ResourceDraft?>(null)}
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    fun edit(draft: ResourceDraft) { editor = Json.encodeToString(draft) }
    val owned = local.resources.filter { it.owned }
    val volunteer = owned.firstOrNull { it.helper }
    val supplies = owned.filterNot { it.helper }
    val ready = local.resourcesLoaded || simulated
    val canSave = ready && !local.saving && actions?.publishResource != null

    GhostScreen("Offer help", "Manage your volunteer availability and supplies. Saving does not commit you to an assignment.",
        modifier, contentPadding, simulated) {
        local.error?.let { item("error") { InfoCard("Couldn't save help", it) } }
        local.notice?.let { item("notice") { Text(it) } }
        if (!ready) item("loading") {
            InfoCard("Opening saved help", "Your availability and supply offers will appear here.") {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            items(local.assistance.filter{it.owner},key={"assistance-${it.id}"}) { AssistanceCard(it,actions,local.saving) }
            item("volunteer") {
                InfoCard("Your volunteer availability", volunteer?.title ?: "Tell nearby people what you can help with.") {
                    if (volunteer != null) {
                        Text(volunteer.area ?: "Area not provided")
                        ResourceStatus(volunteer)
                    }
                    Button({ edit(volunteer?.editDraft ?: ResourceDraft()) }, enabled = canSave,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (volunteer == null) "Register as a volunteer" else "Update availability")
                    }
                }
            }
            item("supplies") {
                InfoCard("Your supplies", if (supplies.isEmpty()) "No supply offers saved yet." else "${supplies.size} saved offers") {
                    Button({ edit(ResourceDraft(supply = true)) }, enabled = canSave,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Add supplies") }
                }
            }
            items(supplies, key = { "supply-${it.id}" }) { supply ->
                InfoCard(supply.title, supply.area ?: "Area not provided") {
                    ResourceStatus(supply)
                    supply.editDraft?.let { draft ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ edit(draft) }, enabled = canSave) { Text("Edit offer") }
                            TextButton({ withdrawing=draft },
                                enabled = !local.saving && actions?.withdrawResource != null) { Text("Withdraw offer") }
                        }
                    }
                }
            }
        }
    }
    withdrawing?.let{draft->AlertDialog(onDismissRequest={withdrawing=null},title={Text("Withdraw this offer?")},
        text={Text("Stops new matching when the withdrawal reaches other phones/server. Existing commitments stay active; cancel them separately if you cannot fulfil them.")},
        confirmButton={TextButton({actions?.withdrawResource?.invoke(draft) {};withdrawing=null},enabled=!local.saving){Text("Withdraw offer")}},
        dismissButton={TextButton({withdrawing=null}){Text("Keep offer")}})}
    editor?.let { ResourceFormDialog(local, actions, onClose = { editor = null }, initial = Json.decodeFromString(it)) }
}

@Composable
private fun ResourceStatus(resource: ResourceSummary) {
    Text(when {
        resource.withdrawn -> "Withdrawn on this phone"
        resource.available -> "Reported available"
        else -> "Unavailable or expired"
    })
    if(resource.remainingQuantity!=null)Text("Last known available: ${resource.remainingQuantity} ? reserved: ${resource.reservedQuantity} ? used: ${resource.usedQuantity}",style=MaterialTheme.typography.bodySmall)
    Text("Availability ends ${fixtureTime(resource.expiresAt)}", style = MaterialTheme.typography.bodySmall)
    Text(when (resource.cloudState) {
        LocalCloudState.ACCEPTED -> "Received by server · receipt verified"
        LocalCloudState.QUEUED -> "Saved · waiting for an online gateway"
        LocalCloudState.NOT_SHARED -> "Not shared online"
        LocalCloudState.REJECTED -> "Server upload rejected · retry from Connections"
        LocalCloudState.EXPIRED -> "Sharing expired"
    }, style = MaterialTheme.typography.bodySmall)
    resource.withdrawalCloudState?.let { state ->
        Text(when (state) {
            LocalCloudState.ACCEPTED -> "Earlier availability withdrawal received by server; receipt verified."
            LocalCloudState.QUEUED -> "Earlier availability withdrawal saved; waiting for an online gateway."
            LocalCloudState.REJECTED -> "Earlier availability withdrawal rejected by server; retry from Connections."
            LocalCloudState.NOT_SHARED -> "Earlier nearby availability withdrawal saved."
            LocalCloudState.EXPIRED -> "Earlier availability withdrawal expired."
        } + " Nearby copies update when the withdrawal arrives or expire on their own.", style = MaterialTheme.typography.bodySmall)
    }
}
