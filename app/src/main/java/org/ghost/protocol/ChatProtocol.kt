package org.ghost.protocol

import org.ghost.contracts.v1.*
import org.ghost.domain.model.*
import org.ghost.security.SigningIdentity
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger

object ChatProtocol {
    private fun check(value:Boolean) { if(!value)throw ProtocolException(Rejection.INVALID_PAYLOAD) }
    fun rsa(bytes:ByteArray):RSAPublicKey {
        require(bytes.size==294)
        val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)) as RSAPublicKey
        require(key.modulus.bitLength()==2048 && key.publicExponent==BigInteger.valueOf(65537) && key.encoded.contentEquals(bytes))
        return key
    }
    fun validate(e:ImmutableEnvelopeV1,p:PrivateChatV1,pin:ByteArray?,now:Long) {
        check(p.type in 1..3 && p.statusEnvelope.size() in 1..8192 && p.statusSignature.size() in 8..72)
        val proofEnvelope=ImmutableEnvelopeV1.parseFrom(p.statusEnvelope)
        check(proofEnvelope.kindValue==16 && proofEnvelope.createdAtMs<=e.createdAtMs)
        val proof=PacketValidator(pin).verify(WirePacket(WireCodec.base64(p.statusEnvelope.toByteArray()),
            WireCodec.base64(p.statusSignature.toByteArray()),RelayMetadata(4,0)),minOf(now,proofEnvelope.expiresAtMs-1))
        val s=proof.payload as AssistanceStatusV1
        check(s.state==2 && p.proposalId==s.proposalId && e.destination.kindValue==2)
        val sender=e.senderNodeId
        val other=if(sender==s.ownerNodeId)s.requesterNodeId else if(sender==s.requesterNodeId)s.ownerNodeId else null
        if(other==null || e.destination.nodeId!=other)throw ProtocolException(Rejection.INVALID_ACTOR)
        check(proofEnvelope.destination.nodeId==if(p.type==3)other else sender)
        if(p.type!=3)check(e.createdAtMs<s.deadlineMs && e.expiresAtMs<=minOf(s.deadlineMs,proofEnvelope.expiresAtMs))
        when(p.type) {
            1 -> {
                try { rsa(p.rsaPublicKey.toByteArray()) } catch(_:Exception){check(false)}
                check(p.messageId.isEmpty() && p.ciphertext.isEmpty && p.wrappedKey.isEmpty && p.senderWrappedKey.isEmpty && p.nonce.isEmpty && p.recipientKeyEnvelope.isEmpty && p.recipientKeySignature.isEmpty)
            }
            2 -> {
                check(p.messageId==e.eventId && p.nonce.size()==12 && p.ciphertext.size() in 17..4016 &&
                    p.wrappedKey.size()==256 && p.senderWrappedKey.size()==256 && p.rsaPublicKey.isEmpty &&
                    p.recipientKeyEnvelope.size() in 1..12288 && p.recipientKeySignature.size() in 8..72)
                val k=ImmutableEnvelopeV1.parseFrom(p.recipientKeyEnvelope)
                check(k.kindValue==18 && PrivateChatV1.parseFrom(k.payloadBytes).type==1)
                val verified=PacketValidator(pin).verify(WirePacket(WireCodec.base64(p.recipientKeyEnvelope.toByteArray()),
                    WireCodec.base64(p.recipientKeySignature.toByteArray()),RelayMetadata(4,0)),e.createdAtMs)
                check((verified.payload as PrivateChatV1).proposalId==p.proposalId && k.senderNodeId==other && k.destination.nodeId==sender && e.expiresAtMs<=k.expiresAtMs)
            }
            3 -> check(p.messageId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")) &&
                p.ciphertext.isEmpty && p.wrappedKey.isEmpty && p.senderWrappedKey.isEmpty && p.nonce.isEmpty && p.rsaPublicKey.isEmpty && p.recipientKeyEnvelope.isEmpty && p.recipientKeySignature.isEmpty)
        }
    }
}
