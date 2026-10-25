package org.ghost.mesh

import com.google.protobuf.ByteString
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.ghost.contracts.v1.NetworkAnnouncementV1
import org.ghost.protocol.*
import org.ghost.security.SigningIdentity
import org.ghost.transport.api.PeerHandle
import org.junit.Assert.*
import org.junit.Test

class MeshTopologyTest {
    private val now = 1792860000000L
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().let { SigningIdentity(it.private, it.public) }
    private fun announcement(origin: SigningIdentity, neighbors: List<String>, time: Long = now, ttl: Long = MeshTopology.TTL,name:String="") =
        SignedPackets.create(origin, 15, NetworkAnnouncementV1.newBuilder()
            .addAllNeighborNodeIds(neighbors.map { ByteString.copyFrom(WireCodec.bytes(it)) })
            .setInternetValue(1).setDisplayName(name).build().toByteString(), time, ttl = ttl)

    @Test fun topologyPreservesSignaturesStopsAtFourTransfersRejectsReplayAndExpires() {
        val a = key(); val b = key(); val c = key()
        val original = announcement(c, listOf(b.nodeId.value))
        val validator = PacketValidator()
        for (name in listOf("x".repeat(41),"Forged\nName","Fake\u202eName"))
            assertEquals(Rejection.INVALID_PAYLOAD,assertThrows(ProtocolException::class.java){validator.verify(announcement(c,emptyList(),name=name),now)}.code)
        val cache = MeshTopology()
        var packet = MeshTopology.forward(original, c.nodeId.value)
        assertTrue(cache.accept(validator.verify(packet, now), c.nodeId.value, a.nodeId.value, now))
        assertFalse(cache.accept(validator.verify(packet, now), c.nodeId.value, a.nodeId.value, now))
        val newer = MeshTopology.forward(announcement(c, emptyList(), now + 1), c.nodeId.value)
        assertTrue(cache.accept(validator.verify(newer, now + 1), c.nodeId.value, a.nodeId.value, now + 1))
        assertFalse(cache.accept(validator.verify(packet, now + 1), c.nodeId.value, a.nodeId.value, now + 1))
        assertTrue(cache.reports().single().neighbors.isEmpty())
        cache.prune(now + MeshTopology.TTL + 1); assertTrue(cache.reports().isEmpty())
        repeat(3) { packet = MeshTopology.forward(packet, b.nodeId.value) }
        assertEquals(0, packet.relay.remainingHops)
        val end = MeshTopology()
        assertTrue(end.accept(validator.verify(packet, now), b.nodeId.value, a.nodeId.value, now))
        assertTrue(end.outgoing(a.nodeId.value, b.nodeId.value).isEmpty())
        assertEquals(original.signature, packet.signature)
        assertEquals(original.immutableEnvelopeBytesBase64, packet.immutableEnvelopeBytesBase64)
        assertEquals(Rejection.BAD_SIGNATURE, assertThrows(ProtocolException::class.java) {
            validator.verify(packet.copy(signature = WireCodec.base64(ByteArray(70) { 1 })), now)
        }.code)
        assertEquals(Rejection.UNSUPPORTED_KIND, assertThrows(ProtocolException::class.java) {
            validator.verify(original, now, forCloud = true)
        }.code)
        assertEquals(Rejection.INVALID_PAYLOAD, assertThrows(ProtocolException::class.java) {
            validator.verify(announcement(c, listOf(c.nodeId.value)), now)
        }.code)
        assertEquals(Rejection.INVALID_PAYLOAD, assertThrows(ProtocolException::class.java) {
            validator.verify(announcement(c, emptyList(), ttl = MeshTopology.TTL + 1), now)
        }.code)
    }

    @Test fun knownGraphFiltersByHopDistanceAndNeverInventsLocalConnections() {
        val a = "a".repeat(64); val b = "b".repeat(64); val c = "c".repeat(64); val d = "d".repeat(64)
        val peer = PeerHandle("nearby", "B")
        val snapshot = TopologySnapshot(a, announcements = listOf(
            NodeAnnouncement(b, listOf(a, c), NodeCapabilities(), now, now + 60000),
            NodeAnnouncement(c, listOf(b, d), NodeCapabilities(Connectivity.AVAILABLE, true), now, now + 60000)))
        assertEquals(listOf(1), knownMesh(listOf(peer), mapOf("B" to b), snapshot, 1).phones.map { it.hops })
        val wider = knownMesh(listOf(peer), mapOf("B" to b), snapshot, 3)
        assertEquals(listOf(1, 2, 3), wider.phones.map { it.hops })
        assertEquals(1, wider.links.count { it.direct })
        assertTrue(wider.phones.single { it.nodeId == c }.capabilities.gatewayConfigured)
        assertTrue(knownMesh(emptyList(), emptyMap(), snapshot, 4).phones.isEmpty())
        val withdrawn = snapshot.copy(announcements = snapshot.announcements.map { if (it.nodeId == b) it.copy(neighbors = listOf(a)) else it })
        assertEquals(1, knownMesh(listOf(peer), mapOf("B" to b), withdrawn, 4).phones.size)
    }

    @Test fun allKnownIncludesReportedEndpointsPastTheSliderAndBoundsDenseGraphs() {
        val nodes = (0..5).map { it.toString(16).padStart(64, '0') }
        val peer = PeerHandle("nearby", "first")
        val reports = (1..4).map { i -> NodeAnnouncement(nodes[i], listOf(nodes[i - 1], nodes[i + 1]),
            NodeCapabilities(), now, now + MeshTopology.TTL) }
        val snapshot = TopologySnapshot(nodes[0], announcements = reports)
        assertEquals(4, knownMesh(listOf(peer), mapOf("first" to nodes[1]), snapshot, 4).phones.size)
        assertEquals(5, knownMesh(listOf(peer), mapOf("first" to nodes[1]), snapshot, MeshTopology.MAX_REPORTS).phones.size)
        val dense = snapshot.copy(announcements = listOf(NodeAnnouncement(nodes[1],
            (10..100).map { it.toString(16).padStart(64, '0') }, NodeCapabilities(), now, now + MeshTopology.TTL)))
        val limited = knownMesh(listOf(peer), mapOf("first" to nodes[1]), dense, MeshTopology.MAX_REPORTS)
        assertTrue(limited.limited)
        assertTrue(limited.phones.size <= MeshTopology.MAX_REPORTS)
    }
}
