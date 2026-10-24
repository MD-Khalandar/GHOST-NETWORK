package org.ghost.domain.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ResourceDraft(val id: String = UUID.randomUUID().toString(), val supply: Boolean = false,
    val name: String = "", val area: String = "", val helpType: HelpType = HelpType.WATER,
    val available: Boolean = true, val resourceType: ResourceType = ResourceType.WATER,
    val quantity: String = "", val unit: String = "", val publicSharing: Boolean = true, val cloudConsent: Boolean = false)

data class ResourceSummary(val id: String, val owner: String, val owned: Boolean, val helper: Boolean,
    val title: String, val area: String?, val available: Boolean, val expiresAt: Long, val cloudState: LocalCloudState)

data class UploadCandidate(val eventId: String, val packetId: String, val wire: ByteArray)
