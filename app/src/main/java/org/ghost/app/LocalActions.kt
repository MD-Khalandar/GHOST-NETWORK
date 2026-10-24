package org.ghost.app

import org.ghost.domain.model.SosDraft
import org.ghost.transport.nearby.NearbyState

data class LocalUiState(
    val draft: SosDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val exchangeStatus: String? = null,
    val network: NearbyState = NearbyState(),
    val peerIdentities: Map<String, String> = emptyMap(),
)

fun LocalUiState.meshStatus(): String = when {
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
)
