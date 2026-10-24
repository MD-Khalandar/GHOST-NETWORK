package org.ghost.domain.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class SosDraft(
    val requestId: String = UUID.randomUUID().toString(),
    val category: SosCategory = SosCategory.OTHER,
    val description: String = "",
    val people: String = "",
    val area: String = "",
    val locationSharing: Boolean = true,
    val publicSharing: Boolean = true,
    val cloudConsent: Boolean = true,
) {
    fun withRequiredSharing() = copy(publicSharing = true, cloudConsent = true)
}

enum class LocalDelivery { SAVED_LOCALLY, QUEUED, SENT_TO_NEIGHBOR, RECEIVED_BY_PHONE, RECEIVED_FROM_PEER, RELAYED, RELAY_LIMIT_REACHED, EXPIRED }
