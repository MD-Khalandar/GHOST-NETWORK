package org.ghost.simulation

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.ghost.domain.model.*
import org.ghost.gateway.api.GatewayReachability
import org.junit.Assert.*
import org.junit.Test

class FakeCloudClientTest {
    private fun upload(key: String = "gateway-batch-0001") = SyncUploadRequest(
        key, listOf(Cp01Fixtures.packet()), listOf(Cp01Fixtures.receipt()),
    )

    private fun response() = SyncUploadResponse(listOf(SyncUploadItemResult(
        EventId("33333333-3333-4333-8333-333333333333"), CloudAckStatus.ACCEPTED, cloudRevision = 42,
    )))

    @Test
    fun offlineAndAuthenticationFailuresPreserveScriptUntilReconnect() = runTest {
        val clock = FakeClock()
        val cloud = FakeCloudClient(clock)
        cloud.enqueueUploadResult(upload(), Result.success(response()))
        assertEquals(GatewayReachability.OFFLINE, cloud.reachability.value)
        assertTrue(cloud.upload(upload()).isFailure)
        cloud.setReachability(GatewayReachability.AUTHENTICATION_FAILED)
        assertTrue(cloud.upload(upload()).isFailure)
        cloud.setReachability(GatewayReachability.UNKNOWN)
        assertTrue(cloud.upload(upload()).isFailure)
        cloud.setReachability(GatewayReachability.ONLINE)
        clock.advanceBy(300)
        assertEquals(response(), cloud.upload(upload()).getOrThrow())
        assertEquals(4, cloud.uploadCalls.value.size)
        assertEquals(clock.nowMs, cloud.uploadCalls.value.last().attemptedAtMs)
        assertTrue(cloud.uploadCalls.value.all { it.request.packets.single() == Cp01Fixtures.packet() })
    }

