package org.ghost.feature.foundation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.ghost.domain.repository.CloudCoordinationRepository
import org.ghost.domain.repository.HelperOfferRepository
import org.ghost.domain.repository.SosRepository
import org.ghost.gateway.api.GatewayClient
import org.ghost.transport.api.GhostTransport

/** Reads supplied boundaries only. Screen collection/retry never publishes or starts I/O. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoundationViewModel(
    sos: SosRepository,
    offers: HelperOfferRepository,
    coordination: CloudCoordinationRepository,
    transport: GhostTransport,
    gateway: GatewayClient,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val state: StateFlow<FoundationState> = refresh.flatMapLatest {
        combine(sos.observeRequests(), offers.observeOffers(), coordination.observeState(), transport.peers, gateway.reachability) {
                requests, localOffers, mission, peers, cloud ->
            FoundationState(requests, localOffers, mission, peers, cloud)
        }.onStart { emit(FoundationState(loading = true)) }
            .catch { emit(FoundationState(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoundationState(loading = true))

    fun retry() { refresh.value += 1 }
}
