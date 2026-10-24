package org.ghost.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.ghost.feature.foundation.FoundationViewModel
import org.ghost.domain.model.CloudCoordinationStatus
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
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherUsesEmptyRealStorageAndRetainsItsGraphAcrossRecreation() {
        compose.waitUntil(5_000) {
            (compose.activity.application as GhostApplication).graph.local.value.draft != null &&
                !ViewModelProvider(compose.activity)[FoundationViewModel::class.java].state.value.loading
        }
        val graph = (compose.activity.application as GhostApplication).graph
        compose.onNodeWithText("Requests").performClick()
        compose.onNodeWithText("No requests yet").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Requests").assertIsSelected()
        compose.runOnIdle {
            assertSame(graph, (compose.activity.application as GhostApplication).graph)
            val state = ViewModelProvider(compose.activity)[FoundationViewModel::class.java].state.value
            assertEquals(0, state.requests.size)
            assertEquals(0, state.offers.size)
            assertEquals(CloudCoordinationStatus.NOT_CONNECTED, state.mission.status)
        }
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        compose.onNodeWithText("New SOS").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            assertEquals(0, ViewModelProvider(compose.activity)[FoundationViewModel::class.java].state.value.requests.size)
        }
    }
}
