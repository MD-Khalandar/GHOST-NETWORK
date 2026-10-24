package org.ghost.app

import kotlinx.coroutines.flow.flowOf
import org.ghost.domain.model.*
import org.ghost.domain.repository.*

/** Explicitly unavailable services, not simulated successes. */
object UnavailableOffers : HelperOfferRepository {
    override fun observeOffers(requestId: RequestId?) = flowOf(emptyList<OfferSummary>())
    override suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult> = Result.failure(UnsupportedOperationException("Helper registration is not implemented"))
    override suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult> = Result.failure(UnsupportedOperationException("Offer publication is not implemented"))
}
