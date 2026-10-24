package org.ghost.gateway

import kotlinx.coroutines.flow.map
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.domain.repository.CloudCoordinationRepository

class UploadCoordinationState(private val repository: LocalSosRepository) : CloudCoordinationRepository {
    override fun observeState() = repository.observeRequests().map { reports ->
        val latest = reports.firstOrNull { it.createdHere }
        CloudCoordinationState(when (latest?.cloudState) {
            LocalCloudState.ACCEPTED -> CloudCoordinationStatus.UPLOADED
            LocalCloudState.QUEUED -> CloudCoordinationStatus.WAITING_FOR_GATEWAY
            LocalCloudState.REJECTED -> CloudCoordinationStatus.FAILED
            else -> CloudCoordinationStatus.NOT_CONNECTED
        }, requestId = latest?.requestId, message = when (latest?.cloudState) {
            LocalCloudState.ACCEPTED -> "Server storage is confirmed. Assistance coordination is not implemented yet."
            LocalCloudState.QUEUED -> "This SOS is waiting for a configured online gateway."
            LocalCloudState.REJECTED -> "Server rejected this upload. Check gateway setup and retry."
            else -> "No server acknowledgement for this phone's SOS. Assistance coordination is not implemented yet."
        })
    }
}
