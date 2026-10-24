package org.ghost.simulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghost.domain.model.*
import org.ghost.domain.repository.LocalPublishResult
import org.ghost.domain.repository.SosRepository

/** Memory-only demo publication. It never transmits or marks cloud acceptance. */
class FakeSosRepository(
    private val clock: FakeClock,
    private val sourceNodeId: NodeId = DemoData.requester,
) : SosRepository {
    private val mutex = Mutex()
    private val mutablePending = MutableStateFlow<List<FakePublication<SosCreatePayload>>>(emptyList())
    private val mutableRequests = MutableStateFlow<List<DashboardSosSummary>>(emptyList())
    val pending = mutablePending.asStateFlow()

    override fun observeRequests() = mutableRequests.asStateFlow()

    override suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult> = mutex.withLock {
        val existing = pending.value.find { it.payload.requestId == payload.requestId }
        if (existing != null) {
            return@withLock if (existing.payload == payload) {
                Result.success(LocalPublishResult(existing.eventId, queued = true))
            } else {
                Result.failure(IllegalArgumentException("Conflicting simulated SOS create for the same ID"))
            }
        }
        val eventId = fixtureEventId("sos", sourceNodeId.value, payload.requestId.value)
        val now = clock.nowMs
        mutablePending.value = (pending.value + FakePublication(eventId, payload, now)).snapshot()
        mutableRequests.value = (mutableRequests.value + DashboardSosSummary(
            requestId = payload.requestId,
            category = payload.category,
            state = SosState.OPEN,
            description = payload.description,
            peopleCount = payload.peopleCount,
            coarseArea = payload.coarseArea,
            sourceNodeId = sourceNodeId,
            reportedAtMs = now,
            // A's shared read type requires this field. Here it is LOCAL fixture receipt time.
            receivedAtMs = now,
            stale = false,
        )).snapshot()
        Result.success(LocalPublishResult(eventId, queued = true))
    }
}
