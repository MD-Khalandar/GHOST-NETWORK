package org.ghost.security

import com.google.protobuf.ByteString
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import kotlinx.serialization.json.*
import org.ghost.contracts.v1.*
import org.ghost.domain.model.Consent
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual host JCA crypto and generated protobuf; not Android Keystore/hardware evidence. */
class PacketValidatorTest {
    private val json = Json { explicitNulls = false }
    private val fixture = checkNotNull(javaClass.classLoader?.getResourceAsStream("security-vectors.json"))
        .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
    private val now = fixture.getValue("now_ms").jsonPrimitive.long
    private val device = fixture.getValue("device").jsonObject
    private val cloud = fixture.getValue("cloud").jsonObject
    private val ids = fixture.getValue("ids").jsonObject
    private val vectors = fixture.getValue("packets").jsonArray
    private val owner = device.getValue("node_id").jsonPrimitive.content
    private val lookup = AuthorityLookup { kind, id, version ->
        val expected = when (kind) {
            AuthorityKind.REQUEST -> ids.getValue("request").jsonPrimitive.content
            AuthorityKind.OFFER -> ids.getValue("offer").jsonPrimitive.content
            AuthorityKind.HELPER -> owner
            AuthorityKind.PROPOSAL -> ids.getValue("proposal").jsonPrimitive.content
            AuthorityKind.RECEIPT -> ids.getValue("receipt_packet").jsonPrimitive.content
        }
        if (id == expected && (kind != AuthorityKind.PROPOSAL || version == 2)) Authority(owner, true) else null
    }
    private val validator = PacketValidator(WireCodec.unbase64(cloud.getValue("public_key").jsonPrimitive.content, 91), lookup)
    private fun packet(kind: Int) = json.decodeFromJsonElement<WirePacket>(vectors[kind - 1].jsonObject.getValue("packet"))
    private fun identity(): SigningIdentity {
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(
            WireCodec.unbase64(device.getValue("test_only_private_pkcs8").jsonPrimitive.content, 256)))
        return SigningIdentity(privateKey, SigningIdentity.decodePublicKey(WireCodec.unbase64(device.getValue("public_key").jsonPrimitive.content, 91)))
    }
    private fun resign(kind: Int, update: ImmutableEnvelopeV1.Builder.() -> Unit): WirePacket {
        val original = validator.verify(packet(kind), now).envelope
        return WireCodec.sign(identity(), original.toBuilder().apply(update).build())
    }
    private fun reject(code: Rejection, block: () -> Unit) {
        val error = assertThrows(ProtocolException::class.java) { block() }
        assertEquals(code, error.code)
    }

    @Test fun allTwelveServerFixturesVerifyAndMatchGeneratedProtobufEncoding() {
        assertEquals(Rejection.entries.map { it.name }, fixture.getValue("rejection_codes").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(owner, SigningIdentity.nodeId(identity().publicKey.encoded).value)
        for (vector in vectors) {
            val v = vector.jsonObject
            val p = json.decodeFromJsonElement<WirePacket>(v.getValue("packet"))
            val verified = validator.verify(p, now)
            assertArrayEquals(WireCodec.unbase64(v.getValue("payload_bytes").jsonPrimitive.content, 16384), verified.payload.toByteArray())
            assertArrayEquals(WireCodec.unbase64(p.immutableEnvelopeBytesBase64, 24576), verified.envelope.toByteArray())
            assertArrayEquals(WireCodec.unbase64(v.getValue("wire_bytes").jsonPrimitive.content, 32768), WireCodec.encode(p))
            assertEquals(p, WireCodec.decode(WireCodec.encode(p)))
        }
    }

    @Test fun tamperingBoundsVersionExpiryAndHopFailuresAreRejected() {
        reject(Rejection.BAD_SIGNATURE) { validator.verify(packet(1).copy(signature = WireCodec.base64(ByteArray(70) { 1 })), now) }
        val mismatch = validator.verify(packet(1), now).envelope.toBuilder().setSenderNodeId(ByteString.copyFrom(ByteArray(32))).build()
        val raw = mismatch.toByteArray()
        reject(Rejection.SIGNER_MISMATCH) { validator.verify(packet(1).copy(immutableEnvelopeBytesBase64 = WireCodec.base64(raw), signature = WireCodec.base64(identity().sign(raw))), now) }
        reject(Rejection.UNSUPPORTED_VERSION) { validator.verify(resign(1) { version = 2 }, now) }
        reject(Rejection.EXPIRED) { validator.verify(packet(1), now + 21600000) }
        reject(Rejection.INVALID_HOPS) { validator.verify(packet(1).copy(relay = packet(1).relay.copy(remainingHops = 5)), now) }
        reject(Rejection.TOO_LARGE) { validator.verify(packet(1).copy(immutableEnvelopeBytesBase64 = "a".repeat(40000)), now) }
        reject(Rejection.TOO_LARGE) { validator.verify(resign(1) { payloadBytes = ByteString.copyFrom(ByteArray(16385)) }, now) }
    }

    @Test fun cloudAndOwnerAuthorityFailClosedIncludingStaleVersionsAndWrongReceiptSigner() {
        reject(Rejection.UNTRUSTED_CLOUD) { PacketValidator().verify(packet(7), now) }
        reject(Rejection.UNTRUSTED_CLOUD) { validator.verify(packet(7), now, forCloud = true) }
        reject(Rejection.UNKNOWN_AUTHORITY) { PacketValidator().verify(packet(2), now) }
        val wrongOwner = PacketValidator(lookup = AuthorityLookup { _, _, _ -> Authority("a".repeat(64), true) })
        reject(Rejection.INVALID_ACTOR) { wrongOwner.verify(packet(6), now) }
        reject(Rejection.INVALID_ACTOR) { wrongOwner.verify(packet(12), now) }
        reject(Rejection.UNKNOWN_AUTHORITY) { validator.verify(resign(10) {
            payloadBytes = MatchAcceptedV1.parseFrom(payloadBytes).toBuilder().setVersion(3).build().toByteString()
        }, now) }
    }

    @Test fun consentDefaultsOffAndRevocationCannotUploadNewContent() {
        assertEquals(Consent(false, false), Consent())
        val localOnly = resign(1) { payloadBytes = SosCreateV1.parseFrom(payloadBytes).toBuilder()
            .setConsent(ConsentV1.newBuilder().setPublicMeshAllowed(true)).build().toByteString() }
        validator.verify(localOnly, now)
        reject(Rejection.CONSENT_REQUIRED) { validator.verify(localOnly, now, forCloud = true) }
        val revoked = resign(2) { payloadBytes = SosUpdateV1.newBuilder().setRequestId(ids.getValue("request").jsonPrimitive.content)
            .setStateValue(1).setCloudProcessingAllowed(false).build().toByteString() }
        validator.verify(revoked, now, forCloud = true)
        val withContent = validator.verify(revoked, now).envelope.toBuilder().apply {
            payloadBytes = SosUpdateV1.parseFrom(payloadBytes).toBuilder().setNote("New private content").build().toByteString()
        }.build()
        reject(Rejection.CONSENT_REQUIRED) { validator.verify(WireCodec.sign(identity(), withContent), now, forCloud = true) }
        val hidden = validator.verify(revoked, now).envelope.toBuilder().apply {
            payloadBytes = payloadBytes.concat(ByteString.copyFrom(byteArrayOf(0xa2.toByte(), 0x06, 1, 120)))
        }.build()
        reject(Rejection.CONSENT_REQUIRED) { validator.verify(WireCodec.sign(identity(), hidden), now, forCloud = true) }
    }

    @Test fun signedBytesSurviveRelayMetadataChanges() {
        val signed = WireCodec.sign(identity(), validator.verify(packet(1), now).envelope)
        validator.verify(signed, now)
        val relayed = signed.copy(relay = signed.relay.copy(remainingHops = 3, hopCount = 1, previousNodeId = org.ghost.domain.model.NodeId("a".repeat(64))))
        validator.verify(relayed, now)
        assertEquals(signed.signature, relayed.signature)
        assertEquals(signed.immutableEnvelopeBytesBase64, relayed.immutableEnvelopeBytesBase64)
    }
}
