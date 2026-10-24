package org.ghost.simulation

/** In-memory components for the non-sending app preview. */
class DemoEnvironment(val clock: FakeClock = FakeClock()) {
    val label = "Simulated demo data — memory only"
    val transport = FakeTransport(clock)
    val cloudClient = FakeCloudClient(clock)
    val sosRepository = FakeSosRepository(clock)
    val helperOfferRepository = FakeHelperOfferRepository(clock)
    val cloudCoordinationRepository = FakeCloudCoordinationRepository()

    /** Optional, repeatable local fixtures. Does not connect peers or perform a cloud upload. */
    suspend fun seedLocalReports() {
        sosRepository.publish(DemoData.sos()).getOrThrow()
        helperOfferRepository.publishHelper(DemoData.helper()).getOrThrow()
        helperOfferRepository.publishOffer(DemoData.offer()).getOrThrow()
        helperOfferRepository.setOffersForRequest(DemoData.sos().requestId, listOf(DemoData.offer().offerId))
    }
}
