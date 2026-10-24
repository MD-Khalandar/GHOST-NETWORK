package org.ghost.gateway.api

import kotlinx.coroutines.flow.Flow
import org.ghost.domain.model.SyncDownloadAckRequest
import org.ghost.domain.model.SyncDownloadResponse
import org.ghost.domain.model.SyncUploadRequest
import org.ghost.domain.model.SyncUploadResponse

enum class GatewayReachability {
    UNKNOWN,
    OFFLINE,
    ONLINE,
    AUTHENTICATION_FAILED,
}

/**
 * Authenticated cloud synchronization seam. It never accepts model prompts,
 * database handles, or mutable re-serialized origin events.
 */
interface GatewayClient {
    val reachability: Flow<GatewayReachability>

    suspend fun upload(request: SyncUploadRequest): Result<SyncUploadResponse>

    suspend fun download(cursor: String?, limit: Int): Result<SyncDownloadResponse>

    suspend fun acknowledgeDownload(request: SyncDownloadAckRequest): Result<Unit>
}
