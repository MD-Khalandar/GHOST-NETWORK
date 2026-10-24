package org.ghost.mesh

import android.app.Application
import androidx.room.Room
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.ghost.contracts.v1.ImmutableEnvelopeV1
import org.ghost.data.local.GhostDatabase
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.protocol.PacketValidator
import org.ghost.protocol.WireCodec
import org.ghost.security.SigningIdentity
import org.ghost.transport.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real exchange workers, Room and signatures; only the radio is replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectExchangeTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<GhostDatabase>()
    private val names = mutableListOf<String>()
    private val nodes = mutableMapOf<String, Phone>()
    private val packets = mutableListOf<Triple<String, String, Int>>()
    private var dropReceipts = 0
    private var stallTarget: String? = null
    private val baseTime = 1792860000000L

    private inner class Radio(val name: String) : GhostTransport {
        override val id = "test-radio"
        override val peers = MutableStateFlow<List<PeerHandle>>(emptyList())
        override val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
        override suspend fun start() = Result.success(Unit)
        override suspend fun stop() { peers.value.toList().forEach { disconnect(it.endpointId) } }
        override fun disconnect(endpointId: String) {
            peers.value = peers.value.filterNot { it.endpointId == endpointId }
            events.tryEmit(TransportEvent(TransportEventType.PEER_DISCONNECTED, PeerHandle(id, endpointId)))
            nodes[endpointId]?.radio?.let { other ->
                other.peers.value = other.peers.value.filterNot { it.endpointId == name }
                other.events.tryEmit(TransportEvent(TransportEventType.PEER_DISCONNECTED, PeerHandle(id, name)))
            }
        }
        override suspend fun send(peer: PeerHandle, packet: WirePacket): SendResult {
            if (peer !in peers.value) return SendResult(SendResultCode.REJECTED_NO_PEER)
            val kind = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)).kindValue
            packets.add(Triple(name, peer.endpointId, kind))
            if (peer.endpointId == stallTarget && kind == 1) delay(100000)
            if (kind == 12 && dropReceipts > 0) { dropReceipts--; return SendResult(SendResultCode.ACCEPTED_BY_TRANSPORT) }
            val other = nodes.getValue(peer.endpointId).radio
            other.events.emit(TransportEvent(TransportEventType.PACKET_RECEIVED, PeerHandle(id, name), packetBytes = WireCodec.encode(packet)))
            return SendResult(SendResultCode.ACCEPTED_BY_TRANSPORT)
        }
    }
    private inner class Phone(val key: SigningIdentity, val db: GhostDatabase, val repo: LocalSosRepository,
                              val radio: Radio, val engine: DirectExchange)

    private fun TestScope.phone(name: String, databaseName: String = "mesh-${UUID.randomUUID()}.db", key: SigningIdentity? = null): Phone {
        val signingKey = key ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().let { SigningIdentity(it.private, it.public) }
        names.add(databaseName)
        val db = Room.databaseBuilder(context, GhostDatabase::class.java, databaseName)
            .addMigrations(GhostDatabase.MIGRATION_1_2).build().also { databases.add(it) }
        val repo = LocalSosRepository(db, { signingKey }, { baseTime + testScheduler.currentTime })
        val radio = Radio(name)
        val engine = DirectExchange(backgroundScope, radio, repo, { signingKey }, { baseTime + testScheduler.currentTime })
        return Phone(signingKey, db, repo, radio, engine).also { nodes[name] = it }
    }
    private fun connect(a: String, b: String) {
        val first = nodes.getValue(a).radio; val second = nodes.getValue(b).radio
        val toB = PeerHandle(first.id, b); val toA = PeerHandle(second.id, a)
        first.peers.value += toB; second.peers.value += toA
        first.events.tryEmit(TransportEvent(TransportEventType.PEER_CONNECTED, toB))
        second.events.tryEmit(TransportEvent(TransportEventType.PEER_CONNECTED, toA))
    }
    private fun TestScope.until(condition: suspend () -> Boolean) {
        // Room and JCA run on real IO threads; advance only the engine's scheduled work.
        repeat(1000) {
            runCurrent()
            if (runBlocking { condition() }) return
            Thread.sleep(5)
        }
        fail("Exchange did not reach the expected state: ${nodes.mapValues { it.value.engine.message.value }}")
    }
    private fun Phone.publish(public: Boolean = true) = runBlocking { repo.publish(SosCreatePayload(
        RequestId(UUID.randomUUID().toString()), SosCategory.WATER, "Need drinking water", consent = Consent(public, false))).getOrThrow() }
    private fun sends(from: String, to: String, kind: Int = 1) = packets.count { it == Triple(from, to, kind) }

    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun liveThreePhoneChainRelaysOnceAndLoopLinksDoNotBounceReports() = runTest {
        val a = phone("A"); val b = phone("B"); val c = phone("C")
        connect("A", "B"); connect("B", "C")
        until { b.engine.identities.value.size == 2 && a.engine.identities.value.size == 1 && c.engine.identities.value.size == 1 }
        a.publish(); a.engine.flush()
        until { c.db.sos().count() == 1 && b.repo.observeRequests().first().single().localDelivery == LocalDelivery.RELAYED }
        val report = runBlocking { c.repo.observeRequests().first().single() }
        assertEquals(a.key.nodeId, report.sourceNodeId)
        assertEquals(2, report.relayHops)
        assertEquals(1, runBlocking { b.repo.observeRequests().first().single().acknowledgedNeighbors })
        assertEquals(1, sends("A", "B")); assertEquals(1, sends("B", "C"))
        connect("A", "C")
        until { c.engine.identities.value.size == 2 }
        advanceTimeBy(30000); runCurrent()
        until { a.repo.outgoing(c.key.nodeId.value).isEmpty() }
        assertEquals(1, runBlocking { c.db.sos().count() })
        assertEquals(0, sends("C", "B")); assertEquals(0, sends("C", "A"))
        a.publish(public = false); a.engine.flush()
        advanceTimeBy(10000); runCurrent()
        assertEquals(1, runBlocking { b.db.sos().count() }); assertEquals(1, runBlocking { c.db.sos().count() })
    }

    @Test fun lostReceiptRetriesTheSameReportThenStopsAcrossReconnect() = runTest {
        val a = phone("A"); val b = phone("B")
        connect("A", "B")
        until { a.engine.identities.value.isNotEmpty() && b.engine.identities.value.isNotEmpty() }
        dropReceipts = 1
        a.publish(); a.engine.flush()
        until { sends("B", "A", 12) == 1 }
        advanceTimeBy(20000); runCurrent()
        until { a.repo.observeRequests().first().single().localDelivery == LocalDelivery.RECEIVED_BY_PHONE }
        assertEquals(2, sends("A", "B")); assertEquals(1, runBlocking { b.db.sos().count() })
        a.radio.disconnect("B"); runCurrent()
        connect("A", "B")
        until { a.engine.identities.value.isNotEmpty() && b.engine.identities.value.isNotEmpty() }
        advanceTimeBy(20000); runCurrent()
        assertEquals(2, sends("A", "B"))
    }

    @Test fun retriesPauseAfterThreeAttemptsAndManualRetryRecovers() = runTest {
        val a = phone("A"); val b = phone("B")
        connect("A", "B")
        until { a.engine.identities.value.isNotEmpty() && b.engine.identities.value.isNotEmpty() }
        dropReceipts = 10
        a.publish(); a.engine.flush()
        until { sends("B", "A", 12) == 1 }
        advanceTimeBy(20000); runCurrent(); until { sends("B", "A", 12) == 2 }
        advanceTimeBy(20000); runCurrent(); until { sends("B", "A", 12) == 3 }
        advanceTimeBy(20000); runCurrent()
        until { a.engine.message.value?.contains("paused") == true }
        assertEquals(3, sends("A", "B"))
        dropReceipts = 0; a.engine.retry()
        until { a.repo.observeRequests().first().single().localDelivery == LocalDelivery.RECEIVED_BY_PHONE }
        assertEquals(4, sends("A", "B")); assertEquals(1, runBlocking { b.db.sos().count() })
    }

    @Test fun aStalledNeighborDoesNotBlockAnotherPhoneOrSendAfterStop() = runTest {
        val a = phone("A"); val b = phone("B"); val c = phone("C")
        connect("A", "B"); connect("A", "C")
        until { a.engine.identities.value.size == 2 }
        stallTarget = "B"
        a.publish(); a.engine.flush()
        until { c.db.sos().count() == 1 && sends("A", "B") == 1 }
        runBlocking { a.radio.stop() }; runCurrent()
        assertTrue(a.engine.identities.value.isEmpty())
        advanceTimeBy(120000); runCurrent()
        assertEquals(0, runBlocking { b.db.sos().count() })
        assertEquals(1, sends("A", "B")); assertEquals(1, sends("A", "C"))
    }
}
