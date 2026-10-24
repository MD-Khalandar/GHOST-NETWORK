package org.ghost.simulation

/** Fresh, isolated in-memory components. A owns their injection into GhostAppDependencies. */
class Cp01Simulation(val clock: FakeClock = FakeClock()) {
    val label = "Simulated CP01 data — memory only"
    val transport = FakeTransport(clock)
    val cloudClient = FakeCloudClient(clock)
    val sosRepository = FakeSosRepository(clock)
    val helperOfferRepository = FakeHelperOfferRepository(clock)
    val cloudCoordinationRepository = FakeCloudCoordinationRepository()

    /** Optional, repeatable local fixtures. Does not connect peers or perform a cloud upload. */
    suspend fun seedLocalReports() {
        sosRepository.publish(Cp01Fixtures.sos()).getOrThrow()
        helperOfferRepository.publishHelper(Cp01Fixtures.helper()).getOrThrow()
        helperOfferRepository.publishOffer(Cp01Fixtures.offer()).getOrThrow()
        helperOfferRepository.setOffersForRequest(Cp01Fixtures.sos().requestId, listOf(Cp01Fixtures.offer().offerId))
    }
}
