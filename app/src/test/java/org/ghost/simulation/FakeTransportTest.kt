package org.ghost.simulation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.ghost.domain.model.PacketId
import org.ghost.transport.api.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeTransportTest {
    @Test
    fun stoppedAndNoPeerAttemptsDoNotConsumeScriptedSendResults() = runTest {
        val clock = FakeClock()
        val transport = FakeTransport(clock)
        val peer = PeerHandle(transport.id, "B")
        transport.enqueueSendResult(SendResult(SendResultCode.FAILED_RETRYABLE))
        assertEquals(SendResultCode.FAILED_RETRYABLE, transport.send(peer, Cp01Fixtures.packet()).code)
        transport.start().getOrThrow()
        assertEquals(SendResultCode.REJECTED_NO_PEER, transport.send(peer, Cp01Fixtures.packet()).code)
        transport.connect(peer)
        clock.advanceBy(50)
        assertEquals(SendResultCode.FAILED_RETRYABLE, transport.send(peer, Cp01Fixtures.packet()).code)
        assertEquals(SendResultCode.ACCEPTED_BY_TRANSPORT, transport.send(peer, Cp01Fixtures.packet()).code)
        assertEquals(Cp01Fixtures.REPORTED_AT_MS + 50, transport.attempts.value.last().attemptedAtMs)
        assertTrue(transport.attempts.value.all { it.packet == Cp01Fixtures.packet() })
    }

    @Test
    fun lifecycleHasStablePeerOrderAndNoDuplicateConnectOrDisconnectEvents() = runTest {
        val transport = FakeTransport(FakeClock())
        val events = mutableListOf<TransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { transport.events.collect { events.add(it) } }
        val b = PeerHandle(transport.id, "B")
        val c = PeerHandle(transport.id, "C")
        transport.start().getOrThrow()
        transport.start().getOrThrow()
        transport.connect(c)
        transport.connect(b)
        transport.connect(b)
        assertEquals(listOf(b, c), transport.peers.value)
        transport.disconnect(b)
        transport.disconnect(b)
        transport.stop()
        transport.stop()
        runCurrent()
        assertTrue(transport.peers.value.isEmpty())
        assertEquals(listOf(
            TransportEvent(TransportEventType.PEER_CONNECTED, c),
            TransportEvent(TransportEventType.PEER_CONNECTED, b),
            TransportEvent(TransportEventType.PEER_DISCONNECTED, b),
            TransportEvent(TransportEventType.PEER_DISCONNECTED, c),
        ), events)
    }

    @Test
    fun permissionLossClearsPeersAndRecoveryNeedsAnExplicitRestartAndConnect() = runTest {
        val transport = FakeTransport(FakeClock())
        val peer = PeerHandle(transport.id, "B")
        transport.start().getOrThrow()
        transport.connect(peer)
        transport.enqueueSendResult(SendResult(SendResultCode.REJECTED_TOO_LARGE))
        transport.setPermissionGranted(false)
        assertTrue(transport.peers.value.isEmpty())
        assertTrue(transport.start().isFailure)
        assertEquals(SendResultCode.REJECTED_NO_PERMISSION, transport.send(peer, Cp01Fixtures.packet()).code)
        transport.setPermissionGranted(true)
        assertEquals(SendResultCode.FAILED_RETRYABLE, transport.send(peer, Cp01Fixtures.packet()).code)
        transport.start().getOrThrow()
        assertEquals(SendResultCode.REJECTED_NO_PEER, transport.send(peer, Cp01Fixtures.packet()).code)
        transport.connect(peer)
        assertEquals(SendResultCode.REJECTED_TOO_LARGE, transport.send(peer, Cp01Fixtures.packet()).code)
    }

    @Test
    fun neighborSendDoesNotEmitReceiveOrMutateLocalOrCloudState() = runTest {
        val simulation = Cp01Simulation()
        simulation.seedLocalReports()
        val transport = simulation.transport
        val events = mutableListOf<TransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { transport.events.collect { events.add(it) } }
        val peer = PeerHandle(transport.id, "B")
        val before = simulation.sosRepository.observeRequests().value
        transport.start().getOrThrow()
        transport.connect(peer)
        assertEquals(SendResultCode.ACCEPTED_BY_TRANSPORT, transport.send(peer, Cp01Fixtures.packet()).code)
        runCurrent()
        assertEquals(listOf(TransportEventType.PEER_CONNECTED), events.map { it.type })
        assertEquals(before, simulation.sosRepository.observeRequests().value)
        assertEquals(1, simulation.sosRepository.pending.value.size)
        assertTrue(simulation.cloudClient.uploadCalls.value.isEmpty())
    }

    @Test
    fun incomingIdsAreOnlyEmittedWhenExplicitlyScriptedFromAConnectedPeer() = runTest {
        val transport = FakeTransport(FakeClock())
        val peer = PeerHandle(transport.id, "B")
        val packetId = PacketId("66666666-6666-4666-8666-666666666666")
        assertTrue(runCatching { transport.receive(peer, packetId) }.isFailure)
        transport.start().getOrThrow()
        assertTrue(runCatching { transport.connect(PeerHandle("another-transport", "B")) }.isFailure)
        val events = mutableListOf<TransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { transport.events.collect { events.add(it) } }
        transport.connect(peer)
        repeat(2) { transport.receive(peer, packetId) }
        runCurrent()
        // Raw duplicate callbacks are intentional; adapter fixtures do not own event deduplication.
        assertEquals(2, events.count { it.type == TransportEventType.PACKET_RECEIVED && it.packetId == packetId })
        transport.stop()
        assertTrue(runCatching { transport.receive(peer, packetId) }.isFailure)
    }

    @Test
    fun eventsAreLiveWhilePeerSnapshotsSurviveCollectorReplacement() = runTest {
        val transport = FakeTransport(FakeClock())
        transport.start().getOrThrow()
        val peer = PeerHandle(transport.id, "B")
        transport.connect(peer)
        val events = mutableListOf<TransportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { transport.events.collect { events.add(it) } }
        runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(listOf(peer), transport.peers.value)
        transport.disconnect(peer)
        runCurrent()
        assertEquals(TransportEventType.PEER_DISCONNECTED, events.single().type)
    }

    @Test
    fun eventCollectorCanSendRepliesWhileMoreThanOneBufferOfEventsArrives() = runTest {
        val transport = FakeTransport(FakeClock())
        val peer = PeerHandle(transport.id, "B")
        transport.start().getOrThrow()
        transport.connect(peer)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.events.collect { event ->
                if (event.type == TransportEventType.PACKET_RECEIVED) {
                    transport.send(peer, Cp01Fixtures.packet())
                }
            }
        }
        withTimeout(1_000) {
            repeat(40) { transport.receive(peer, Cp01Fixtures.receipt().packetId) }
        }
        runCurrent()
        assertEquals(40, transport.attempts.value.size)
        assertTrue(transport.attempts.value.all { it.result.code == SendResultCode.ACCEPTED_BY_TRANSPORT })
    }
}
