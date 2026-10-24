package org.ghost.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ghost.design.GhostIcons
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.DeviceTrustState
import org.ghost.feature.home.HomeScreen
import org.ghost.feature.home.SosFormDialog
import org.ghost.domain.model.SosDraft
import org.ghost.feature.mission.MissionScreen
import org.ghost.feature.network.NetworkScreen
import org.ghost.feature.requests.RequestsScreen

/** Navigation only; sample request/network content remains explicitly simulated. */
@Composable
fun GhostApp(state: FoundationState, onRetry: () -> Unit, trust: DeviceTrustState = DeviceTrustState(), onRetryIdentity: (() -> Unit)? = null,
             local: LocalUiState = LocalUiState(), actions: LocalActions? = null) {
    var route by rememberSaveable { mutableStateOf(AppRoute.HOME) }
    var showSosPreview by rememberSaveable { mutableStateOf(false) }
    val savedScreens = rememberSaveableStateHolder()
    val previewDraft = remember { SosDraft() }
    BackHandler(route != AppRoute.HOME) { route = AppRoute.HOME }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("GHOST", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text(if (state.simulated) "Preview" else "Local SOS", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        bottomBar = {
            if (LocalDensity.current.fontScale >= 1.5f) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).selectableGroup()) {
                        AppRoute.entries.chunked(2).forEach { destinations ->
                            Row {
                                destinations.forEach { destination ->
                                    val selected = route == destination
                                    Surface(modifier = Modifier.weight(1f).padding(4.dp),
                                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = MaterialTheme.shapes.medium) {
                                        Box(Modifier.selectable(selected, role = Role.Tab, onClick = { route = destination })
                                            .heightIn(min = 48.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                                            Text(destination.name.lowercase().replaceFirstChar { it.titlecase() },
                                                style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                AppRoute.entries.forEach { destination ->
                    val label = when (destination) {
                        AppRoute.HOME -> "Home"
                        AppRoute.REQUESTS -> "Requests"
                        AppRoute.MISSION -> "Mission"
                        AppRoute.NETWORK -> "Network"
                    }
                    val icon = when (destination) {
                        AppRoute.HOME -> GhostIcons.Home
                        AppRoute.REQUESTS -> GhostIcons.Requests
                        AppRoute.MISSION -> GhostIcons.Mission
                        AppRoute.NETWORK -> GhostIcons.Network
                    }
                    NavigationBarItem(selected = route == destination, onClick = { route = destination },
                        icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                }
            }
            }
        },
    ) { insets ->
        savedScreens.SaveableStateProvider(route) {
            val modifier = Modifier.semantics { paneTitle = route.name.lowercase().replaceFirstChar { it.titlecase() } }
            when (route) {
                AppRoute.HOME -> HomeScreen(state, { showSosPreview = true }, { route = AppRoute.REQUESTS },
                    { route = AppRoute.NETWORK }, onRetry, modifier, insets, local)
                AppRoute.REQUESTS -> RequestsScreen(state, onRetry, modifier, insets)
                AppRoute.MISSION -> MissionScreen(state, onRetry, modifier, insets)
                AppRoute.NETWORK -> NetworkScreen(state, onRetry, modifier, insets, trust, onRetryIdentity, local, actions)
            }
        }
    }
    if (showSosPreview) {
        val draft = local.draft ?: if (actions == null) previewDraft else null
        if (draft == null) AlertDialog(onDismissRequest = { showSosPreview = false }, title = { Text("Loading SOS draft") },
            text = { Text(local.error ?: "Opening local storage…") }, confirmButton = { TextButton({ showSosPreview = false }) { Text("Close") } })
        else SosFormDialog(draft, local.saving, local.error, actions != null,
            onChange = { actions?.updateDraft?.invoke(it) },
            onSave = { value -> actions?.publish?.invoke(value) { showSosPreview = false; route = AppRoute.REQUESTS } },
            onClose = { value -> if (actions == null) showSosPreview = false else actions.saveDraft(value) { showSosPreview = false } })
    }
    local.network.pairing.firstOrNull()?.let { pairing ->
        AlertDialog(onDismissRequest = { actions?.confirm?.invoke(pairing.endpointId, false) },
            title = { Text("Confirm nearby phone") },
            text = { Text("${pairing.name}\n\nCompare this code on both phones:\n${pairing.code}\n\nAccept only if both codes match.") },
            confirmButton = { TextButton({ actions?.confirm?.invoke(pairing.endpointId, true) }) { Text("Codes match") } },
            dismissButton = { TextButton({ actions?.confirm?.invoke(pairing.endpointId, false) }) { Text("Decline") } })
    }
}
