package org.ghost.feature.network

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.ghost.transport.api.PeerHandle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GraphBackground = Color(0xFF111318)
private val GraphForeground = Color(0xFFE6E8EE)
private val GraphMuted = Color(0xFFA1A8B8)
private val GraphTeal = Color(0xFF82D8CE)

private class NeighborMotion(val peer: PeerHandle, val slot: Int) {
    val arrival = Animatable(0f)
    var displacement by mutableStateOf(Offset.Zero)
    var dragging by mutableStateOf(false)
}

// Slots survive changes in list order, so existing phones don't shuffle when another leaves.
private fun restingPosition(slot: Int): Offset = when (slot) {
    0 -> Offset(-0.57f, -0.48f)
    1 -> Offset(0.65f, -0.22f)
    2 -> Offset(0.31f, 0.64f)
    3 -> Offset(-0.60f, 0.47f)
    else -> {
        val angle = slot * 2.399963
        Offset(cos(angle).toFloat(), sin(angle).toFloat()) * 0.7f
    }
}

/** Local observations only: no inferred links between neighbors or distant phones. */
@Composable
fun NearbyMeshGraph(peers: List<PeerHandle>, identities: Map<String, String>, active: Boolean,
                    starting: Boolean = false, modifier: Modifier = Modifier) {
    val neighbors = remember(peers, active) {
        if (active) peers.distinct().sortedWith(compareBy({ it.transportId }, { it.endpointId })) else emptyList()
    }
    val motionCache = remember { mutableMapOf<PeerHandle, NeighborMotion>() }
    val motions = remember(neighbors) {
        motionCache.keys.retainAll(neighbors.toSet())
        neighbors.map { peer ->
            motionCache.getOrPut(peer) {
                val occupied = motionCache.values.map { it.slot }.toSet()
                NeighborMotion(peer, generateSequence(0) { it + 1 }.first { it !in occupied })
            }
        }
    }
    var selectedPeer by remember { mutableStateOf<PeerHandle?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val selected = selectedPeer?.takeIf { it in neighbors }
    val ready = neighbors.count { identities.containsKey(it.endpointId) }
    val summary = when {
        starting -> "Connecting to mesh"
        !active -> "Mesh is off"
        neighbors.isEmpty() -> "Searching for nearby phones"
        neighbors.size == 1 -> "1 direct connection"
        else -> "${neighbors.size} direct connections"
    }
    LaunchedEffect(active) {
        if (!active) { selectedPeer = null; zoom = 1f; pan = Offset.Zero }
    }
    LaunchedEffect(neighbors) { if (selectedPeer !in neighbors) selectedPeer = null }
    motions.forEach { motion ->
        key(motion.peer) {
            LaunchedEffect(motion) {
                motion.arrival.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 45f))
            }
        }
    }
    val transition = rememberInfiniteTransition(label = "mesh atmosphere")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "neighbor drift")

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = GraphBackground, contentColor = GraphForeground) {
        Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your nearby mesh", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(summary, color = GraphMuted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(color = GraphTeal.copy(alpha = 0.10f), shape = RoundedCornerShape(50)) {
                    Text("$ready ready", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = GraphTeal, style = MaterialTheme.typography.labelSmall)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(0.95f).clipToBounds()
                .testTag("nearby-mesh-graph")
                .semantics {
                    contentDescription = "Your nearby mesh. $summary. Your phone has ${neighbors.size} direct connections: " +
                        "$ready ready to relay, ${neighbors.size - ready} checking identity. Only direct neighbors are shown."
                    stateDescription = "Zoom ${(zoom * 100).roundToInt()} percent"
                    customActions = listOf(
                        CustomAccessibilityAction("Zoom in") { zoom = (zoom * 1.25f).coerceAtMost(2.4f); true },
                        CustomAccessibilityAction("Zoom out") { zoom = (zoom / 1.25f).coerceAtLeast(0.7f); true },
                    )
                }) {
                val density = LocalDensity.current
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                val center = Offset(width / 2, height / 2)
                val radius = minOf(width, height) * 0.43f
                val labelWidth = with(density) { 88.dp.toPx() }
                val nodeHeight = with(density) { 56.dp.toPx() }
                fun project(world: Offset): Offset = center + world * (radius * zoom) + pan
                fun position(motion: NeighborMotion): Offset {
                    val drift = if (motion.dragging || motion.displacement != Offset.Zero) Offset.Zero else
                        Offset(sin(phase + motion.slot * 1.7f), cos(phase + motion.slot * 2.1f)) * 0.035f
                    return project((restingPosition(motion.slot) + drift) * motion.arrival.value + motion.displacement)
                }

                Box(Modifier.matchParentSize().pointerInput(width, height) {
                    // Leave one-finger background gestures to the screen's scrolling list.
                    // Only a two-finger gesture captures the graph's camera.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var transforming = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                transforming = true
                                val oldZoom = zoom
                                zoom = (zoom * event.calculateZoom()).coerceIn(0.7f, 2.4f)
                                val focus = event.calculateCentroid(useCurrent = false).let {
                                    if (it.x.isFinite() && it.y.isFinite()) it else center
                                }
                                val ratio = zoom / oldZoom
                                val nextPan = (pan + center - focus) * ratio + focus - center + event.calculatePan()
                                pan = Offset(nextPan.x.coerceIn(-width / 2, width / 2), nextPan.y.coerceIn(-height / 2, height / 2))
                            }
                            if (transforming) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(Brush.radialGradient(listOf(Color(0xFF1E242D), GraphBackground),
                            center = center, radius = size.maxDimension * 0.65f))
                        val origin = project(Offset.Zero)
                        motions.forEach { motion ->
                            val target = position(motion)
                            val verified = identities.containsKey(motion.peer.endpointId)
                            val highlighted = selected == motion.peer
                            val tint = if (verified) GraphTeal else GraphMuted
                            drawLine(tint.copy(alpha = if (highlighted) 0.65f else 0.25f), origin, target,
                                strokeWidth = if (highlighted) 1.5.dp.toPx() else 1.dp.toPx(),
                                pathEffect = if (verified) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
                            val pulse = if (verified) 1f else 0.65f + 0.2f * sin(phase * 3 + motion.slot)
                            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.16f * pulse), Color.Transparent),
                                center = target, radius = 20.dp.toPx()), radius = 20.dp.toPx(), center = target)
                            drawCircle(tint.copy(alpha = pulse), radius = 4.5.dp.toPx(), center = target)
                            if (highlighted) drawCircle(tint.copy(alpha = 0.8f), radius = 10.dp.toPx(), center = target,
                                style = Stroke(1.dp.toPx()))
                        }
                        drawCircle(Brush.radialGradient(listOf(GraphTeal.copy(alpha = 0.2f), Color.Transparent),
                            center = origin, radius = 32.dp.toPx()), radius = 32.dp.toPx(), center = origin)
                        drawCircle(GraphTeal.copy(alpha = 0.20f), radius = 12.dp.toPx(), center = origin,
                            style = Stroke(1.dp.toPx()))
                        drawCircle(GraphForeground, radius = 6.dp.toPx(), center = origin)
                    }
                    Text("You", color = GraphForeground, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.offset {
                            val point = project(Offset.Zero)
                            IntOffset((point.x - labelWidth / 2).roundToInt(), (point.y + 15.dp.toPx()).roundToInt())
                        }.width(88.dp), textAlign = TextAlign.Center)
                    motions.forEach { motion ->
                        key(motion.peer) {
                            val nodeId = identities[motion.peer.endpointId]
                            Box(Modifier.offset {
                                val point = position(motion)
                                IntOffset((point.x - labelWidth / 2).roundToInt(), (point.y - nodeHeight / 2).roundToInt())
                            }.size(88.dp, 56.dp)
                                .testTag("mesh-node-${motion.peer.endpointId}")
                                .semantics {
                                    contentDescription = if (nodeId == null) "Nearby phone, checking identity" else "Phone ${nodeId.take(8)}, ready to relay"
                                    this.selected = selected == motion.peer
                                }
                                .clickable(role = Role.Button) { selectedPeer = motion.peer }
                                .pointerInput(motion, radius) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        var movement = Offset.Zero
                                        try {
                                            do {
                                                val event = awaitPointerEvent()
                                                // Hand pinches to the camera without also dragging a phone.
                                                if (event.changes.count { it.pressed } > 1) break
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed || change.isConsumed) break
                                                var amount = change.position - change.previousPosition
                                                movement += amount
                                                if (!motion.dragging && movement.getDistance() > viewConfiguration.touchSlop) {
                                                    motion.dragging = true
                                                    selectedPeer = motion.peer
                                                    amount = movement * (1 - viewConfiguration.touchSlop / movement.getDistance())
                                                }
                                                if (motion.dragging) {
                                                    change.consume()
                                                    val world = restingPosition(motion.slot) + motion.displacement + amount / (radius * zoom)
                                                    motion.displacement = Offset(world.x.coerceIn(-0.85f, 0.85f),
                                                        world.y.coerceIn(-0.85f, 0.85f)) - restingPosition(motion.slot)
                                                }
                                            } while (event.changes.any { it.pressed })
                                        } finally { motion.dragging = false }
                                    }
                                }, contentAlignment = Alignment.BottomCenter) {
                                Text(nodeId?.take(8) ?: "Checking", color = if (nodeId == null) GraphMuted else GraphForeground,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Drag phones · pinch to zoom", color = GraphMuted, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        zoom = 1f; pan = Offset.Zero
                        motions.forEach { it.displacement = Offset.Zero }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset view", color = GraphTeal) }
                }
                if (selected != null) {
                    val nodeId = identities[selected.endpointId]
                    Surface(Modifier.fillMaxWidth().testTag("mesh-connection-details"),
                        color = Color(0xFF20262F), shape = RoundedCornerShape(12.dp), contentColor = GraphForeground) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(nodeId?.let { "Phone ${it.take(8)}" } ?: "Nearby phone", style = MaterialTheme.typography.titleSmall)
                            Text(if (nodeId == null) "Checking identity before relay starts." else "Identity checked · ready to relay public SOS reports.",
                                color = if (nodeId == null) GraphMuted else GraphTeal, style = MaterialTheme.typography.bodySmall)
                            Text("Connected directly to your phone.", color = GraphMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (neighbors.isEmpty()) {
                    Text(if (active || starting) "Nearby phones appear here automatically." else "Connect to the mesh to see nearby phones.",
                        color = GraphMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Solid link: ready to relay · dashed: checking identity", color = GraphMuted,
                        style = MaterialTheme.typography.labelSmall)
                    // These buttons also provide keyboard and screen-reader access to each node.
                    neighbors.forEach { peer ->
                        val nodeId = identities[peer.endpointId]
                        TextButton(onClick = { selectedPeer = peer }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (nodeId == null) "Checking phone…" else "Phone ${nodeId.take(8)} · ready to relay",
                                color = if (nodeId == null) GraphMuted else GraphTeal,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text("Direct neighbors only. Links show connections, not SOS delivery paths.",
                    color = GraphMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
