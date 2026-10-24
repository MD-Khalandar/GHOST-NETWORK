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
import org.ghost.domain.model.LocalCloudState
import org.ghost.domain.model.CloudCoordinationState
import org.ghost.domain.model.CloudCoordinationStatus
import org.ghost.gateway.GatewayUiState
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

    @Test fun ownedSupplyCanBeEditedAndWithdrawnWithoutCreatingAnotherOffer() {
        val draft = org.ghost.domain.model.ResourceDraft(supply = true, quantity = "10", unit = "litres", publicSharing = true,
            cloudConsent = true, editing = true, baseEventId = "latest-version")
        val resource = org.ghost.domain.model.ResourceSummary(draft.id, "a".repeat(64), true, false, "Water · 10 litres", "Station",
            true, Long.MAX_VALUE, LocalCloudState.QUEUED, editDraft = draft)
        var saved: org.ghost.domain.model.ResourceDraft? = null
        var withdrawn: org.ghost.domain.model.ResourceDraft? = null
        val actions = LocalActions({}, { _, done -> done() }, { _, _ -> }, {}, {}, {},
            publishResource = { value, done -> saved = value; done() }, withdrawResource = { value, done -> withdrawn = value; done() })
        compose.setContent { GhostTheme { org.ghost.feature.home.ResourceFormDialog(LocalUiState(resources = listOf(resource)), actions, {}) } }
        compose.onNodeWithText("Edit offer").performScrollTo().performClick()
        compose.onNodeWithText("Edit supply offer").assertIsDisplayed()
        compose.onNodeWithText("Quantity (optional)").performScrollTo().performTextReplacement("7")
        compose.onNodeWithText("Share with nearby phones").performScrollTo().assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("Save changes").performClick()
        assertEquals(draft.id, saved!!.id); assertEquals("7", saved!!.quantity); assertTrue(saved!!.editing)
        compose.onNodeWithText("Withdraw offer").performScrollTo().performClick()
        assertEquals(draft.id, withdrawn!!.id); assertEquals(draft.baseEventId, withdrawn!!.baseEventId)
    }

    @Test fun offerHelpOnlyPublishesOnSaveAndKeepsSharingChoicesIndependent() {
        var saved: org.ghost.domain.model.ResourceDraft? = null
        val actions = LocalActions({}, { _, done -> done() }, { _, _ -> }, {}, {}, {},
            publishResource = { value, done -> saved = value; done() })
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, actions = actions) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Offer help"))
        compose.onNodeWithText("Offer help").performClick()
        assertNull(saved)
        compose.onNodeWithText("Supplies").performClick()
        compose.onNodeWithText("Quantity (optional)").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Save and share help").performClick()
        assertNull(saved)
        compose.onNodeWithText("Quantity (optional)").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("Allow online help coordination").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("Share with nearby phones").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Allow online help coordination").assertIsOn()
        compose.onNodeWithText("Save on this phone").performClick()
        assertEquals("12", saved!!.quantity)
        assertTrue(saved!!.supply); assertFalse(saved!!.publicSharing); assertTrue(saved!!.cloudConsent)
    }

    @Test
    fun navigationAndOpeningTheSosFormDoNotPublish() {
        compose.setContent { GhostTheme(darkTheme = false) { GhostApp(FoundationPreviewFixtures.loaded, {}) } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Send SOS").performScrollTo().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("New SOS").assertIsDisplayed()
        compose.onNodeWithText("This is a preview. Requests can't be saved.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Requests").performClick().assertIsSelected()
        compose.onNodeWithText("Saved requests").assertIsDisplayed()
        compose.onNodeWithText("Saved on this phone only").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Help status").performClick()
        compose.onNodeWithText("Online receipt not confirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Assistance confirmed").assertDoesNotExist()

        compose.onNode(hasText("Connections") and hasClickAction()).performClick()
        compose.onNodeWithText("Connect to GHOST").performScrollTo().assertIsDisplayed()

    }

    @Test
    fun operatorSessionStatesNeverExposePhoneApprovalControls() {
        val trust = mutableStateOf(DeviceTrustState())
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, trust.value) } }
        compose.onNode(hasText("Connections") and hasClickAction()).performClick()
        compose.onNodeWithText("Device signing identity").assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Technical details"))
        compose.onNodeWithText("Technical details").performClick()
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
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Hide technical details"))
        compose.onNodeWithText("Hide technical details").performClick()
        compose.onNodeWithText("Device signing identity").assertDoesNotExist()
    }

    @Test
    fun sharingChoicesRemainIndependentWithoutAnExtraReviewScreen() {
        var saved: SosDraft? = null
        val draft = SosDraft(publicSharing = true)
        val actions = LocalActions({}, { _, done -> done() }, { value, done -> saved = value; done() }, {}, {}, {})
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, local = LocalUiState(draft = draft), actions = actions) } }
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        compose.onNodeWithText("Sharing settings").performScrollTo().performClick()
        compose.onNodeWithText("Share with nearby phones").performScrollTo().assertIsOn()
        compose.onNodeWithText("Allow online help coordination").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Share with nearby phones").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Allow online help coordination").performScrollTo().assertIsOn()
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
        compose.onNodeWithText("Connect to GHOST").performScrollTo().performClick()
        assertEquals(1, starts)
        compose.runOnIdle { local.value = local.value.copy(network = org.ghost.transport.nearby.NearbyState(active = true)) }
        compose.onNodeWithText("Looking for nearby phones…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Codes match").assertDoesNotExist()
        compose.onNodeWithText("Disconnect from GHOST").performScrollTo().performClick()
        assertEquals(1, stops)
        assertEquals(0, publishes)
    }

    @Test
    fun nearbyGraphTracksConnectionsIdentityChecksAndDisconnectWithoutInventingRoutes() {
        val state = mutableStateOf(FoundationState())
        val local = mutableStateOf(LocalUiState(network = NearbyState(active = true)))
        compose.setContent { GhostTheme { GhostApp(state.value, {}, local = local.value) } }
        compose.onNode(hasText("Connections") and hasClickAction()).performClick()
        compose.onNodeWithTag("nearby-mesh-graph").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("0 phones connected to you", substring = true).assertExists()
        val a = PeerHandle("nearby", "A"); val b = PeerHandle("nearby", "B")
        compose.runOnIdle {
            state.value = state.value.copy(peers = listOf(a, b))
            local.value = local.value.copy(peerIdentities = mapOf("A" to "a".repeat(64)))
        }
        compose.onNodeWithContentDescription("2 phones connected to you: 1 ready to pass along requests, 1 getting connected", substring = true).assertExists()
        compose.onNodeWithText("Getting connected…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { local.value = local.value.copy(peerIdentities = mapOf("A" to "a".repeat(64), "B" to "b".repeat(64))) }
        compose.onNodeWithContentDescription("2 phones connected to you: 2 ready to pass along requests, 0 getting connected", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(peers = listOf(b)) }
        compose.onNodeWithContentDescription("1 phone connected to you: 1 ready to pass along requests", substring = true).assertExists()
        compose.onNodeWithText("Phone aaaaaaaa · ready to share").assertDoesNotExist()
        // Stop hides stale peer/identity snapshots immediately.
        compose.runOnIdle { local.value = local.value.copy(network = NearbyState()) }
        compose.onNodeWithContentDescription("0 phones connected to you", substring = true).assertExists()
        compose.onNodeWithText("Phone bbbbbbbb · ready to share").assertDoesNotExist()
    }

    @Test
    fun newUiKeepsVerifiedServerReceiptsAndGatewaySetupAccessible() {
        val report = FoundationPreviewFixtures.loaded.requests.single().copy(createdHere = true, cloudState = LocalCloudState.ACCEPTED)
        val state = FoundationPreviewFixtures.loaded.copy(simulated = false, requests = listOf(report),
            mission = CloudCoordinationState(CloudCoordinationStatus.UPLOADED))
        val actions = LocalActions({}, { _, done -> done() }, { _, _ -> }, {}, {}, {},
            saveGateway = { _, _, done -> done() })
        compose.setContent { GhostTheme { GhostApp(state, {},
            local = LocalUiState(gateway = GatewayUiState(cloudKeyPinned = true)), actions = actions) } }
        compose.onNodeWithText("Requests").performClick()
        compose.onNodeWithText("Received by server · receipt verified. Help is not confirmed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Help status").performClick()
        compose.onNodeWithText("Received by server").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Assistance confirmed").assertDoesNotExist()
        compose.onNode(hasText("Connections") and hasClickAction()).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Configure gateway"))
        compose.onNodeWithText("Configure gateway").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
    }
}
