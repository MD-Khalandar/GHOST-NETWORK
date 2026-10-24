package org.ghost.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.ghost.domain.model.*
import org.ghost.domain.repository.*
import org.ghost.gateway.api.*

/** Explicitly unavailable services, not simulated successes. */
object UnavailableOffers : HelperOfferRepository {
    override fun observeOffers(requestId: RequestId?) = flowOf(emptyList<OfferSummary>())
    override suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult> = Result.failure(UnsupportedOperationException("Helper registration is not implemented"))
    override suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult> = Result.failure(UnsupportedOperationException("Offer publication is not implemented"))
}
object UnavailableCoordination : CloudCoordinationRepository {
    override fun observeState() = flowOf(CloudCoordinationState(CloudCoordinationStatus.NOT_CONNECTED, message = "Cloud synchronization and coordination are not implemented yet."))
}
object UnavailableGateway : GatewayClient {
    override val reachability = MutableStateFlow(GatewayReachability.OFFLINE)
    override suspend fun upload(request: SyncUploadRequest): Result<SyncUploadResponse> = Result.failure(UnsupportedOperationException("Cloud sync is not implemented"))
    override suspend fun download(cursor: String?, limit: Int): Result<SyncDownloadResponse> = Result.failure(UnsupportedOperationException("Cloud sync is not implemented"))
    override suspend fun acknowledgeDownload(request: SyncDownloadAckRequest): Result<Unit> = Result.failure(UnsupportedOperationException("Cloud sync is not implemented"))
}
