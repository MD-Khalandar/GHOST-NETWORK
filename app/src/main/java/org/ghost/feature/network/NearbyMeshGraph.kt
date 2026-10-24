package org.ghost.feature.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.ghost.design.InfoCard
import org.ghost.transport.api.PeerHandle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Local observations only: no inferred links between neighbors or distant phones. */
@Composable
fun NearbyMeshGraph(peers: List<PeerHandle>, identities: Map<String, String>, active: Boolean,
                    starting: Boolean = false, modifier: Modifier = Modifier) {
    val neighbors = if (active) peers.distinct().sortedWith(compareBy({ it.transportId }, { it.endpointId })) else emptyList()
    val ready = neighbors.count { identities.containsKey(it.endpointId) }
    val summary = when {
        starting -> "Connecting to mesh"
        !active -> "Mesh is off"
        neighbors.isEmpty() -> "Searching for nearby phones"
        neighbors.size == 1 -> "1 direct connection"
        else -> "${neighbors.size} direct connections"
    }
    val colors = MaterialTheme.colorScheme
    InfoCard("Your nearby mesh", summary, modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).testTag("nearby-mesh-graph")
            .clearAndSetSemantics {
                contentDescription = "Your nearby mesh. $summary. Your phone has ${neighbors.size} direct connections: " +
                    "$ready ready to relay, ${neighbors.size - ready} checking identity. Only direct neighbors are shown."
            }) {
            val diameter = 64.dp
            val radius = ((maxWidth - diameter) / 2 - 8.dp).coerceAtLeast(0.dp)
            Canvas(Modifier.matchParentSize()) {
                drawCircle(colors.outlineVariant.copy(alpha = 0.45f), radius.toPx(), style = Stroke(1.dp.toPx()))
                neighbors.forEachIndexed { index, peer ->
                    val angle = -PI / 2 + index * 2 * PI / neighbors.size
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val verified = identities.containsKey(peer.endpointId)
                    val edge = diameter.toPx() / 2 + 4.dp.toPx()
                    drawLine(
                        color = if (verified) colors.primary else colors.outline,
                        start = center + direction * edge,
                        end = center + direction * (radius.toPx() - edge),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = if (verified) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
                }
            }
            Surface(Modifier.align(Alignment.Center).size(diameter), shape = CircleShape,
                color = colors.primary, contentColor = colors.onPrimary) {
                Box(contentAlignment = Alignment.Center) { Text("You", style = MaterialTheme.typography.titleMedium) }
            }
            neighbors.forEachIndexed { index, peer ->
                key(peer.transportId, peer.endpointId) {
                    val angle = -PI / 2 + index * 2 * PI / neighbors.size
                    val verified = identities.containsKey(peer.endpointId)
                    Surface(Modifier.align(Alignment.Center)
                        .offset(x = (cos(angle) * radius.value).toFloat().dp, y = (sin(angle) * radius.value).toFloat().dp)
                        .size(diameter), shape = CircleShape,
                        color = if (verified) colors.primaryContainer else colors.surfaceContainer,
                        contentColor = if (verified) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        border = BorderStroke(2.dp, if (verified) colors.primary else colors.outline)) {
                        Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        if (neighbors.isEmpty()) {
            Text(if (active || starting) "Nearby phones appear here automatically." else "Connect to the mesh to see nearby phones.")
        } else {
            Text("Solid line: ready to relay. Dashed line: checking phone.", style = MaterialTheme.typography.bodySmall)
            neighbors.forEachIndexed { index, peer ->
                val nodeId = identities[peer.endpointId]
                Text("${index + 1}. " + if (nodeId == null) "Checking phone…" else "Phone ${nodeId.take(8)} · ready to relay",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Direct neighbors only. Farther phones and relay routes are not shown.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
