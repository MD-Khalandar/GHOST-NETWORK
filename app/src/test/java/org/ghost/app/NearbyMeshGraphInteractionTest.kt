package org.ghost.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.ghost.design.GhostTheme
import org.ghost.feature.network.NearbyMeshGraph
import org.ghost.transport.api.PeerHandle
import org.ghost.mesh.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NearbyMeshGraphInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val a = PeerHandle("nearby", "A")
    private val b = PeerHandle("nearby", "B")

    @Test
    fun hopSliderShowsRemotePhonesAndTheirReportedInternetAndGatewayStatus() {
        val local = "0".repeat(64); val direct = "a".repeat(64); val remote = "c".repeat(64)
        val snapshot = TopologySnapshot(local, announcements = listOf(
            NodeAnnouncement(direct, listOf(local, remote), NodeCapabilities(), 1, 60001),
            NodeAnnouncement(remote, listOf(direct), NodeCapabilities(Connectivity.AVAILABLE, true, Connectivity.UNKNOWN), 1, 60001)))
        compose.setContent { GhostTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NearbyMeshGraph(listOf(a), mapOf("A" to direct), true, topology = snapshot)
            }
        } }
        compose.onNodeWithTag("mesh-node-$remote").assertDoesNotExist()
        compose.onNodeWithTag("mesh-hop-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithTag("mesh-node-$remote").assertExists().performClick()
        compose.onNodeWithText("Internet available (reported)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Configured gateway · server status unknown").assertIsDisplayed()
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        compose.onNodeWithTag("mesh-full-screen").assertIsDisplayed()
        compose.onNodeWithTag("mesh-node-$remote").assertExists().assertIsSelected()
        compose.onNodeWithText("Show up to 2 hops").assertIsDisplayed()
        compose.onNodeWithText("Close full screen").performClick()
        compose.onNodeWithTag("mesh-full-screen").assertDoesNotExist()
        compose.onNodeWithTag("mesh-node-$remote").assertExists().assertIsSelected()
        compose.onNodeWithText("All known").performScrollTo().performClick()
        compose.onNodeWithText("All known phones").assertIsDisplayed()
        compose.onNodeWithText("Limit distance").performClick()
        compose.onNodeWithTag("mesh-hop-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("mesh-node-$remote").assertDoesNotExist()
        compose.onNodeWithTag("mesh-connection-details").assertDoesNotExist()
    }

    @Test
    fun selectingAPhoneTracksIdentityAndClearsDetailsWhenItLeaves() {
        val peers = mutableStateOf(listOf(a, b))
        val identities = mutableStateOf(mapOf("A" to "a".repeat(64)))
        compose.setContent {
            GhostTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NearbyMeshGraph(peers.value, identities.value, active = true)
                }
            }
        }
        compose.onNodeWithTag("mesh-node-B").performClick().assertIsSelected()
        compose.onNodeWithText("Getting connected. Requests can be shared once this finishes.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { identities.value = identities.value + ("B" to "b".repeat(64)) }
        compose.onNodeWithText("Phone bbbbbbbb").assertExists()
        compose.onNodeWithText("Ready to pass along requests.").assertExists()
        compose.runOnIdle { peers.value = listOf(a) }
        compose.onNodeWithTag("mesh-connection-details").assertDoesNotExist()
        compose.onNodeWithTag("mesh-node-B").assertDoesNotExist()
    }

    @Test
    fun draggingAndPinchingChangeTheViewAndResetRestoresIt() {
        compose.setContent {
            GhostTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NearbyMeshGraph(listOf(a, b), mapOf("A" to "a".repeat(64)), active = true)
                }
            }
        }
        val node = compose.onNodeWithTag("mesh-node-A")
        val graph = compose.onNodeWithTag("nearby-mesh-graph")
        val originalGraphTop = graph.fetchSemanticsNode().boundsInRoot.top
        val original = node.fetchSemanticsNode().boundsInRoot.center
        node.performTouchInput { swipe(center, center + Offset(40f, 30f), durationMillis = 400) }
        val dragged = node.fetchSemanticsNode().boundsInRoot.center
        assertTrue("A dragged phone should move", (dragged - original).getDistance() > 20f)
        node.assertIsSelected()
        graph.performTouchInput {
            pinch(Offset(width * 0.3f, height * 0.55f), Offset(width * 0.7f, height * 0.55f),
                Offset(width * 0.15f, height * 0.55f), Offset(width * 0.85f, height * 0.55f), durationMillis = 400)
        }
        assertNotEquals("Zoom 100 percent", graph.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithText("Reset view").performScrollTo().performClick()
        assertEquals("Zoom 100 percent", graph.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        graph.performScrollTo()
        val reset = node.fetchSemanticsNode().boundsInRoot.center
        // Compare within the graph, since scrolling to Reset can move the whole card.
        val graphTop = graph.fetchSemanticsNode().boundsInRoot.top
        assertEquals(original.x, reset.x, 12f)
        assertEquals(original.y - originalGraphTop, reset.y - graphTop, 12f)
    }

    @Test
    fun removingAnEarlierNeighborKeepsTheRemainingPhoneInPlace() {
        val peers = mutableStateOf(listOf(a, b))
        compose.setContent { GhostTheme { NearbyMeshGraph(peers.value, emptyMap(), active = true) } }
        val node = compose.onNodeWithTag("mesh-node-B")
        val before = node.fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle { peers.value = listOf(b) }
        val after = node.fetchSemanticsNode().boundsInRoot.center
        assertTrue("Remaining phones should keep their layout slots", (after - before).getDistance() < 12f)
    }
}
