package org.ghost.data.local

import androidx.room.withTransaction
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.ghost.contracts.v1.*
import org.ghost.protocol.*
import org.ghost.security.ChatCrypto
import org.ghost.security.SigningIdentity
import java.util.UUID

data class ChatMessage(val id:String,val proposalId:String,val mine:Boolean,val text:String,val createdAt:Long,val status:String)
data class ChatConversation(val proposalId:String,val canSend:Boolean,val status:String,val messages:List<ChatMessage>)

/** Stores only signed ciphertext. Plaintext exists briefly for the conversation UI. */
class LocalChatRepository(private val db:GhostDatabase,private val assistance:LocalAssistanceRepository,
    private val identity:()->SigningIdentity,private val crypto:()->ChatCrypto,private val now:()->Long) {
    private val dao=db.assistance()
    private fun envelope(row:AssistanceRow)=ImmutableEnvelopeV1.parseFrom(WireCodec.unbase64(WireCodec.decode(row.wire).immutableEnvelopeBytesBase64,24576))
    private fun payload(row:AssistanceRow)=PrivateChatV1.parseFrom(envelope(row).payloadBytes)
    private fun hex(bytes:ByteString)=SigningIdentity.hex(bytes.toByteArray())
    private fun statuses(rows:List<AssistanceRow>,local:String)=rows.filter{it.kind==16 && it.destination==local}.distinctBy{it.proposalId}
    private fun other(s:AssistanceStatusV1,local:String)=if(hex(s.ownerNodeId)==local)hex(s.requesterNodeId) else hex(s.ownerNodeId)
    private fun base(row:AssistanceRow,type:Int)=PrivateChatV1.newBuilder().setProposalId(row.proposalId).setType(type)
        .setStatusEnvelope(envelope(row).toByteString()).setStatusSignature(ByteString.copyFrom(WireCodec.unbase64(WireCodec.decode(row.wire).signature,72)))
    private fun decrypt(row:AssistanceRow):String {
        val e=envelope(row);val p=payload(row);val local=identity().nodeId.value
        return crypto().open(p.nonce.toByteArray(),p.ciphertext.toByteArray(),
            (if(hex(e.senderNodeId)==local)p.senderWrappedKey else p.wrappedKey).toByteArray(),
            ChatCrypto.aad(p.proposalId,p.messageId,hex(e.senderNodeId),hex(e.destination.nodeId)))
    }
    fun observe():Flow<List<ChatConversation>> = combine(dao.observe(),flow {while(true){emit(now());kotlinx.coroutines.delay(10000)}}){rows,_->rows}.map { rows->
        val local=identity().nodeId.value
        val chat=rows.filter{it.kind==18}
        statuses(rows,local).map { status->
            val s=AssistanceStatusV1.parseFrom(envelope(status).payloadBytes)
            val peer=other(s,local)
            val key=chat.firstOrNull { it.proposalId==s.proposalId && it.destination==local && payload(it).type==1 && it.expiresAt>now() }
            val open=s.state==2 && s.deadlineMs>now() && db.closures().latest(s.requestId)==null
            val messages=chat.filter { it.proposalId==s.proposalId && payload(it).type==2 &&
                (it.destination==local || hex(envelope(it).senderNodeId)==local) }.map { row->
                val e=envelope(row);val mine=hex(e.senderNodeId)==local
                val delivered=chat.any{it.proposalId==s.proposalId && payload(it).type==3 && payload(it).messageId==row.eventId &&
                    hex(envelope(it).senderNodeId)==peer && it.destination==local}
                val text=runCatching{decrypt(row)}.getOrElse{"Message unavailable on this phone"}
                ChatMessage(row.eventId,s.proposalId,mine,text,e.createdAtMs,when {
                    !mine->"Received on this phone"
                    delivered->"Delivered to the other phone"
                    row.cloudError!=null->"Server rejected this message; delivery is unconfirmed"
                    row.expiresAt<=now()->"Delivery window ended; delivery is unconfirmed"
                    row.cloudAck!=null->"Saved on server; waiting for the other phone"
                    else->"Waiting for delivery"
                })
            }.sortedWith(compareBy<ChatMessage>{it.createdAt}.thenBy{it.id}).takeLast(100)
            ChatConversation(s.proposalId,open && key!=null,when {
                !open->"This arrangement has ended. Chat history is kept on this phone."
                key==null->"Waiting to exchange private chat keys. Keep both phones connected to GHOST."
                else->"Encrypted text between you and the other person. Delivery can wait for a gateway or nearby relay."
            },messages)
        }
    }.flowOn(Dispatchers.IO)

    /** Called on saved coordination changes, including after process restart. */
    suspend fun process(pin:ByteArray)=withContext(Dispatchers.IO) {
        db.withTransaction {
            val local=identity().nodeId.value
            var rows=dao.observe().first()
            for(row in statuses(rows,local)) {
                val s=AssistanceStatusV1.parseFrom(envelope(row).payloadBytes)
                if(s.state!=2 || s.deadlineMs<=now() || db.closures().latest(s.requestId)!=null)continue
                if(rows.any{it.kind==18 && it.proposalId==s.proposalId && payload(it).type==1 && hex(envelope(it).senderNodeId)==local})continue
                val key=base(row,1).setRsaPublicKey(ByteString.copyFrom(crypto().publicKey)).build()
                assistance.store(SignedPackets.create(identity(),18,key.toByteString(),now(),destination=other(s,local),
                    ttl=minOf(envelope(row).expiresAtMs,s.deadlineMs)-now()),pin)
            }
            rows=dao.observe().first()
            for(row in rows.filter{it.kind==18 && it.destination==local && payload(it).type==2}) {
                val p=payload(row)
                if(rows.any{it.kind==18 && payload(it).type==3 && payload(it).messageId==row.eventId && hex(envelope(it).senderNodeId)==local})continue
                // An authenticated end-to-end receipt is created only after successful decryption and durable storage.
                if(runCatching{decrypt(row)}.isFailure)continue
                val receipt=PrivateChatV1.newBuilder().setProposalId(row.proposalId).setType(3).setMessageId(row.eventId)
                    .setStatusEnvelope(p.statusEnvelope).setStatusSignature(p.statusSignature).build()
                assistance.store(SignedPackets.create(identity(),18,receipt.toByteString(),now(),destination=hex(envelope(row).senderNodeId),ttl=86400000),pin)
            }
        }
    }
    suspend fun send(id:String,text:String,pin:ByteArray)=withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length<=1000){"Enter a message up to 1,000 characters."}
        db.withTransaction {
            val local=identity().nodeId.value;val rows=dao.proposal(id)
            val row=statuses(rows,local).firstOrNull()?:error("Arrangement not found.")
            val s=AssistanceStatusV1.parseFrom(envelope(row).payloadBytes)
            require(s.state==2 && s.deadlineMs>now() && db.closures().latest(s.requestId)==null){"This arrangement has ended."}
            require(rows.count{it.kind==18 && payload(it).type==2}<100){"This conversation has reached its 100-message limit."}
            val peer=other(s,local)
            val key=rows.firstOrNull{it.kind==18 && it.destination==local && hex(envelope(it).senderNodeId)==peer && payload(it).type==1 && it.expiresAt>now()}
                ?:error("Waiting for the other phone's encryption key. Keep GHOST connected.")
            val message=UUID.randomUUID().toString()
            val sealed=crypto().seal(text.trim(),payload(key).rsaPublicKey.toByteArray(),ChatCrypto.aad(id,message,local,peer))
            val p=base(row,2).setMessageId(message).setRecipientKeyEnvelope(envelope(key).toByteString())
                .setRecipientKeySignature(ByteString.copyFrom(WireCodec.unbase64(WireCodec.decode(key.wire).signature,72)))
                .setNonce(ByteString.copyFrom(sealed.nonce)).setCiphertext(ByteString.copyFrom(sealed.ciphertext))
                .setWrappedKey(ByteString.copyFrom(sealed.wrapped)).setSenderWrappedKey(ByteString.copyFrom(sealed.ownWrapped)).build()
            assistance.store(SignedPackets.create(identity(),18,p.toByteString(),now(),destination=peer,eventId=message,
                ttl=minOf(envelope(row).expiresAtMs,s.deadlineMs,key.expiresAt)-now()),pin)
        }
    }
}
