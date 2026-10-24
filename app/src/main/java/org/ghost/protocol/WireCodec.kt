package org.ghost.protocol

import com.google.protobuf.ByteString
import kotlin.io.encoding.Base64
import org.ghost.contracts.v1.ImmutableEnvelopeV1
import org.ghost.contracts.v1.RelayMetadataV1
import org.ghost.contracts.v1.WirePacketV1
import org.ghost.domain.model.NodeId
import org.ghost.domain.model.RelayMetadata
import org.ghost.domain.model.WirePacket
import org.ghost.security.SigningIdentity

object WireCodec {
    fun base64(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')
    fun unbase64(value: String, maximum: Int): ByteArray {
        if (value.length > (maximum * 4 + 2) / 3) throw ProtocolException(Rejection.TOO_LARGE)
        if (!value.matches(Regex("[A-Za-z0-9_-]+"))) throw ProtocolException(Rejection.MALFORMED_PACKET)
        val decoded = Base64.UrlSafe.decode(value + "=".repeat((4 - value.length % 4) % 4))
        if (decoded.size > maximum) throw ProtocolException(Rejection.TOO_LARGE)
        if (base64(decoded) != value) throw ProtocolException(Rejection.MALFORMED_PACKET)
        return decoded
    }
    fun bytes(hex: String): ByteArray {
        require(hex.matches(Regex("[a-f0-9]{64}")))
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun sign(identity: SigningIdentity, envelope: ImmutableEnvelopeV1): WirePacket {
        require(envelope.senderNodeId.toByteArray().contentEquals(bytes(identity.nodeId.value)))
        require(envelope.senderSigningPublicKey.toByteArray().contentEquals(identity.publicKey.encoded))
        val raw = envelope.toByteArray()
        require(raw.size <= PacketValidator.MAX_ENVELOPE)
        return WirePacket(base64(raw), base64(identity.sign(raw)), RelayMetadata(4, 0))
    }
    /** Rebuild known fields so minimal revocations cannot hide content in unknown fields. */
    internal fun knownEnvelopeBytes(e: ImmutableEnvelopeV1): ByteArray = ImmutableEnvelopeV1.newBuilder()
        .setVersion(e.version).setPacketId(e.packetId).setEventId(e.eventId)
        .setSenderNodeId(e.senderNodeId).setSenderSigningPublicKey(e.senderSigningPublicKey)
        .setDestination(org.ghost.contracts.v1.DestinationV1.newBuilder().setKindValue(e.destination.kindValue).setNodeId(e.destination.nodeId))
        .setKindValue(e.kindValue).setCreatedAtMs(e.createdAtMs).setExpiresAtMs(e.expiresAtMs)
        .setPriorityValue(e.priorityValue).setPayloadFormatValue(e.payloadFormatValue).setPayloadBytes(e.payloadBytes)
        .build().toByteArray()
    fun encode(packet: WirePacket): ByteArray = WirePacketV1.newBuilder()
        .setImmutableEnvelopeBytes(ByteString.copyFrom(unbase64(packet.immutableEnvelopeBytesBase64, PacketValidator.MAX_ENVELOPE)))
        .setSignature(ByteString.copyFrom(unbase64(packet.signature, 72)))
        .setRelay(RelayMetadataV1.newBuilder().setRemainingHops(packet.relay.remainingHops).setHopCount(packet.relay.hopCount).apply {
            packet.relay.previousNodeId?.let { previousNodeId = ByteString.copyFrom(bytes(it.value)) }
        }).build().toByteArray().also { require(it.size <= PacketValidator.MAX_PACKET) }

    fun decode(bytes: ByteArray): WirePacket {
        if (bytes.size > PacketValidator.MAX_PACKET) throw ProtocolException(Rejection.TOO_LARGE)
        val packet = WirePacketV1.parseFrom(bytes)
        return WirePacket(base64(packet.immutableEnvelopeBytes.toByteArray()), base64(packet.signature.toByteArray()),
            RelayMetadata(packet.relay.remainingHops, packet.relay.hopCount,
                packet.relay.previousNodeId.takeUnless { it.isEmpty }?.let { NodeId(SigningIdentity.hex(it.toByteArray())) }))
    }
}
