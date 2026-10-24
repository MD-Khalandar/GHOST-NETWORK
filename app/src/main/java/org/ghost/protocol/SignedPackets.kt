package org.ghost.protocol

import com.google.protobuf.ByteString
import java.util.UUID
import org.ghost.contracts.v1.*
import org.ghost.domain.model.WirePacket
import org.ghost.security.SigningIdentity

object SignedPackets {
    fun create(identity: SigningIdentity, kind: Int, payload: ByteString, now: Long, destination: String? = null,
               packetId: String = UUID.randomUUID().toString(), eventId: String = UUID.randomUUID().toString(), ttl: Long = 21600000): WirePacket {
        val target = DestinationV1.newBuilder().setKindValue(if (destination == null) 1 else 2).apply {
            destination?.let { nodeId = ByteString.copyFrom(WireCodec.bytes(it)) }
        }
        return WireCodec.sign(identity, ImmutableEnvelopeV1.newBuilder().setVersion(1).setPacketId(packetId).setEventId(eventId)
            .setSenderNodeId(ByteString.copyFrom(WireCodec.bytes(identity.nodeId.value)))
            .setSenderSigningPublicKey(ByteString.copyFrom(identity.publicKey.encoded)).setDestination(target)
            .setKindValue(kind).setCreatedAtMs(now).setExpiresAtMs(now + ttl).setPriorityValue(if (kind == 1) 3 else 1)
            .setPayloadFormatValue(1).setPayloadBytes(payload).build())
    }
}