    @Test
    fun failuresRetryInScriptOrderAndUnscriptedOnlineRequestsNeverClaimSuccess() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        cloud.setReachability(GatewayReachability.ONLINE)
        assertTrue(cloud.upload(upload()).isFailure)
        cloud.enqueueUploadResult(upload(), Result.failure(IllegalStateException("Simulated timeout")))
        cloud.enqueueUploadResult(upload(), Result.success(response()))
        assertTrue(cloud.upload(upload("unexpected-key")).isFailure)
        assertEquals("Simulated timeout", cloud.upload(upload()).exceptionOrNull()?.message)
        assertEquals(response(), cloud.upload(upload()).getOrThrow())
    }

    @Test
    fun sameBatchIsIdempotentButAKeyCannotBeReusedForDifferentBytes() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        cloud.setReachability(GatewayReachability.ONLINE)
        cloud.enqueueUploadResult(upload(), Result.success(response()))
        repeat(10) { assertEquals(response(), cloud.upload(upload()).getOrThrow()) }
        val changed = upload().copy(packets = listOf(Cp01Fixtures.packet().copy(signature = "changed")))
        assertTrue(cloud.upload(changed).isFailure)
        assertTrue(cloud.upload(upload().copy(receipts = emptyList())).isFailure)
        assertEquals(response(), cloud.upload(upload()).getOrThrow())
        assertTrue(cloud.upload(upload("different-batch")).isFailure)
    }

    @Test
    fun uploadSnapshotsProtectScriptsHistoryAndRetryCacheFromCallerListChanges() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        val packets = mutableListOf(Cp01Fixtures.packet())
        val receipts = mutableListOf(Cp01Fixtures.receipt())
        val request = upload().copy(packets = packets, receipts = receipts)
        val results = response().results.toMutableList()
        cloud.enqueueUploadResult(request, Result.success(SyncUploadResponse(results)))
        results.clear()
        cloud.setReachability(GatewayReachability.ONLINE)
        assertEquals(response(), cloud.upload(request).getOrThrow())
        packets.clear()
        receipts.clear()
        assertEquals(upload(), cloud.uploadCalls.value.single().request)
        assertEquals(response(), cloud.upload(upload()).getOrThrow())
    }

    @Test
    fun downloadReplayAndAckRetriesAreSeparateAndDoNotAdvanceCursorsAutomatically() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        val page = SyncDownloadResponse("", "fixture-cursor-1", listOf(Cp01Fixtures.packet()))
        val ack = SyncDownloadAckRequest(page.nextCursor, listOf(Cp01Fixtures.receipt().packetId))
        cloud.enqueueDownloadResult(null, 1, Result.success(page))
        cloud.enqueueAcknowledgementResult(ack, Result.failure(IllegalStateException("Simulated timeout")))
        cloud.enqueueAcknowledgementResult(ack, Result.success(Unit))
        assertTrue(cloud.download(null, 1).isFailure)
        assertTrue(cloud.acknowledgeDownload(ack).isFailure)
        cloud.setReachability(GatewayReachability.ONLINE)
        assertEquals(page, cloud.download(null, 1).getOrThrow())
        assertTrue(cloud.acknowledgeDownload(ack.copy(cursor = "wrong-cursor")).isFailure)
        assertTrue(cloud.acknowledgeDownload(ack).isFailure)
        assertEquals(page, cloud.download(null, 1).getOrThrow())
        assertTrue(cloud.acknowledgeDownload(ack).isSuccess)
        assertTrue(cloud.acknowledgeDownload(ack).isSuccess)
        assertEquals(page, cloud.download(null, 1).getOrThrow())
        assertTrue(cloud.download(page.nextCursor, 1).isFailure)
        assertEquals(listOf(null, null, null, null, page.nextCursor), cloud.downloadCalls.value.map { it.request.cursor })
    }

    @Test
    fun downloadLimitsAndUnexpectedQueriesDoNotConsumeTheExpectedPage() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        val page = SyncDownloadResponse("", "next", listOf(Cp01Fixtures.packet()))
        cloud.enqueueDownloadResult(null, 1, Result.success(page))
        cloud.setReachability(GatewayReachability.ONLINE)
        assertTrue(cloud.download(null, 0).isFailure)
        assertTrue(cloud.download(null, 101).isFailure)
        assertTrue(cloud.download("unknown-cursor", 1).isFailure)
        assertTrue(cloud.download(null, 2).isFailure)
        assertEquals(page, cloud.download(null, 1).getOrThrow())
        assertTrue(runCatching {
            cloud.enqueueDownloadResult("next", 1, Result.success(page.copy(packets = List(2) { Cp01Fixtures.packet() })))
        }.isFailure)
    }

    @Test
    fun explicitCloudSuccessLeavesLocalQueueAndMissionStateUnchanged() = runTest {
        val simulation = Cp01Simulation()
        simulation.seedLocalReports()
        val before = simulation.cloudCoordinationRepository.observeState().value
        val cloud = simulation.cloudClient
        cloud.enqueueUploadResult(upload(), Result.success(response()))
        cloud.setReachability(GatewayReachability.ONLINE)
        cloud.upload(upload()).getOrThrow()
        assertEquals(before, simulation.cloudCoordinationRepository.observeState().value)
        assertEquals(1, simulation.sosRepository.pending.value.size)
        assertEquals(SosState.OPEN, simulation.sosRepository.observeRequests().value.single().state)
        assertTrue(simulation.transport.attempts.value.isEmpty())
    }

    @Test
    fun scriptedCancellationPropagatesInsteadOfBecomingAnOrdinaryFailure() = runTest {
        val cloud = FakeCloudClient(FakeClock())
        cloud.setReachability(GatewayReachability.ONLINE)
        val cancellation = CancellationException("Simulated cancellation")
        cloud.enqueueUploadResult(upload(), Result.failure(cancellation))
        val caught = runCatching { cloud.upload(upload()) }.exceptionOrNull()
        assertSame(cancellation, caught)
        cloud.enqueueUploadResult(upload(), Result.success(response()))
        assertEquals(response(), cloud.upload(upload()).getOrThrow())
    }

    @Test
    fun separateClientsDoNotShareScriptsOrSuccessCaches() = runTest {
        val first = FakeCloudClient(FakeClock())
        val second = FakeCloudClient(FakeClock())
        first.setReachability(GatewayReachability.ONLINE)
        second.setReachability(GatewayReachability.ONLINE)
        first.enqueueUploadResult(upload(), Result.success(response()))
        first.upload(upload()).getOrThrow()
        assertTrue(second.upload(upload()).isFailure)
        assertTrue(second.download(null, 1).isFailure)
        assertTrue(second.acknowledgeDownload(SyncDownloadAckRequest("unknown", emptyList())).isFailure)
    }
}
