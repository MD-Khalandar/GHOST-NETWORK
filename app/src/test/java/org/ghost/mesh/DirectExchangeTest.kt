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
import org.ghost.contracts.v1.CloudAckV1
import org.ghost.data.local.GhostDatabase
import org.ghost.data.local.LocalSosRepository
import org.ghost.domain.model.*
import org.ghost.protocol.PacketValidator
import org.ghost.protocol.WireCodec
import org.ghost.protocol.SignedPackets
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
    private var wallOffset = 0L
    private fun newKey(): SigningIdentity = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().let { SigningIdentity(it.private, it.public) }

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
                              val radio: Radio, val engine: DirectExchange, val scope: CoroutineScope)

    private fun TestScope.phone(name: String, databaseName: String = "mesh-${UUID.randomUUID()}.db", key: SigningIdentity? = null,
                                cloudKey: ByteArray? = null, capabilities: () -> NodeCapabilities = { NodeCapabilities() }): Phone {
        val signingKey = key ?: newKey()
        names.add(databaseName)
        val db = Room.databaseBuilder(context, GhostDatabase::class.java, databaseName)
            .addMigrations(GhostDatabase.MIGRATION_1_2, GhostDatabase.MIGRATION_2_3, GhostDatabase.MIGRATION_3_4, GhostDatabase.MIGRATION_4_5).build().also { databases.add(it) }
        val repo = LocalSosRepository(db, { signingKey }, { baseTime + wallOffset + testScheduler.currentTime })
        val radio = Radio(name)
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val engine = DirectExchange(scope, radio, repo, { signingKey }, { baseTime + wallOffset + testScheduler.currentTime }, cloudKey, capabilities)
        return Phone(signingKey, db, repo, radio, engine, scope).also { nodes[name] = it }
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
    private fun Phone.publish(public: Boolean = true, cloud: Boolean = false) = runBlocking { repo.publish(SosCreatePayload(
        RequestId(UUID.randomUUID().toString()), SosCategory.WATER, "Need drinking water", consent = Consent(public, cloud))).getOrThrow() }
    private fun sends(from: String, to: String, kind: Int = 1) = packets.count { it == Triple(from, to, kind) }

    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun shorterDuplicateResumesForwardingToAnAlreadyConnectedNeighbor() = runTest {
        val a = phone("A"); val b = phone("B"); val c = phone("C"); val previous = newKey()
        a.publish()
        val original = runBlocking { WireCodec.decode(a.repo.outgoing().single().wire) }
        runBlocking { b.repo.receiveSos(original.copy(relay = RelayMetadata(0, 4, previous.nodeId)), previous.nodeId.value) }
        connect("B", "C")
        until { b.engine.identities.value.size == 1 && c.engine.identities.value.size == 1 }
        assertEquals(0, runBlocking { c.db.sos().count() })
        connect("A", "B")
        until { c.db.sos().count() == 1 }
        until { a.repo.observeRequests().first().single().localDelivery == LocalDelivery.RECEIVED_BY_PHONE }
        val atB = runBlocking { b.repo.observeRequests().first().single() }
        val atC = runBlocking { c.repo.observeRequests().first().single() }
        assertEquals(1, atB.relayHops); assertEquals(3, atB.remainingRelayHops)
        assertEquals(2, atC.relayHops); assertEquals(2, atC.remainingRelayHops)
        assertEquals(1, runBlocking { b.db.sos().count() })
    }

    @Test fun privateUnavailableHelperSaveRelaysAWithdrawalAndReturnsItsServerReceipt() = runTest {
        val cloud = newKey(); val pin = cloud.publicKey.encoded
        val a = phone("A", cloudKey = pin); phone("B", cloudKey = pin); val g = phone("G", cloudKey = pin)
        runBlocking {
            a.repo.resources.publish(ResourceDraft(name = "Shared volunteer", cloudConsent = true)).getOrThrow()
        }
        connect("A", "B"); connect("B", "G")
        until { g.repo.resources.observeSummaries().first().singleOrNull()?.available == true }
        runBlocking {
            a.repo.resources.publish(ResourceDraft(name = "New private name", available = false,
                publicSharing = false, cloudConsent = false)).getOrThrow()
        }
        until { g.repo.resources.observeSummaries().first().singleOrNull()?.available == false }
        runBlocking {
            val report = g.repo.cloudOutgoing().single()
            val p = PacketValidator().verify(WireCodec.decode(report.wire), baseTime, forCloud = true).payload as org.ghost.contracts.v1.HelperProfilePublishV1
            assertEquals(2, p.availabilityValue)
            assertFalse(p.hasDisplayName()); assertFalse(p.hasCoarseArea())
            val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(report.eventId)
                .setStatusValue(1).setCloudRevision(2).setReceivedAtMs(baseTime).build().toByteString(), baseTime,
                destination = a.key.nodeId.value)
            g.repo.acceptCloudAck(report.eventId, ack, pin, 2)
        }
        until { a.repo.resources.observeSummaries().first().single().withdrawalCloudState == LocalCloudState.ACCEPTED }
        val own = runBlocking { a.repo.resources.observeSummaries().first().single() }
        assertEquals("New private name", own.editDraft!!.name)
        assertFalse(own.editDraft!!.publicSharing); assertFalse(own.editDraft!!.cloudConsent)
        assertEquals(LocalCloudState.NOT_SHARED, own.cloudState)
    }

    @Test fun helpersAndSuppliesRelayAndVerifiedServerReceiptsReturnToTheirOwner() = runTest {
        val cloud = newKey(); val pin = cloud.publicKey.encoded
        val a = phone("A", cloudKey = pin); val b = phone("B", cloudKey = pin); val g = phone("G", cloudKey = pin)
        runBlocking {
            a.repo.resources.publish(ResourceDraft(name = "Water volunteer", cloudConsent = true)).getOrThrow()
            a.repo.resources.publish(ResourceDraft(supply = true, quantity = "20", unit = "litres", cloudConsent = true)).getOrThrow()
            a.repo.resources.publish(ResourceDraft(supply = true, quantity = "5", publicSharing = false, cloudConsent = true)).getOrThrow()
        }
        connect("A", "B"); connect("B", "G")
        until { g.db.resources().count() == 2 }
        assertEquals(0, runBlocking { g.db.sos().count() })
        assertEquals(2, runBlocking { g.repo.cloudOutgoing().size })
        runBlocking {
            for (report in g.repo.cloudOutgoing()) {
                val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(report.eventId)
                    .setStatusValue(1).setCloudRevision(1).setReceivedAtMs(baseTime).build().toByteString(), baseTime,
                    destination = a.key.nodeId.value, ttl = 86400000)
                g.repo.acceptCloudAck(report.eventId, ack, pin, 1)
            }
        }
        until { a.repo.resources.observeSummaries().first().count { it.cloudState == LocalCloudState.ACCEPTED } == 2 }
        assertTrue(runBlocking { a.repo.cloudOutgoing().isEmpty() })
        assertEquals(3, runBlocking { a.db.resources().count() })
        assertEquals(1, sends("A", "B", 3)); assertEquals(1, sends("A", "B", 5))
        assertEquals(2, runBlocking { b.db.resources().count() })
        runBlocking {
            val edit = a.repo.resources.observeSummaries().first().single { !it.helper && it.editDraft?.publicSharing == true }.editDraft!!
            a.repo.resources.publish(edit.copy(quantity = "8")).getOrThrow()
            val withdrawal = a.repo.resources.observeSummaries().first().single { !it.helper && it.editDraft?.publicSharing == true }.editDraft!!
            a.repo.resources.updateOffer(withdrawal, withdraw = true).getOrThrow()
        }
        until { g.repo.resources.observeOffers().first().single().state == OfferState.WITHDRAWN }
        runBlocking {
            for (report in g.repo.cloudOutgoing()) {
                val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(report.eventId)
                    .setStatusValue(1).setCloudRevision(2).setReceivedAtMs(baseTime).build().toByteString(), baseTime,
                    destination = a.key.nodeId.value)
                g.repo.acceptCloudAck(report.eventId, ack, pin, 2)
            }
        }
        until { a.repo.resources.observeSummaries().first().single { it.withdrawn }.cloudState == LocalCloudState.ACCEPTED }
        assertTrue(sends("A", "B", 6) > 0)
    }

    @Test fun signedTopologyCrossesThreePhonesWithoutBecomingSosOrSurvivingDisconnect() = runTest {
        val a = phone("A"); phone("B")
        val c = phone("C", capabilities = { NodeCapabilities(Connectivity.AVAILABLE, true, Connectivity.AVAILABLE) })
        connect("A", "B"); connect("B", "C")
        until { nodes.getValue("B").engine.identities.value.size == 2 && c.engine.identities.value.size == 1 }
        repeat(8) { advanceTimeBy(6000); runCurrent(); Thread.sleep(50) }
        until { a.engine.topology.value.announcements.any { it.nodeId == c.key.nodeId.value } }
        val view = knownMesh(a.radio.peers.value, a.engine.identities.value, a.engine.topology.value, 4)
        val remote = view.phones.single { it.nodeId == c.key.nodeId.value }
        assertEquals(2, remote.hops)
        assertEquals(NodeCapabilities(Connectivity.AVAILABLE, true, Connectivity.AVAILABLE), remote.capabilities)
        assertEquals(1, knownMesh(a.radio.peers.value, a.engine.identities.value, a.engine.topology.value, 1).phones.size)
        assertEquals(0, runBlocking { a.db.sos().count() })
        a.radio.disconnect("B"); runCurrent()
        assertTrue(a.engine.topology.value.announcements.isEmpty())
        assertEquals(0, sends("A", "B", 1))
    }

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

    @Test fun cloudAcknowledgementSurvivesRestartAndReturnsViaAPhoneWithoutTheOriginalSos() = runTest {
        val cloud = newKey(); val pin = cloud.publicKey.encoded
        val a = phone("A", cloudKey = pin); val bName = "return-restart-${UUID.randomUUID()}.db"
        val b = phone("B", bName, cloudKey = pin); val g = phone("G", cloudKey = pin)
        connect("A", "B"); connect("B", "G")
        until { b.engine.identities.value.size == 2 }
        a.publish(cloud = true); a.engine.flush()
        until { g.db.sos().count() == 1 }
        val original = runBlocking { g.repo.cloudOutgoing().single() }
        a.radio.disconnect("B"); runCurrent()
        val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(original.eventId)
            .setStatusValue(1).setCloudRevision(1).setReceivedAtMs(baseTime).build().toByteString(), baseTime,
            destination = a.key.nodeId.value, ttl = 86400000)
        val ackId = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(ack.immutableEnvelopeBytesBase64, 24576)).packetId
        runBlocking { g.repo.acceptCloudAck(original.eventId, ack, pin, 1) }
        until { b.db.sos().cloudReturn(ackId) != null && g.db.sos().returnDelivery(ackId, b.key.nodeId.value)?.receiptFromPeer != null }
        assertEquals(LocalCloudState.QUEUED, runBlocking { a.repo.observeRequests().first().single().cloudState })

        b.radio.disconnect("G"); b.scope.cancel(); runCurrent(); b.db.close(); databases.remove(b.db)
        // The SOS has expired, but the independently expiring server acknowledgement can still return.
        wallOffset += 21600001
        val restored = phone("B", bName, b.key, pin)
        val carrier = phone("X", cloudKey = pin)
        connect("B", "X")
        until { carrier.db.sos().cloudReturn(ackId) != null }
        assertEquals(0, runBlocking { carrier.db.sos().count() })
        connect("X", "A")
        until { a.repo.observeRequests().first().single().cloudState == LocalCloudState.ACCEPTED &&
            carrier.db.sos().returnDelivery(ackId, a.key.nodeId.value)?.receiptFromPeer != null }
        val received = WireCodec.decode(runBlocking { a.db.sos().event(original.eventId)!!.cloudAck!! })
        assertEquals(ack.immutableEnvelopeBytesBase64, received.immutableEnvelopeBytesBase64)
        assertEquals(ack.signature, received.signature)
        assertEquals(RelayMetadata(1, 3, carrier.key.nodeId), received.relay)
        assertArrayEquals(original.wire, runBlocking { g.db.sos().event(original.eventId)!!.wire })
        assertTrue(runBlocking { a.repo.outgoingCloudReturns(restored.key.nodeId.value).isEmpty() })
        assertEquals(1, sends("G", "B", 7)); assertEquals(1, sends("B", "X", 7)); assertEquals(1, sends("X", "A", 7))
        assertEquals(0, sends("X", "B", 7))
    }

    @Test fun aLostReturnReceiptRetriesTheSameCloudPacketAndReconnectDoesNotResendIt() = runTest {
        val cloud = newKey(); val pin = cloud.publicKey.encoded
        val a = phone("A", cloudKey = pin); val g = phone("G", cloudKey = pin)
        connect("A", "G"); a.publish(cloud = true)
        until { g.db.sos().count() == 1 && a.repo.observeRequests().first().single().localDelivery == LocalDelivery.RECEIVED_BY_PHONE }
        val report = runBlocking { g.repo.cloudOutgoing().single() }
        val ack = SignedPackets.create(cloud, 7, CloudAckV1.newBuilder().setOriginEventId(report.eventId)
            .setStatusValue(1).setCloudRevision(1).setReceivedAtMs(baseTime).build().toByteString(), baseTime, destination = a.key.nodeId.value)
        val id = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(ack.immutableEnvelopeBytesBase64, 24576)).packetId
        dropReceipts = 1
        runBlocking { g.repo.acceptCloudAck(report.eventId, ack, pin, 1) }
        until { a.repo.observeRequests().first().single().cloudState == LocalCloudState.ACCEPTED && sends("A", "G", 12) == 1 }
        advanceTimeBy(20000); runCurrent()
        until { g.db.sos().returnDelivery(id, a.key.nodeId.value)?.receiptFromPeer != null }
        assertEquals(2, sends("G", "A", 7))
        g.radio.disconnect("A"); runCurrent(); connect("A", "G")
        until { a.engine.identities.value.isNotEmpty() && g.engine.identities.value.isNotEmpty() }
        advanceTimeBy(20000); runCurrent()
        assertEquals(2, sends("G", "A", 7))
    }
}
