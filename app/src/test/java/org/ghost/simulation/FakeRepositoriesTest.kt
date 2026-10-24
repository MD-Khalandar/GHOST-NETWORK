package org.ghost.simulation

import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.ghost.domain.model.*
import org.ghost.gateway.api.GatewayReachability
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeRepositoriesTest {
    @Test
    fun tenConcurrentSosRetriesProduceOneQueuedEventAndOriginalTimestamps() = runTest {
        val simulation = Cp01Simulation()
        val repository = simulation.sosRepository
        val observed = mutableListOf<List<DashboardSosSummary>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeRequests().collect { rows ->
                // This is fake ordering evidence, not a durable transaction test.
                if (rows.isNotEmpty()) assertEquals(rows.size, repository.pending.value.size)
                observed.add(rows)
            }
        }
        val results = (1..10).map { async { repository.publish(Cp01Fixtures.sos()).getOrThrow() } }.awaitAll()
        assertEquals(1, results.distinct().size)
        assertTrue(results.all { it.queued })
        assertEquals(results.first().eventId, UUID.fromString(results.first().eventId).toString())
        simulation.clock.advanceBy(1_000)
        assertEquals(results.first(), repository.publish(Cp01Fixtures.sos()).getOrThrow())
        val row = repository.observeRequests().first().single()
        assertEquals(Cp01Fixtures.REPORTED_AT_MS, row.reportedAtMs)
        assertEquals(row.reportedAtMs, row.receivedAtMs)
        assertEquals(SosState.OPEN, row.state)
        assertEquals(1, repository.pending.value.size)
        assertEquals(listOf(0, 1), observed.map { it.size })
        assertTrue(simulation.transport.attempts.value.isEmpty())
        assertTrue(simulation.cloudClient.uploadCalls.value.isEmpty())
    }

    @Test
    fun conflictingSosCreateDoesNotReplaceQueuedContentOrVisibleProjection() = runTest {
        val repo = FakeSosRepository(FakeClock())
        val result = repo.publish(Cp01Fixtures.sos()).getOrThrow()
        val before = repo.observeRequests().first()
        assertTrue(repo.publish(Cp01Fixtures.sos().copy(description = "Different content")).isFailure)
        assertEquals(before, repo.observeRequests().first())
        assertEquals(result.eventId, repo.pending.value.single().eventId)
        assertEquals(Cp01Fixtures.sos(), repo.pending.value.single().payload)
    }

    @Test
    fun sameActionsGiveSameOrderedQueuesAndStateAcrossFreshInstances() = runTest {
        suspend fun scenario(): Cp01Simulation {
            val simulation = Cp01Simulation()
            simulation.seedLocalReports()
            simulation.clock.advanceBy(5_000)
            simulation.sosRepository.publish(Cp01Fixtures.sos().copy(
                requestId = RequestId("88888888-8888-4888-8888-888888888888"),
                category = SosCategory.FOOD,
            )).getOrThrow()
            return simulation
        }
        val first = scenario()
        val second = scenario()
        assertEquals(first.sosRepository.pending.value, second.sosRepository.pending.value)
        assertEquals(first.sosRepository.observeRequests().first(), second.sosRepository.observeRequests().first())
        assertEquals(first.helperOfferRepository.pendingHelpers.value, second.helperOfferRepository.pendingHelpers.value)
        assertEquals(first.helperOfferRepository.observeOffers().first(), second.helperOfferRepository.observeOffers().first())
        assertEquals(listOf(Cp01Fixtures.REPORTED_AT_MS, Cp01Fixtures.REPORTED_AT_MS + 5_000),
            first.sosRepository.pending.value.map { it.queuedAtMs })
    }

    @Test
    fun offerCanArriveBeforeProfileWithoutInventingHelperDataOrAllocation() = runTest {
        val repo = FakeHelperOfferRepository(FakeClock())
        val result = repo.publishOffer(Cp01Fixtures.offer()).getOrThrow()
        assertTrue(result.queued)
        assertTrue(repo.pendingHelpers.value.isEmpty())
        val before = repo.observeOffers().first().single()
        assertEquals(Cp01Fixtures.helperOwner, before.ownerNodeId)
        assertEquals(OfferState.AVAILABLE, before.state)
        assertEquals(20, before.quantity)
        repo.publishHelper(Cp01Fixtures.helper()).getOrThrow()
        assertEquals(before, repo.observeOffers().first().single())
    }

    @Test
    fun duplicateOffersAndHelpersKeepOriginalQueueEntriesAndRejectConflicts() = runTest {
        val clock = FakeClock()
        val repo = FakeHelperOfferRepository(clock)
        val helper = repo.publishHelper(Cp01Fixtures.helper()).getOrThrow()
        val offer = repo.publishOffer(Cp01Fixtures.offer()).getOrThrow()
        clock.advanceBy(100)
        repeat(10) {
            assertEquals(helper, repo.publishHelper(Cp01Fixtures.helper()).getOrThrow())
            assertEquals(offer, repo.publishOffer(Cp01Fixtures.offer()).getOrThrow())
        }
        assertTrue(repo.publishHelper(Cp01Fixtures.helper().copy(displayName = "Changed")).isFailure)
        assertTrue(repo.publishOffer(Cp01Fixtures.offer().copy(quantity = 99)).isFailure)
        assertTrue(repo.publishOffer(Cp01Fixtures.offer().copy(ownerNodeId = Cp01Fixtures.requester)).isFailure)
        assertEquals(1, repo.pendingHelpers.value.size)
        assertEquals(1, repo.pendingOffers.value.size)
        assertEquals(Cp01Fixtures.REPORTED_AT_MS, repo.pendingOffers.value.single().queuedAtMs)
    }

    @Test
    fun helperQueueCopiesCallerOwnedLists() = runTest {
        val repo = FakeHelperOfferRepository(FakeClock())
        val helpTypes = mutableListOf(HelpType.WATER)
        repo.publishHelper(Cp01Fixtures.helper().copy(helpTypes = helpTypes)).getOrThrow()
        helpTypes.add(HelpType.TRANSPORT)
        assertEquals(listOf(HelpType.WATER), repo.pendingHelpers.value.single().payload.helpTypes)
    }

    @Test
    fun offerFilterUsesOnlyExplicitFixtureLinksAndExpiryDoesNotRenewPublication() = runTest {
        val clock = FakeClock()
        val repo = FakeHelperOfferRepository(clock)
        val requestId = Cp01Fixtures.sos().requestId
        val offer = Cp01Fixtures.offer()
        repo.publishOffer(offer).getOrThrow()
        assertTrue(repo.observeOffers(requestId).first().isEmpty())
        repo.setOffersForRequest(requestId, listOf(offer.offerId))
        assertEquals(listOf(offer.offerId), repo.observeOffers(requestId).first().map { it.offerId })
        assertTrue(repo.observeOffers(RequestId("unknown-request")).first().isEmpty())
        val observed = mutableListOf<OfferState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeOffers(requestId).collect { observed.add(it.single().state) }
        }
        clock.advanceBy(checkNotNull(offer.availableUntilMs) - clock.nowMs - 1)
        assertEquals(OfferState.AVAILABLE, repo.observeOffers().first().single().state)
        clock.advanceBy(1)
        val expired = repo.observeOffers().first().single()
        assertEquals(OfferState.EXPIRED, expired.state)
        assertTrue(expired.stale)
        assertEquals(Cp01Fixtures.REPORTED_AT_MS, expired.updatedAtMs)
        assertEquals(offer.availableUntilMs, expired.availableUntilMs)
        assertEquals(offer, repo.pendingOffers.value.single().payload)
        assertEquals(listOf(OfferState.AVAILABLE, OfferState.EXPIRED), observed)
    }

    @Test
    fun publicReportsWithoutCloudConsentStayLocalAndKeepConsent() = runTest {
        val simulation = Cp01Simulation()
        val consent = Consent(publicMeshAllowed = true, cloudProcessingAllowed = false)
        simulation.sosRepository.publish(Cp01Fixtures.sos().copy(consent = consent)).getOrThrow()
        simulation.helperOfferRepository.publishOffer(Cp01Fixtures.offer().copy(consent = consent)).getOrThrow()
        assertEquals(consent, simulation.sosRepository.pending.value.single().payload.consent)
        assertEquals(consent, simulation.helperOfferRepository.pendingOffers.value.single().payload.consent)
        assertTrue(simulation.cloudClient.uploadCalls.value.isEmpty())
        assertEquals(CloudCoordinationStatus.NOT_CONNECTED, simulation.cloudCoordinationRepository.observeState().value.status)
    }

    @Test
    fun freshInstancesAreEmptyAndIsolatedAndSeedingIsRepeatable() = runTest {
        val first = Cp01Simulation()
        first.seedLocalReports()
        first.seedLocalReports()
        first.clock.advanceBy(10)
        val second = Cp01Simulation()
        assertEquals(1, first.sosRepository.pending.value.size)
        assertEquals(1, first.helperOfferRepository.pendingOffers.value.size)
        assertTrue(second.sosRepository.observeRequests().first().isEmpty())
        assertTrue(second.helperOfferRepository.observeOffers().first().isEmpty())
        assertEquals(Cp01Fixtures.REPORTED_AT_MS, second.clock.nowMs)
        assertTrue(first.transport.peers.value.isEmpty())
        assertEquals(GatewayReachability.OFFLINE, first.cloudClient.reachability.value)
    }

    @Test
    fun missionFixtureUpdatesAreExplicitAndAlwaysLabelledSimulated() {
        val repo = FakeCloudCoordinationRepository()
        assertEquals(CloudCoordinationStatus.NOT_CONNECTED, repo.observeState().value.status)
        repo.setState(CloudCoordinationState(CloudCoordinationStatus.AWAITING_OPERATOR, message = "Manual preview"))
        assertEquals("Simulated: Manual preview", repo.observeState().value.message)
        assertEquals(CloudCoordinationStatus.AWAITING_OPERATOR, repo.observeState().value.status)
    }
}
