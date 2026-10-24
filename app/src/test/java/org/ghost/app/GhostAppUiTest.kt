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
        compose.onNodeWithText("Create SOS").assertIsDisplayed()
        compose.onNodeWithText("UI preview: saving is disabled.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithText("Requests").performClick().assertIsSelected()
        compose.onNodeWithText("Saved requests").assertIsDisplayed()
        compose.onNodeWithText("Saved on this phone only").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Mission").performClick()
        compose.onNodeWithText("Cloud unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resource allocation confirmed").assertDoesNotExist()

        compose.onNode(hasText("Network") and hasClickAction()).performClick()
        compose.onNodeWithText("Nearby phones").performScrollTo().assertIsDisplayed()

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
    fun publicSharingAndCloudConsentDefaultOffAndRemainIndependent() {
        var saved: SosDraft? = null
        val draft = SosDraft()
        val actions = LocalActions({}, { _, done -> done() }, { draft, done -> saved = draft; done() }, {}, {}, {}, { _, _ -> }, {}, {})
        compose.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}, local = LocalUiState(draft = draft), actions = actions) } }
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        compose.onNodeWithText("Share publicly with nearby phones").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Allow cloud coordination").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Share publicly with nearby phones").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Allow cloud coordination").performScrollTo().assertIsOn()
        compose.onNodeWithText("What help do you need?").performScrollTo().performTextInput("Need drinking water")
        compose.onNodeWithText("Review SOS").performClick()
        assertNull(saved)
        compose.onNodeWithText("Save SOS").performClick()
        assertEquals("Need drinking water", saved!!.description)
        assertFalse(saved!!.publicSharing)
        assertTrue(saved!!.cloudConsent)
    }
}
