package org.ghost.app

import org.ghost.domain.repository.CloudCoordinationRepository
import org.ghost.domain.repository.HelperOfferRepository
import org.ghost.domain.repository.SosRepository
import org.ghost.gateway.api.GatewayClient
import org.ghost.transport.api.GhostTransport

/** App dependencies stay outside composables. */
data class GhostAppDependencies(
    val transport: GhostTransport,
    val gatewayClient: GatewayClient,
    val sosRepository: SosRepository,
    val helperOfferRepository: HelperOfferRepository,
    val cloudCoordinationRepository: CloudCoordinationRepository,
)
