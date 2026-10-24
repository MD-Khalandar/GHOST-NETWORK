package org.ghost.domain.repository

import kotlinx.coroutines.flow.Flow
import org.ghost.domain.model.CloudCoordinationState
import org.ghost.domain.model.DashboardSosSummary
import org.ghost.domain.model.HelperProfilePublishPayload
import org.ghost.domain.model.OfferPublishPayload
import org.ghost.domain.model.OfferSummary
import org.ghost.domain.model.RequestId
import org.ghost.domain.model.SosCreatePayload

data class LocalPublishResult(
    val eventId: String,
    val queued: Boolean,
)

/** Room-backed local SOS boundary. Implemented by B after CP01. */
interface SosRepository {
    fun observeRequests(): Flow<List<DashboardSosSummary>>

    suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult>
}

/** Room-backed helper/offer boundary. Owner authorization remains server/domain policy. */
interface HelperOfferRepository {
    fun observeOffers(requestId: RequestId? = null): Flow<List<OfferSummary>>

    suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult>

    suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult>
}

/** Read-only projection for the phone Mission surface. */
interface CloudCoordinationRepository {
    fun observeState(): Flow<CloudCoordinationState>
}
