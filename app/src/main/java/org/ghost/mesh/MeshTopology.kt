package org.ghost.mesh

import org.ghost.contracts.v1.NetworkAnnouncementV1
import org.ghost.domain.model.NodeId
import org.ghost.domain.model.WirePacket
import org.ghost.protocol.VerifiedPacket
import org.ghost.security.SigningIdentity
import org.ghost.transport.api.PeerHandle

enum class Connectivity { UNKNOWN, AVAILABLE, UNAVAILABLE }
data class NodeCapabilities(val internet: Connectivity = Connectivity.UNKNOWN, val gatewayConfigured: Boolean = false,
                            val server: Connectivity = Connectivity.UNKNOWN)
data class NodeAnnouncement(val nodeId: String, val neighbors: List<String>, val capabilities: NodeCapabilities,
                            val reportedAtMs: Long, val expiresAtMs: Long, val displayName: String = "")
data class TopologySnapshot(val localNodeId: String? = null, val localCapabilities: NodeCapabilities = NodeCapabilities(),
                            val announcements: List<NodeAnnouncement> = emptyList(), val localDisplayName: String = "")

/** Ephemeral, bounded graph knowledge. Never stored in Room or used to route SOS. */
class MeshTopology {
    private data class Entry(val report: NodeAnnouncement, val packet: WirePacket, val packetId: String)
    private val entries = linkedMapOf<String, Entry>()

    fun clear() = entries.clear()
    fun prune(now: Long) { entries.entries.removeAll { it.value.report.expiresAtMs <= now } }
    fun accept(verified: VerifiedPacket, via: String, self: String, now: Long): Boolean {
        val e = verified.envelope
        val sender = SigningIdentity.hex(e.senderNodeId.toByteArray())
        val relay = verified.packet.relay
        require(relay.hopCount in 1..4 && relay.previousNodeId?.value == via)
        require(relay.hopCount != 1 || sender == via)
        if (sender == self) return false
        prune(now)
        val previous = entries[sender]
        if (previous != null && (e.createdAtMs < previous.report.reportedAtMs ||
            e.createdAtMs == previous.report.reportedAtMs &&
            (e.packetId != previous.packetId ||
                relay.hopCount >= previous.packet.relay.hopCount))) return false
        if (previous == null && entries.size >= MAX_REPORTS) return false
        val p = verified.payload as NetworkAnnouncementV1
        entries[sender] = Entry(NodeAnnouncement(sender, p.neighborNodeIdsList.map { SigningIdentity.hex(it.toByteArray()) },
            NodeCapabilities(Connectivity.entries[p.internetValue], p.gatewayConfigured, Connectivity.entries[p.serverValue]),
            e.createdAtMs, minOf(e.expiresAtMs, now + TTL), p.displayName), verified.packet, e.packetId)
        return true
    }
    fun reports(): List<NodeAnnouncement> = entries.values.map { it.report }
    fun outgoing(self: String, target: String): List<Pair<String, WirePacket>> = entries.values
        .filter { it.report.nodeId != target && it.packet.relay.remainingHops > 0 }
        .map { it.report.nodeId to forward(it.packet, self) }

    companion object {
        const val TTL = 60000L
        const val MAX_REPORTS = 64
        fun forward(packet: WirePacket, self: String): WirePacket = packet.copy(relay = packet.relay.copy(
            remainingHops = packet.relay.remainingHops - 1, hopCount = packet.relay.hopCount + 1, previousNodeId = NodeId(self)))
    }
}

data class GraphPhone(val key: String, val nodeId: String?, val peer: PeerHandle?, val hops: Int,
                      val capabilities: NodeCapabilities = NodeCapabilities(), val displayName: String = "")
data class GraphLink(val from: String, val to: String, val direct: Boolean)
data class KnownMesh(val phones: List<GraphPhone>, val links: List<GraphLink>, val limited: Boolean)

/** Hop count is shortest distance through fresh reported links, never geographic distance. */
fun knownMesh(peers: List<PeerHandle>, identities: Map<String, String>, snapshot: TopologySnapshot, maxHops: Int): KnownMesh {
    val reports = snapshot.announcements.associateBy { it.nodeId }
    val direct = peers.mapNotNull { peer -> identities[peer.endpointId]?.let { it to peer } }.toMap()
    val pendingPeers = peers.filter { identities[it.endpointId] == null }
    val edges = linkedSetOf<Pair<String, String>>()
    val adjacency = mutableMapOf<String, MutableSet<String>>()
    fun edge(a: String, b: String) {
        if (a == b) return
        val ordered = if (a < b) a to b else b to a
        edges.add(ordered)
        adjacency.getOrPut(a) { linkedSetOf() }.add(b)
        adjacency.getOrPut(b) { linkedSetOf() }.add(a)
    }
    direct.keys.sorted().forEach { edge(SELF, it) }
    reports.values.sortedBy { it.nodeId }.forEach { report ->
        if (report.nodeId != snapshot.localNodeId) report.neighbors.forEach { neighbor ->
            // Actual local links override any old remote claim about this phone.
            if (neighbor != snapshot.localNodeId &&
                (reports[neighbor] == null || report.nodeId in reports.getValue(neighbor).neighbors)) edge(report.nodeId, neighbor)
        }
    }
    val distances = linkedMapOf(SELF to 0)
    val queue = ArrayDeque<String>().apply { add(SELF) }
    var limited = false
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (distances.getValue(current) >= maxHops.coerceIn(1, MeshTopology.MAX_REPORTS)) continue
        adjacency[current].orEmpty().forEach { neighbor ->
            if (neighbor !in distances) {
                if (distances.size > MeshTopology.MAX_REPORTS - pendingPeers.size) limited = true
                else { distances[neighbor] = distances.getValue(current) + 1; queue.add(neighbor) }
            }
        }
    }
    val phones = distances.filterKeys { it != SELF }.map { (node, hops) ->
        GraphPhone(direct[node]?.let { "direct:${it.endpointId}" } ?: node, node, direct[node], hops, reports[node]?.capabilities ?: NodeCapabilities(),reports[node]?.displayName.orEmpty())
    } + pendingPeers.map { GraphPhone("direct:${it.endpointId}", null, it, 1) }
    val graphKeys = phones.filter { it.nodeId != null }.associate { it.nodeId to it.key } + (SELF to SELF)
    val links = edges.filter { it.first in distances && it.second in distances }.map {
        GraphLink(graphKeys.getValue(it.first), graphKeys.getValue(it.second), it.first == SELF || it.second == SELF)
    } + phones.filter { it.nodeId == null }.map { GraphLink(SELF, it.key, true) }
    return KnownMesh(phones, links, limited)
}

const val SELF = "self"
