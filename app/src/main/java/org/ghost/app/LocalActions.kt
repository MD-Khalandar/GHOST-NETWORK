package org.ghost.app

import org.ghost.domain.model.SosDraft
import org.ghost.transport.nearby.NearbyState
import org.ghost.gateway.GatewayUiState
import org.ghost.mesh.TopologySnapshot
import org.ghost.domain.model.ResourceDraft
import org.ghost.domain.model.ResourceSummary
import org.ghost.domain.model.SosState

data class LocalUiState(
    val draft: SosDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val exchangeStatus: String? = null,
    val network: NearbyState = NearbyState(),
    val peerIdentities: Map<String, String> = emptyMap(),
    val gateway: GatewayUiState = GatewayUiState(),
    val topology: TopologySnapshot = TopologySnapshot(),
    val resources: List<ResourceSummary> = emptyList(),
    val resourcesLoaded: Boolean = false,
    val assistance: List<org.ghost.data.local.AssistanceSummary> = emptyList(),
    val displayName: String = "",
)

fun LocalUiState.meshStatus(): String = when {
    gateway.online -> "Connected to GHOST — online"
    gateway.active && gateway.automatic && peerIdentities.isNotEmpty() -> "Connected nearby — waiting for the server"
    network.starting -> "Connecting to GHOST…"
    !network.active -> "Not connected to GHOST"
    peerIdentities.isEmpty() -> "Looking for nearby phones…"
    peerIdentities.size == 1 -> "Connected to 1 phone"
    else -> "Connected to ${peerIdentities.size} phones"
}

data class LocalActions(
    val updateDraft: (SosDraft) -> Unit,
    val saveDraft: (SosDraft, () -> Unit) -> Unit,
    val publish: (SosDraft, () -> Unit) -> Unit,
    val startNearby: () -> Unit,
    val stopNearby: () -> Unit,
    val retrySends: () -> Unit,
    val saveGateway: ((String, String, () -> Unit) -> Unit)? = null,
    val clearGateway: (() -> Unit)? = null,
    val publishResource: ((ResourceDraft, () -> Unit) -> Unit)? = null,
    val withdrawResource: ((ResourceDraft, () -> Unit) -> Unit)? = null,
    val decideAssistance: ((String, Int, Int) -> Unit)? = null,
    val requestLocation: (() -> Unit)? = null,
    val closeSos: ((String, SosState) -> Unit)? = null,
    val saveDisplayName: ((String) -> Unit)? = null,
)
