package org.ghost.simulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghost.domain.model.CloudCoordinationState
import org.ghost.domain.model.CloudCoordinationStatus
import org.ghost.domain.repository.CloudCoordinationRepository

/** Only explicit fixture controls change Mission state; uploads do not invent coordination. */
class FakeCloudCoordinationRepository : CloudCoordinationRepository {
    private val state = MutableStateFlow(CloudCoordinationState(
        status = CloudCoordinationStatus.NOT_CONNECTED,
        message = "Simulated: cloud unavailable",
    ))

    override fun observeState() = state.asStateFlow()

    fun setState(value: CloudCoordinationState) {
        state.value = value.copy(message = "Simulated: ${value.message ?: value.status.name}")
    }
}
