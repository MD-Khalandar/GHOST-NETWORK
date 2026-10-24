package org.ghost.data.local

import android.app.Application
import androidx.room.Room
import com.google.protobuf.ByteString
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise queue selection with real Room storage and verified signed publications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalResourceRepositoryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databases = mutableListOf<GhostDatabase>()
    private val time = 1792860000000L
    private fun key(): SigningIdentity = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().let { SigningIdentity(it.private, it.public) }
    private fun open(): GhostDatabase = Room.inMemoryDatabaseBuilder(context, GhostDatabase::class.java)
        .build().also { databases.add(it) }
    private fun packet(owner: SigningIdentity, kind: Int, created: Long): WirePacket {
        val ownerId = ByteString.copyFrom(WireCodec.bytes(owner.nodeId.value))
        val consent = ConsentV1.newBuilder().setPublicMeshAllowed(true).build()
        val payload = if (kind == 3) HelperProfilePublishV1.newBuilder().setOwnerNodeId(ownerId)
            .addHelpTypesValue(6).setAvailabilityValue(1).setConsent(consent).build().toByteString()
        else OfferPublishV1.newBuilder().setOwnerNodeId(ownerId).setOfferId(UUID.randomUUID().toString())
            .setResourceTypeValue(1).setQuantity(10).setConsent(consent).build().toByteString()
        return SignedPackets.create(owner, kind, payload, created)
    }
    @After fun close() { databases.forEach { it.close() } }

    @Test fun exhaustedHelpersOrSuppliesDoNotBlockLaterEligiblePublications() = runBlocking {
        for (blockedKind in listOf(3, 5)) {
            val local = key(); val previous = key(); val peer = key(); val supplier = key()
            val repo = LocalResourceRepository(open(), { local }, { time + 1000 })
            repeat(100) { index ->
                val origin = if (blockedKind == 3) key() else supplier
                repo.receive(packet(origin, blockedKind, time + index).copy(relay = RelayMetadata(0, 4, previous.nodeId)), previous.nodeId.value)
            }
            val eligible = listOf(packet(key(), 3, time + 100), packet(supplier, 5, time + 101))
            for (p in eligible) repo.receive(p.copy(relay = RelayMetadata(3, 1, previous.nodeId)), previous.nodeId.value)
            val outgoing = repo.outgoing(peer.nodeId.value, emptySet())
            assertEquals(2, outgoing.size)
            for ((original, row) in eligible.zip(outgoing)) {
                val forwarded = WireCodec.decode(row.wire)
                assertEquals(original.immutableEnvelopeBytesBase64, forwarded.immutableEnvelopeBytesBase64)
                assertEquals(original.signature, forwarded.signature)
                assertEquals(RelayMetadata(2, 2, local.nodeId), forwarded.relay)
                assertEquals(row.createdAt + LocalResourceRepository.LIFETIME, row.expiresAt)
            }
        }
    }

    @Test fun excludedPublicationsDoNotBlockLaterPacketsAndEligibleBatchesStayBounded() = runBlocking {
        val local = key(); val previous = key(); val peer = key(); val supplier = key()
        val db = open(); val repo = LocalResourceRepository(db, { local }, { time + 1000 })
        val ids = (0 until 125).map { index ->
            val p = packet(if (index % 2 == 0) key() else supplier, if (index % 2 == 0) 3 else 5, time + index)
            repo.receive(p.copy(relay = RelayMetadata(3, 1, previous.nodeId)), previous.nodeId.value)
            ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(p.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)).packetId
        }
        val excluded = ids.take(100).toSet()
        assertEquals(ids.drop(100), repo.outgoing(peer.nodeId.value, excluded).map { it.packetId })
        assertEquals(ids.take(100), repo.outgoing(peer.nodeId.value, emptySet()).map { it.packetId })
        assertTrue(repo.outgoing(peer.nodeId.value, ids.toSet()).isEmpty())
        assertEquals(125, db.resources().count())
        assertTrue(repo.outgoing(previous.nodeId.value, emptySet()).isEmpty())
    }

    @Test fun unsupportedOfferUpdatesDoNotBlockALaterHelperForOlderPeers() = runBlocking {
        val local = key(); val peer = key()
        var clock = time
        val repo = LocalResourceRepository(open(), { local }, { clock })
        repeat(100) { index ->
            clock = time + index * 2
            val draft = ResourceDraft(supply = true, quantity = "10")
            val saved = repo.publish(draft).getOrThrow()
            repo.updateOffer(draft.copy(editing = true, baseEventId = saved.eventId, quantity = "9")).getOrThrow()
        }
        clock = time + 1000
        val helper = repo.publish(ResourceDraft(name = "Later helper")).getOrThrow()
        val outgoing = repo.outgoing(peer.nodeId.value, emptySet(), supportsOfferUpdates = false)
        assertEquals(listOf(helper.eventId), outgoing.map { it.eventId })
        val modern = repo.outgoing(peer.nodeId.value, emptySet(), supportsOfferUpdates = true)
        assertEquals(100, modern.size)
        assertTrue(modern.all { it.kind == 6 })
    }
}
