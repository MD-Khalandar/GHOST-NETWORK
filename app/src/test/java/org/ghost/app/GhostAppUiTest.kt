package org.ghost.app

import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.ghost.design.GhostTheme
import org.ghost.feature.foundation.FoundationPreviewFixtures
import org.ghost.feature.foundation.DeviceTrustState
import org.ghost.feature.foundation.OperatorAccess
import org.ghost.domain.model.SosDraft
import org.ghost.feature.foundation.FoundationState
import org.ghost.transport.api.PeerHandle
import org.ghost.transport.nearby.NearbyState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Host Android rendering/semantics only; not a physical TalkBack or system-bar test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GhostAppUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun navigationAndOpeningTheSosFormDoNotPublish() {
        compose.setContent { GhostTheme(darkTheme = false) { GhostApp(FoundationPreviewFixtures.loaded, {}) } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Send SOS").performScrollTo().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("New SOS").assertIsDisplayed()
        compose.onNodeWithText("UI preview: saving is disabled.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Requests").performClick().assertIsSelected()
        compose.onNodeWithText("Saved requests").assertIsDisplayed()
        compose.onNodeWithText("Saved on this phone only").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Mission").performClick()
        compose.onNodeWithText("Cloud unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resource allocation confirmed").assertDoesNotExist()

        compose.onNode(hasText("Network") and hasClickAction()).performClick()
        compose.onNodeWithText("Connect to mesh").performScrollTo().assertIsDisplayed()

    }

    @Test
    fun operatorSessionStatesNeverExposePhoneApprovalControls() {
        val trust = mutableStateOf(DeviceTrustState())
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, trust.value) } }
        compose.onNode(hasText("Network") and hasClickAction()).performClick()
        for ((access, label) in listOf(
            OperatorAccess.SIGN_IN_REQUIRED to "Operator sign-in required",
            OperatorAccess.NOT_AUTHORIZED to "Operator access denied",
            OperatorAccess.AUTHENTICATED_OPERATOR to "Operator session authenticated",
        )) {
            compose.runOnIdle { trust.value = DeviceTrustState(operatorAccess = access) }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText("Approve").assertDoesNotExist()
        }
    }

    @Test
    fun sharingChoicesRemainIndependentWithoutAnExtraReviewScreen() {
        var saved: SosDraft? = null
        val draft = SosDraft(publicSharing = true)
        val actions = LocalActions({}, { _, done -> done() }, { value, done -> saved = value; done() }, {}, {}, {})
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, local = LocalUiState(draft = draft), actions = actions) } }
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        compose.onNodeWithText("Sharing settings").performScrollTo().performClick()
        compose.onNodeWithText("Share with mesh").performScrollTo().assertIsOn()
        compose.onNodeWithText("Allow cloud coordination").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Share with mesh").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Allow cloud coordination").performScrollTo().assertIsOn()
        compose.onNodeWithText("What help do you need?").performScrollTo().performTextInput("Need drinking water")
        assertNull(saved)
        compose.onNodeWithText("Review SOS").assertDoesNotExist()
        compose.onNodeWithText("Save on this phone").performClick()
        assertEquals("Need drinking water", saved!!.description)
        assertFalse(saved!!.publicSharing)
        assertTrue(saved!!.cloudConsent)
    }

    @Test
    fun messageAloneSendsOnceAndPeopleUsesOptionalStepper() {
        var saved: SosDraft? = null
        var sends = 0
        val actions = LocalActions({}, { _, done -> done() }, { value, done -> saved = value; sends++; done() }, {}, {}, {})
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, local = LocalUiState(draft = SosDraft(publicSharing = true)), actions = actions) } }
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        compose.onNodeWithText("People needing help").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("sos-send").performClick()
        assertEquals(0, sends)
        compose.onNodeWithText("What help do you need?").performScrollTo().performTextInput("Need water")
        compose.onNodeWithText("Optional details").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2) // Message and optional area; no number keyboard.
        compose.onNodeWithContentDescription("More people").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Fewer people").performClick()
        compose.onNodeWithText("Hide optional details").performScrollTo().performClick()
        compose.onNodeWithTag("sos-send").performClick()
        assertEquals(1, sends)
        assertEquals("Need water", saved!!.description)
        assertEquals("1", saved!!.people)
        assertTrue(saved!!.publicSharing)
        assertFalse(saved!!.cloudConsent)
    }

    @Test
    fun joiningTheMeshHasOneActionAndNeverPublishesAnSos() {
        var starts = 0; var stops = 0; var publishes = 0
        val local = mutableStateOf(LocalUiState(draft = SosDraft()))
        val actions = LocalActions({}, { _, done -> done() }, { _, _ -> publishes++ }, { starts++ }, { stops++ }, {})
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, local = local.value, actions = actions) } }
        compose.onNodeWithText("Connect to mesh").performScrollTo().performClick()
        assertEquals(1, starts)
        compose.runOnIdle { local.value = local.value.copy(network = org.ghost.transport.nearby.NearbyState(active = true)) }
        compose.onNodeWithText("Searching for nearby phones").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Codes match").assertDoesNotExist()
        compose.onNodeWithText("Disconnect from mesh").performScrollTo().performClick()
        assertEquals(1, stops)
        assertEquals(0, publishes)
    }

    @Test
    fun nearbyGraphTracksConnectionsIdentityChecksAndDisconnectWithoutInventingRoutes() {
        val state = mutableStateOf(FoundationState())
        val local = mutableStateOf(LocalUiState(network = NearbyState(active = true)))
        compose.setContent { GhostTheme { GhostApp(state.value, {}, local = local.value) } }
        compose.onNode(hasText("Network") and hasClickAction()).performClick()
        compose.onNodeWithTag("nearby-mesh-graph").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Your phone has 0 direct connections", substring = true).assertExists()
        val a = PeerHandle("nearby", "A"); val b = PeerHandle("nearby", "B")
        compose.runOnIdle {
            state.value = state.value.copy(peers = listOf(a, b))
            local.value = local.value.copy(peerIdentities = mapOf("A" to "a".repeat(64)))
        }
        compose.onNodeWithContentDescription("2 direct connections: 1 ready to relay, 1 checking identity", substring = true).assertExists()
        compose.onNodeWithText("2. Checking phone…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { local.value = local.value.copy(peerIdentities = mapOf("A" to "a".repeat(64), "B" to "b".repeat(64))) }
        compose.onNodeWithContentDescription("2 direct connections: 2 ready to relay, 0 checking identity", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(peers = listOf(b)) }
        compose.onNodeWithContentDescription("1 direct connections: 1 ready to relay", substring = true).assertExists()
        compose.onNodeWithText("1. Phone aaaaaaaa · ready to relay").assertDoesNotExist()
        // Stop hides stale peer/identity snapshots immediately.
        compose.runOnIdle { local.value = local.value.copy(network = NearbyState()) }
        compose.onNodeWithContentDescription("Your phone has 0 direct connections", substring = true).assertExists()
        compose.onNodeWithText("1. Phone bbbbbbbb · ready to relay").assertDoesNotExist()
    }
}
