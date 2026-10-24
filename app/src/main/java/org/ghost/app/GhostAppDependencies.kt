package org.ghost.app

import org.ghost.domain.repository.CloudCoordinationRepository
import org.ghost.domain.repository.HelperOfferRepository
import org.ghost.domain.repository.SosRepository
import org.ghost.gateway.api.GatewayClient
import org.ghost.transport.api.GhostTransport

/**
 * Composition seam owned by A. B supplies core implementations, C supplies
 * the server behind the gateway contract, and D consumes the repositories from
 * feature ViewModels. The activity should receive this graph from a factory,
 * not construct SDK clients inside a composable.
 */
data class GhostAppDependencies(
    val transport: GhostTransport,
    val gatewayClient: GatewayClient,
    val sosRepository: SosRepository,
    val helperOfferRepository: HelperOfferRepository,
    val cloudCoordinationRepository: CloudCoordinationRepository,
)

fun interface GhostAppDependenciesFactory {
    fun create(): GhostAppDependencies
}
