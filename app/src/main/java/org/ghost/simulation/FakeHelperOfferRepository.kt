package org.ghost.simulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghost.domain.model.*
import org.ghost.domain.repository.HelperOfferRepository
import org.ghost.domain.repository.LocalPublishResult

/** Independent fixture queues. Does not allocate resources, sign, or authorize owner actions. */
class FakeHelperOfferRepository(private val clock: FakeClock) : HelperOfferRepository {
    private val mutex = Mutex()
    private val mutableHelpers = MutableStateFlow<List<FakePublication<HelperProfilePublishPayload>>>(emptyList())
    private val mutableOffers = MutableStateFlow<List<FakePublication<OfferPublishPayload>>>(emptyList())
    private val associations = MutableStateFlow<Map<RequestId, List<OfferId>>>(emptyMap())
    val pendingHelpers = mutableHelpers.asStateFlow()
    val pendingOffers = mutableOffers.asStateFlow()

    override fun observeOffers(requestId: RequestId?) = combine(
        pendingOffers, clock.time, associations,
    ) { publications, now, links ->
        publications.filter { requestId == null || it.payload.offerId in links[requestId].orEmpty() }
            .map { publication ->
                val payload = publication.payload
                val expired = payload.availableUntilMs?.let { now >= it } ?: false
                OfferSummary(
                    offerId = payload.offerId,
                    ownerNodeId = payload.ownerNodeId,
                    resourceType = payload.resourceType,
                    quantity = payload.quantity,
                    unit = payload.unit,
                    coarseArea = payload.coarseArea,
                    availableUntilMs = payload.availableUntilMs,
                    state = if (expired) OfferState.EXPIRED else OfferState.AVAILABLE,
                    updatedAtMs = publication.queuedAtMs,
                    stale = expired,
                )
            }.snapshot()
    }.distinctUntilChanged()

    /** Explicit preview associations only. No matching engine or inferred allocation. */
    suspend fun setOffersForRequest(requestId: RequestId, offerIds: List<OfferId>) = mutex.withLock {
        associations.value = associations.value + (requestId to offerIds.distinct().snapshot())
    }

    override suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult> = mutex.withLock {
        val copy = payload.copy(helpTypes = payload.helpTypes.snapshot())
        val existing = pendingHelpers.value.find { it.payload.ownerNodeId == copy.ownerNodeId }
        if (existing != null) {
            return@withLock duplicateResult(existing, copy)
        }
        val eventId = fixtureEventId("helper", copy.ownerNodeId.value, copy.ownerNodeId.value)
        mutableHelpers.value = (pendingHelpers.value + FakePublication(eventId, copy, clock.nowMs)).snapshot()
        Result.success(LocalPublishResult(eventId, queued = true))
    }

    override suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult> = mutex.withLock {
        val existing = pendingOffers.value.find { it.payload.offerId == payload.offerId }
        if (existing != null) {
            return@withLock duplicateResult(existing, payload)
        }
        val eventId = fixtureEventId("offer", payload.ownerNodeId.value, payload.offerId.value)
        mutableOffers.value = (pendingOffers.value + FakePublication(eventId, payload, clock.nowMs)).snapshot()
        Result.success(LocalPublishResult(eventId, queued = true))
    }

    private fun <T> duplicateResult(existing: FakePublication<T>, payload: T): Result<LocalPublishResult> =
        if (existing.payload == payload) {
            Result.success(LocalPublishResult(existing.eventId, queued = true))
        } else {
            Result.failure(IllegalArgumentException("Conflicting simulated publication for the same ID"))
        }
}
