package org.ghost.data.local

import androidx.room.withTransaction
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity

data class AssistanceSummary(val id:String,val version:Int,val requestId:String,val resourceId:String,val owner:Boolean,
    val state:Int,val description:String,val area:String,val quantity:Int?,val unit:String,val deadline:Long,
    val reason:String,val pending:Boolean,val error:String?,val resourceKind:Int)

class LocalAssistanceRepository(private val db:GhostDatabase,private val identity:()->SigningIdentity,private val now:()->Long) {
    private val dao=db.assistance()
    fun observeRows()=dao.observe()
    fun observe()=dao.observe().combine(flow { while(true){ emit(now());delay(10000) } }) { rows,_ ->
        if(rows.none{it.kind==16})return@combine emptyList()
        val local=withContext(Dispatchers.IO){ identity().nodeId.value }
        rows.filter { it.kind==16 && it.destination==local }.distinctBy { it.proposalId }.map { row ->
            val p=AssistanceStatusV1.parseFrom(envelope(row).payloadBytes)
            val decision=rows.firstOrNull { it.kind==17 && it.proposalId==row.proposalId && it.version==row.version }
            AssistanceSummary(p.proposalId,p.version,p.requestId,p.resourceId,
                SigningIdentity.hex(p.ownerNodeId.toByteArray())==local,p.state,p.description,p.area,
                if(p.hasQuantity())p.quantity else null,p.unit,p.deadlineMs,p.reason,decision!=null,decision?.cloudError,p.resourceKind)
        }
    }.flowOn(Dispatchers.IO)
    private fun envelope(row:AssistanceRow)=ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(row.wire).immutableEnvelopeBytesBase64,PacketValidator.MAX_ENVELOPE))
    suspend fun store(packet:WirePacket,cloudKey:ByteArray)=withContext(Dispatchers.IO) {
        val v=PacketValidator(cloudKey).verify(packet,now());require(v.envelope.kindValue in 16..17)
        db.withTransaction {
            val e=v.envelope
            require(db.sos().packet(e.packetId)==null && db.sos().event(e.eventId)==null &&
                db.resources().packet(e.packetId)==null && db.resources().event(e.eventId)==null &&
                db.closures().packet(e.packetId)==null && db.closures().event(e.eventId)==null &&
                db.sos().cloudReturn(e.packetId)==null && db.sos().cloudReturnEvent(e.eventId)==null){"Conflicting coordination identifier."}
            val id=if(v.payload is AssistanceStatusV1)v.payload.proposalId else (v.payload as AssistanceDecisionV1).proposalId
            val version=if(v.payload is AssistanceStatusV1)v.payload.version else (v.payload as AssistanceDecisionV1).version
            val previous=dao.packet(e.packetId) ?: dao.event(e.eventId)
            if(previous!=null){
                require(envelope(previous)==e)
                if(packet.relay.hopCount<WireCodec.decode(previous.wire).relay.hopCount) dao.relay(e.packetId,WireCodec.encode(packet))
            } else {
                dao.prune(now(),identity().nodeId.value);require(dao.count()<1000){"Coordination queue is full."}
                dao.insert(AssistanceRow(e.eventId,e.packetId,id,version,e.kindValue,
                    if(e.destination.kindValue==2)SigningIdentity.hex(e.destination.nodeId.toByteArray()) else "",
                    e.expiresAtMs,WireCodec.encode(packet)))
            }
        }
    }
    suspend fun decide(id:String,version:Int,decision:Int,cloudKey:ByteArray)=withContext(Dispatchers.IO) {
        db.withTransaction {
            val key=identity();val local=key.nodeId.value
            val rows=dao.proposal(id)
            val row=rows.firstOrNull{it.kind==16 && it.destination==local} ?: error("Invitation not found.")
            val e=envelope(row);val p=AssistanceStatusV1.parseFrom(e.payloadBytes)
            require(decision in 1..4)
            val actor=if(decision==4)p.requesterNodeId else p.ownerNodeId
            require(p.version==version && p.deadlineMs>now() && SigningIdentity.hex(actor.toByteArray())==local){"This arrangement is no longer current or belongs to another person."}
            require(if(decision>=3)p.state==2 else p.state==1)
            require(rows.none{it.kind==17 && it.version==version}){"A decision is already waiting for server confirmation."}
            val proof=WireCodec.decode(row.wire)
            val payload=AssistanceDecisionV1.newBuilder().setProposalId(id).setVersion(version).setDecision(decision)
                .setStatusEnvelope(e.toByteString()).setStatusSignature(ByteString.copyFrom(WireCodec.unbase64(proof.signature,72))).build()
            val packet=SignedPackets.create(key,17,payload.toByteString(),now(),ttl=minOf(e.expiresAtMs,p.deadlineMs)-now())
            store(packet,cloudKey)
        }
    }
    suspend fun receive(packet:WirePacket,peer:String,cloudKey:ByteArray):ByteArray=withContext(Dispatchers.IO) {
        require(packet.relay.hopCount>0 && packet.relay.previousNodeId?.value==peer)
        db.withTransaction {
            store(packet,cloudKey)
            val e=ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64,PacketValidator.MAX_ENVELOPE))
            val key=identity()
            val receipt=WireCodec.encode(SignedPackets.create(key,12,DeliveryReceiptV1.newBuilder().setPacketId(e.packetId)
                .setRecipientNodeId(ByteString.copyFrom(WireCodec.bytes(key.nodeId.value))).setReceiptTypeValue(1).setObservedAtMs(now()).build().toByteString(),now(),destination=peer))
            dao.delivered(AssistanceDelivery(e.packetId,peer,receipt));receipt
        }
    }
    suspend fun outgoing(peer:String,excluded:Set<String>)=withContext(Dispatchers.IO) {
        dao.outgoing(now(),peer).filterNot{it.packetId in excluded}.mapNotNull{prepare(it.packetId,peer)}.take(30)
    }
    suspend fun prepare(id:String,peer:String):AssistanceRow?=withContext(Dispatchers.IO) {
        if(dao.delivery(id,peer)!=null)return@withContext null
        val row=dao.packet(id)?:return@withContext null
        val packet=WireCodec.decode(row.wire);val local=identity().nodeId
        if(row.expiresAt<=now() || row.destination==local.value || peer==local.value || packet.relay.remainingHops==0 || packet.relay.previousNodeId?.value==peer)return@withContext null
        row.copy(wire=WireCodec.encode(packet.copy(relay=RelayMetadata(packet.relay.remainingHops-1,packet.relay.hopCount+1,local))))
    }
    suspend fun cursor(server:String)=dao.cursor()?.takeIf{it.server==server}?.cursor ?: "0"

    /** Store a verified page and its checkpoint atomically. A packet can expire in transit. */
    suspend fun saveDownload(server: String, page: SyncDownloadResponse, cloudKey: ByteArray) = withContext(Dispatchers.IO) {
        db.withTransaction {
            require(page.cursor == cursor(server) && page.nextCursor.toLong() >= page.cursor.toLong())
            for (packet in page.packets) {
                val e = ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE))
                require(e.kindValue == 16)
                if (e.expiresAtMs <= now()) {
                    // Authenticate before skipping; invalid signatures must never advance a checkpoint.
                    PacketValidator(cloudKey).verify(packet, e.expiresAtMs - 1)
                } else store(packet, cloudKey)
            }
            dao.checkpoint(AssistanceCursor(server = server, cursor = page.nextCursor))
        }
    }
}
