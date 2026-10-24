package org.ghost.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghost.contracts.v1.ConsentV1
import org.ghost.contracts.v1.SosCreateV1
import org.ghost.domain.model.*
import org.ghost.protocol.ProtocolException
import org.ghost.protocol.WireCodec
import org.ghost.protocol.PacketValidator
import org.ghost.protocol.SignedPackets
import org.ghost.security.SigningIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Room files and JCA signing. Radio/Android Keystore need physical phones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSosRepositoryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<GhostDatabase>()
    private val names = mutableListOf<String>()
    private val time = 1792860000000L
    private fun key(): SigningIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return SigningIdentity(pair.private, pair.public)
    }
    private fun open(name: String = "test-${UUID.randomUUID()}.db"): GhostDatabase {
        names.add(name)
        return Room.databaseBuilder(context, GhostDatabase::class.java, name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build().also { databases.add(it) }
    }
    private fun payload(public: Boolean = true, id: String = UUID.randomUUID().toString()) = SosCreatePayload(
        RequestId(id), SosCategory.WATER, "Two people need water", 2, "Community hall", consent = Consent(public, false))
    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    @Test fun reportSignedBytesQueueAndDraftSurviveDatabaseReopenWithoutRenewingExpiry() = runBlocking {
        val name = "reopen-${UUID.randomUUID()}.db"
        val db = open(name); val identity = key()
        val repo = LocalSosRepository(db, { identity }, { time })
        repo.initializeDraft()
        val draft = repo.observeDraft().first()!!.copy(description = "Keep this draft", area = "North gate")
        repo.saveDraft(draft)
        repo.publish(payload()).getOrThrow()
        val before = repo.outgoing().single()
        db.close(); databases.remove(db)
        val restored = LocalSosRepository(open(name), { identity }, { time + 1000 })
        assertEquals(1, restored.observeRequests().first().size)
        assertEquals(draft, restored.observeDraft().first())
        val after = restored.outgoing().single()
        assertArrayEquals(before.wire, after.wire)
        assertEquals(before.expiresAt, after.expiresAt)
        assertEquals(LocalDelivery.QUEUED, restored.observeRequests().first().single().localDelivery)
    }

    @Test fun localOnlySaveIsNotTransmittedAndRepeatedSendDoesNotDuplicateOrReviveTheDraft() = runBlocking {
        val db = open(); val identity = key()
        val repo = LocalSosRepository(db, { identity }, { time })
        repo.initializeDraft()
        val draft = repo.observeDraft().first()!!.copy(description = "Need water")
        repo.saveDraft(draft)
        val input = payload(public = false, id = draft.requestId)
        val first = repo.publish(input).getOrThrow()
        assertEquals(first, repo.publish(input).getOrThrow())
        assertEquals(1, db.sos().count())
        assertTrue(repo.outgoing().isEmpty())
        assertEquals(LocalDelivery.SAVED_LOCALLY, repo.observeRequests().first().single().localDelivery)
        val next = repo.observeDraft().first()!!
        assertNotEquals(draft.requestId, next.requestId)
        repo.saveDraft(draft)
        assertEquals(next, repo.observeDraft().first())
    }

    @Test fun receiveDeduplicatesAndOnlyTheCorrectSignedPersistenceReceiptMarksDelivery() = runBlocking {
        val a = key(); val b = key()
        val aDb = open(); val bDb = open()
        val sender = LocalSosRepository(aDb, { a }, { time })
        val receiver = LocalSosRepository(bDb, { b }, { time })
        sender.publish(payload()).getOrThrow()
        val row = sender.outgoing().single()
        val packet = WireCodec.decode(row.wire)
        val receipt = receiver.receiveSos(packet, a.nodeId.value)
        assertArrayEquals(receipt, receiver.receiveSos(packet, a.nodeId.value))
        assertEquals(1, bDb.sos().count())
        assertEquals(LocalDelivery.RECEIVED_FROM_PEER, receiver.observeRequests().first().single().localDelivery)
        try { sender.receiveReceipt(WireCodec.decode(receipt), key().nodeId.value, setOf(row.packetId)); fail("Wrong signer accepted") }
        catch (_: ProtocolException) { }
        assertNull(aDb.sos().packet(row.packetId)!!.receivedBy)
        sender.receiveReceipt(WireCodec.decode(receipt), b.nodeId.value, setOf(row.packetId))
        assertEquals(LocalDelivery.RECEIVED_BY_PHONE, sender.observeRequests().first().single().localDelivery)
        assertArrayEquals(receipt, aDb.sos().packet(row.packetId)!!.receipt)
    }

    @Test fun forgedPacketsAndInvalidReportsLeaveStorageUnchanged() = runBlocking {
        val a = key(); val b = key()
        val sender = LocalSosRepository(open(), { a }, { time })
        val db = open(); val receiver = LocalSosRepository(db, { b }, { time })
        sender.publish(payload()).getOrThrow()
        val packet = WireCodec.decode(sender.outgoing(b.nodeId.value).single().wire)
        try { receiver.receiveSos(packet, key().nodeId.value); fail("Wrong relay accepted") }
        catch (_: IllegalArgumentException) { }
        try { receiver.receiveSos(packet.copy(signature = "AA"), a.nodeId.value); fail("Invalid signature accepted") }
        catch (_: ProtocolException) { }
        assertEquals(0, db.sos().count())
        assertTrue(receiver.publish(payload().copy(description = "")).isFailure)
        assertEquals(0, db.sos().count())
    }

    @Test fun threePhoneRelaySurvivesRestartAndKeepsOriginSignatureAndNeighborReceipts() = runBlocking {
        val a = key(); val b = key(); val c = key()
        val aDb = open(); val bName = "relay-${UUID.randomUUID()}.db"; val bDb = open(bName); val cDb = open()
        val sender = LocalSosRepository(aDb, { a }, { time })
        val relay = LocalSosRepository(bDb, { b }, { time })
        val receiver = LocalSosRepository(cDb, { c }, { time })
        sender.publish(payload()).getOrThrow()
        val first = sender.outgoing(b.nodeId.value).single()
        val originPacket = WireCodec.decode(first.wire)
        val firstReceipt = relay.receiveSos(originPacket, a.nodeId.value)
        sender.receiveReceipt(WireCodec.decode(firstReceipt), b.nodeId.value, setOf(first.packetId))
        assertTrue(relay.outgoing(a.nodeId.value).isEmpty())

        // B can meet C later, even after the app/database restarts.
        bDb.close(); databases.remove(bDb)
        val restoredDb = open(bName)
        val restoredRelay = LocalSosRepository(restoredDb, { b }, { time + 1000 })
        val second = restoredRelay.outgoing(c.nodeId.value).single()
        val forwarded = WireCodec.decode(second.wire)
        assertEquals(originPacket.immutableEnvelopeBytesBase64, forwarded.immutableEnvelopeBytesBase64)
        assertEquals(originPacket.signature, forwarded.signature)
        assertEquals(RelayMetadata(2, 2, b.nodeId), forwarded.relay)
        assertEquals(first.expiresAt, second.expiresAt)
        PacketValidator().verify(forwarded, time + 1000)

        val lastReceipt = receiver.receiveSos(forwarded, b.nodeId.value)
        restoredRelay.receiveReceipt(WireCodec.decode(lastReceipt), c.nodeId.value, setOf(first.packetId))
        assertEquals(a.nodeId, receiver.observeRequests().first().single().sourceNodeId)
        assertArrayEquals(firstReceipt, aDb.sos().packet(first.packetId)!!.receipt)
        try { sender.receiveReceipt(WireCodec.decode(lastReceipt), c.nodeId.value, setOf(first.packetId)); fail("Another phone's receipt accepted") }
        catch (_: IllegalArgumentException) { }

        // A duplicate via a different neighbor gets a receipt addressed to that neighbor.
        val loop = forwarded.copy(relay = RelayMetadata(1, 3, c.nodeId))
        val duplicateReceipt = restoredRelay.receiveSos(loop, c.nodeId.value)
        receiver.receiveReceipt(WireCodec.decode(duplicateReceipt), b.nodeId.value, setOf(first.packetId))
        assertEquals(1, restoredDb.sos().count())
        assertArrayEquals(second.wire, restoredRelay.outgoing(c.nodeId.value).single().wire)
        assertTrue(receiver.outgoing(a.nodeId.value).isEmpty())
        assertTrue(receiver.outgoing(b.nodeId.value).isEmpty())
    }

    @Test fun forwardingStopsAfterFourTransfersOrTheOriginalExpiry() = runBlocking {
        val keys = List(6) { key() }
        val dbs = List(5) { open() }
        val phones = dbs.mapIndexed { i, db -> LocalSosRepository(db, { keys[i] }, { time }) }
        phones.first().publish(payload()).getOrThrow()
        for (i in 0..3) {
            val packet = WireCodec.decode(phones[i].outgoing(keys[i + 1].nodeId.value).single().wire)
            assertEquals(RelayMetadata(3 - i, i + 1, keys[i].nodeId), packet.relay)
            phones[i + 1].receiveSos(packet, keys[i].nodeId.value)
        }
        assertTrue(phones.last().outgoing(keys.last().nodeId.value).isEmpty())
        assertEquals(1, dbs.last().sos().count())
        val expired = LocalSosRepository(dbs[1], { keys[1] }, { time + 21600000 })
        assertTrue(expired.outgoing(keys.last().nodeId.value).isEmpty())
    }

    @Test fun acknowledgedBatchDoesNotBlockLaterRelayedReports() = runBlocking {
        val a = key(); val b = key(); val c = key()
        val relayDb = open()
        val relay = LocalSosRepository(relayDb, { b }, { time })
        repeat(101) {
            val packet = SignedPackets.create(a, 1,
                SosCreateV1.newBuilder().setRequestId(UUID.randomUUID().toString())
                    .setCategoryValue(3).setDescription("Need drinking water")
                    .setConsent(ConsentV1.newBuilder().setPublicMeshAllowed(true)).build().toByteString(), time)
            relay.receiveSos(packet, a.nodeId.value)
        }
        val batch = relay.outgoing(c.nodeId.value)
        assertEquals(100, batch.size)
        val remaining = relay.outgoing(c.nodeId.value, batch.map { it.packetId }.toSet())
        assertEquals(1, remaining.size)
        assertFalse(remaining.single().packetId in batch.map { it.packetId })
    }
}
