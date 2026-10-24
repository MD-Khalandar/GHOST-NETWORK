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

data class LocalActions(
    val updateDraft: (SosDraft) -> Unit,
    val saveDraft: (SosDraft, () -> Unit) -> Unit,
    val publish: (SosDraft, () -> Unit) -> Unit,
    val startNearby: () -> Unit,
    val stopNearby: () -> Unit,
    val connect: (String) -> Unit,
    val confirm: (String, Boolean) -> Unit,
    val disconnect: (String) -> Unit,
    val retrySends: () -> Unit,
)
