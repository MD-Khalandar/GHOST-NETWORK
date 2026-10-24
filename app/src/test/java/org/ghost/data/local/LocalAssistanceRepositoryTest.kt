package org.ghost.data.local

import android.app.Application
import androidx.room.Room
import com.google.protobuf.ByteString
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalAssistanceRepositoryTest {
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private val databases=mutableListOf<GhostDatabase>()
    private val names=mutableListOf<String>()
    private val time=1792860000000L
    private fun key()=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().let{SigningIdentity(it.private,it.public)}
    private fun open(name:String="assistance-${UUID.randomUUID()}.db"):GhostDatabase {
        names.add(name)
        return Room.databaseBuilder(context,GhostDatabase::class.java,name).addMigrations(GhostDatabase.MIGRATION_1_2,GhostDatabase.MIGRATION_2_3,GhostDatabase.MIGRATION_3_4,GhostDatabase.MIGRATION_4_5).build().also{databases.add(it)}
    }
    private fun status(cloud:SigningIdentity,owner:SigningIdentity,requester:SigningIdentity,id:String,destination:String,state:Int=1,version:Int=1)=
        SignedPackets.create(cloud,16,AssistanceStatusV1.newBuilder().setProposalId(id).setVersion(version).setRequestId("b16e3248-e46b-4b53-a049-14c03c90ce9c")
            .setResourceId("d9db115d-a859-476a-9b26-dcf783c69729").setResourceKind(2).setOwnerNodeId(ByteString.copyFrom(WireCodec.bytes(owner.nodeId.value)))
            .setRequesterNodeId(ByteString.copyFrom(WireCodec.bytes(requester.nodeId.value))).setState(state).setQuantity(7).setUnit("litres")
            .setDescription("Need water").setArea("Station").setDeadlineMs(time+3600000).build().toByteString(),time,ttl=3600000,destination=destination)
    @After fun close(){databases.forEach{it.close()};names.forEach{context.deleteDatabase(it)}}

    @Test fun offlineOwnerDecisionAndRequesterConfirmationSurviveRelayDuplicatesAndRestart()=runBlocking {
        val cloud=key();val a=key();val b=key();val c=key();val pin=cloud.publicKey.encoded
        val ownerName="owner-${UUID.randomUUID()}.db";val ownerDb=open(ownerName)
        val owner=LocalSosRepository(ownerDb,{c},{time+1000})
        val carrier=LocalSosRepository(open(),{b},{time+1000})
        val requesterName="requester-${UUID.randomUUID()}.db";val requesterDb=open(requesterName)
        val requester=LocalSosRepository(requesterDb,{a},{time+1000});val id=UUID.randomUUID().toString()
        carrier.assistance.store(status(cloud,c,a,id,c.nodeId.value),pin)
        val invitation=carrier.assistance.outgoing(c.nodeId.value,emptySet()).single()
        val receipt=owner.assistance.receive(WireCodec.decode(invitation.wire),b.nodeId.value,pin)
        carrier.receiveReceipt(WireCodec.decode(receipt),c.nodeId.value,setOf(invitation.packetId))
        assertNull(carrier.assistance.prepare(invitation.packetId,c.nodeId.value))
        assertTrue(owner.assistance.observe().first().single().owner)
        owner.assistance.decide(id,1,1,pin)
        assertTrue(owner.assistance.observe().first().single().pending)
        assertTrue(runCatching{owner.assistance.decide(id,1,2,pin)}.isFailure)
        val outgoing=owner.assistance.outgoing(b.nodeId.value,emptySet()).single{it.kind==17}
        carrier.assistance.receive(WireCodec.decode(outgoing.wire),c.nodeId.value,pin)
        carrier.assistance.receive(WireCodec.decode(outgoing.wire),c.nodeId.value,pin)
        val upload=carrier.cloudOutgoing().single()
        PacketValidator(pin).verify(WireCodec.decode(upload.wire),time+1000,forCloud=true)
        val ack=SignedPackets.create(cloud,7,CloudAckV1.newBuilder().setOriginEventId(upload.eventId).setStatusValue(1).setCloudRevision(9).setReceivedAtMs(time+1000).build().toByteString(),time+1000,destination=c.nodeId.value)
        carrier.acceptCloudAck(upload.eventId,ack,pin,9)
        assertTrue(carrier.cloudOutgoing().isEmpty())
        carrier.assistance.store(status(cloud,c,a,id,a.nodeId.value,2,2),pin)
        val confirmation=carrier.assistance.outgoing(a.nodeId.value,emptySet()).first{it.destination==a.nodeId.value}
        requester.assistance.receive(WireCodec.decode(confirmation.wire),b.nodeId.value,pin)
        requester.assistance.store(status(cloud,c,a,id,a.nodeId.value),pin) // delayed old invitation cannot undo confirmation
        val shown=requester.assistance.observe().first().single();assertFalse(shown.owner);assertEquals(2,shown.state)
        requesterDb.close();databases.remove(requesterDb)
        assertEquals(2,LocalSosRepository(open(requesterName),{a},{time+2000}).assistance.observe().first().single().state)
        ownerDb.close();databases.remove(ownerDb)
        assertEquals(upload.packetId,LocalSosRepository(open(ownerName),{c},{time+2000}).cloudOutgoing().single().packetId)
    }
    @Test fun requesterReceiptSurvivesOfflineRelayAndRestart()=runBlocking {
        val cloud=key();val owner=key();val requester=key();val relay=key();val pin=cloud.publicKey.encoded
        val name="receipt-${UUID.randomUUID()}.db";val db=open(name)
        val requestRepo=LocalSosRepository(db,{requester},{time+1000})
        val ownerRepo=LocalSosRepository(open(),{owner},{time+1000})
        val carrier=LocalSosRepository(open(),{relay},{time+1000})
        val id=UUID.randomUUID().toString()
        val proof=status(cloud,owner,requester,id,requester.nodeId.value,2,2)
        requestRepo.assistance.store(proof,pin)
        ownerRepo.assistance.store(status(cloud,owner,requester,id,owner.nodeId.value,2,2),pin)
        assertTrue(runCatching{ownerRepo.assistance.decide(id,2,4,pin)}.isFailure)
        assertTrue(runCatching{requestRepo.assistance.decide(id,2,3,pin)}.isFailure)
        requestRepo.assistance.decide(id,2,4,pin)
        assertTrue(requestRepo.assistance.observe().first().single().pending)
        assertEquals(2,requestRepo.assistance.observe().first().single().state)
        assertTrue(runCatching{requestRepo.assistance.decide(id,2,4,pin)}.isFailure)
        db.close();databases.remove(db)
        val reopened=LocalSosRepository(open(name),{requester},{time+2000})
        val pending=reopened.cloudOutgoing().single()
        assertEquals(4,(PacketValidator(pin).verify(WireCodec.decode(pending.wire),time+2000,forCloud=true).payload as AssistanceDecisionV1).decision)
        val outgoing=reopened.assistance.outgoing(relay.nodeId.value,emptySet()).single{it.kind==17}
        repeat(2){carrier.assistance.receive(WireCodec.decode(outgoing.wire),requester.nodeId.value,pin)}
        assertEquals(1,carrier.cloudOutgoing().size)
        val original=ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(proof.immutableEnvelopeBytesBase64,24576))
        val forged=SignedPackets.create(owner,17,AssistanceDecisionV1.newBuilder().setProposalId(id).setVersion(2).setDecision(4)
            .setStatusEnvelope(original.toByteString()).setStatusSignature(ByteString.copyFrom(WireCodec.unbase64(proof.signature,72))).build().toByteString(),time+1000,ttl=10000)
        assertTrue(runCatching{PacketValidator(pin).verify(forged,time+2000,forCloud=true)}.isFailure)
        ownerRepo.resources.publish(ResourceDraft(id="d9db115d-a859-476a-9b26-dcf783c69729",supply=true,
            resourceType=ResourceType.WATER,quantity="10",unit="litres")).getOrThrow()
        carrier.assistance.store(status(cloud,owner,requester,id,requester.nodeId.value,7,3),pin)
        val returned=carrier.assistance.outgoing(requester.nodeId.value,emptySet()).first{it.version==3}
        reopened.assistance.receive(WireCodec.decode(returned.wire),relay.nodeId.value,pin)
        ownerRepo.assistance.store(status(cloud,owner,requester,id,owner.nodeId.value,7,3),pin)
        reopened.assistance.store(proof,pin)
        val shown=reopened.assistance.observe().first().single()
        assertEquals(7,shown.state);assertFalse(shown.pending)
        val stock=ownerRepo.resources.observeSummaries().first().single()
        assertEquals(0,stock.reservedQuantity);assertEquals(7,stock.usedQuantity);assertEquals(3,stock.remainingQuantity)
        assertTrue(ownerRepo.resources.updateOffer(stock.editDraft!!.copy(quantity="6")).isFailure)
    }

    @Test fun invalidDownloadPagesRollBackAndAuthenticatedExpiryDoesNotBlockLaterPages()=runBlocking {
        val cloud=key();val owner=key();val requester=key();val pin=cloud.publicKey.encoded
        val db=open();val repo=LocalAssistanceRepository(db,{owner},{time+1000})
        val first=status(cloud,owner,requester,UUID.randomUUID().toString(),owner.nodeId.value)
        val second=status(cloud,owner,requester,UUID.randomUUID().toString(),owner.nodeId.value)
        val bad=SyncDownloadResponse("0","2",listOf(first,second.copy(signature="AA")))
        assertTrue(runCatching{repo.saveDownload("server",bad,pin)}.isFailure)
        assertEquals(0,db.assistance().count());assertEquals("0",repo.cursor("server"))
        repo.saveDownload("server",bad.copy(packets=listOf(first,second)),pin)
        assertEquals("2",repo.cursor("server"));assertEquals(2,repo.observe().first().size)
        val future=LocalAssistanceRepository(db,{owner},{time+3600001})
        future.saveDownload("server",SyncDownloadResponse("2","3",listOf(first)),pin)
        assertEquals("3",future.cursor("server"))
        db.assistance().prune(time+3600001,owner.nodeId.value)
        assertEquals(2,future.observe().first().size) // Latest local status remains after transport expiry.
    }

    @Test fun untrustedServerForgedOwnerAndExpiredInvitationCannotAuthorizeDecisions()=runBlocking {
        val cloud=key();val owner=key();val requester=key();val attacker=key();val pin=cloud.publicKey.encoded;val id=UUID.randomUUID().toString()
        val invite=status(cloud,owner,requester,id,owner.nodeId.value)
        val repository=LocalSosRepository(open(),{owner},{time+1000})
        assertTrue(runCatching{repository.assistance.store(invite,attacker.publicKey.encoded)}.isFailure)
        repository.assistance.store(invite,pin)
        val e=ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(invite.immutableEnvelopeBytesBase64,24576))
        val forged=SignedPackets.create(attacker,17,AssistanceDecisionV1.newBuilder().setProposalId(id).setVersion(1).setDecision(1)
            .setStatusEnvelope(e.toByteString()).setStatusSignature(ByteString.copyFrom(WireCodec.unbase64(invite.signature,72))).build().toByteString(),time+1000,ttl=10000)
        assertTrue(runCatching{PacketValidator(pin).verify(forged,time+1000,forCloud=true)}.isFailure)
        val expired=LocalSosRepository(open(),{owner},{time+3600001})
        assertTrue(runCatching{expired.assistance.store(invite,pin)}.isFailure)
    }
}
