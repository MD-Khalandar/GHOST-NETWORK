package org.ghost.data.local

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.ghost.domain.model.*
import org.ghost.protocol.ProtocolException
import org.ghost.protocol.WireCodec
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
        val packet = WireCodec.decode(sender.outgoing().single().wire).copy(signature = "AA")
        try { receiver.receiveSos(packet, a.nodeId.value); fail("Invalid signature accepted") }
        catch (_: ProtocolException) { }
        assertEquals(0, db.sos().count())
        assertTrue(receiver.publish(payload().copy(description = "")).isFailure)
        assertEquals(0, db.sos().count())
    }
}
