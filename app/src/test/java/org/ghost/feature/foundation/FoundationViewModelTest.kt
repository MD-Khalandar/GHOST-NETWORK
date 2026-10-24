package org.ghost.feature.foundation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.ghost.domain.model.*
import org.ghost.domain.repository.*
import org.ghost.gateway.api.*
import org.ghost.transport.api.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationViewModelTest {
    @Test
    fun collectionReadsBoundariesWithoutPublishingStartingTransportOrUploading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sources = Sources()
        val vm = sources.viewModel()
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            assertTrue(vm.state.value.loading)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(FoundationPreviewFixtures.loaded.requests, vm.state.value.requests)
            assertEquals(GatewayReachability.OFFLINE, vm.state.value.cloud)
            assertEquals(0, sources.commands)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun reachabilityAndPeerChangesNeverPromoteMissionOrRequestDelivery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sources = Sources()
        val vm = sources.viewModel()
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            runCurrent()
            sources.cloud.value = GatewayReachability.ONLINE
            sources.peerList.value = listOf(PeerHandle("simulation", "B"))
            runCurrent()
            assertEquals(GatewayReachability.ONLINE, vm.state.value.cloud)
            assertEquals(1, vm.state.value.peers.size)
            assertEquals(CloudCoordinationStatus.NOT_CONNECTED, vm.state.value.mission.status)
            assertEquals(SosState.OPEN, vm.state.value.requests.single().state)
            sources.mission.value = CloudCoordinationState(CloudCoordinationStatus.AWAITING_OPERATOR, version = 2)
            runCurrent()
            assertEquals(2, vm.state.value.mission.version)
            assertEquals(0, sources.commands)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun failedReadHasAnExplicitRetryAndRecoversWithoutCommands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sources = Sources().apply { failRead = true }
        val vm = sources.viewModel()
        val store = ViewModelStore().apply { put("test", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
            runCurrent()
            assertTrue(vm.state.value.failed)
            sources.failRead = false
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.failed)
            assertFalse(vm.state.value.loading)
            assertEquals(1, vm.state.value.requests.size)
            assertEquals(0, sources.commands)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test
    fun allCloudStagesAndReachabilityFailuresHaveDistinctLabels() {
        assertEquals(CloudCoordinationStatus.entries.size, CloudCoordinationStatus.entries.map { it.label() }.distinct().size)
        assertEquals(GatewayReachability.entries.size, GatewayReachability.entries.map { it.label() }.distinct().size)
    }

    private class Sources {
        var commands = 0
        var failRead = false
        val requests = MutableStateFlow(FoundationPreviewFixtures.loaded.requests)
        val mission = MutableStateFlow(CloudCoordinationState(CloudCoordinationStatus.NOT_CONNECTED))
        val peerList = MutableStateFlow<List<PeerHandle>>(emptyList())
        val cloud = MutableStateFlow(GatewayReachability.OFFLINE)
        private fun command(): Nothing { commands++; error("UI must not issue I/O commands") }
        fun viewModel() = FoundationViewModel(
            object : SosRepository {
                override fun observeRequests() = flow { if (failRead) error("Read failed"); emitAll(requests) }
                override suspend fun publish(payload: SosCreatePayload): Result<LocalPublishResult> = command()
            },
            object : HelperOfferRepository {
                override fun observeOffers(requestId: RequestId?) = flowOf(FoundationPreviewFixtures.loaded.offers)
                override suspend fun publishHelper(payload: HelperProfilePublishPayload): Result<LocalPublishResult> = command()
                override suspend fun publishOffer(payload: OfferPublishPayload): Result<LocalPublishResult> = command()
            },
            object : CloudCoordinationRepository { override fun observeState() = mission },
            object : GhostTransport {
                override val id = "fixture"
                override val events = emptyFlow<TransportEvent>()
                override val peers = peerList
                override suspend fun start(): Result<Unit> = command()
                override suspend fun stop(): Unit = command()
                override suspend fun send(peer: PeerHandle, packet: WirePacket): SendResult = command()
            },
            object : GatewayClient {
                override val reachability = cloud
                override suspend fun upload(request: SyncUploadRequest): Result<SyncUploadResponse> = command()
                override suspend fun download(cursor: String?, limit: Int): Result<SyncDownloadResponse> = command()
                override suspend fun acknowledgeDownload(request: SyncDownloadAckRequest): Result<Unit> = command()
            },
        )
    }
}
