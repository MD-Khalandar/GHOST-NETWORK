package org.ghost.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.ghost.design.GhostTheme
import org.ghost.feature.foundation.FoundationPreviewFixtures
import org.ghost.feature.foundation.FoundationState
import org.junit.Assert.assertEquals
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
    fun allDestinationsRenderAndSosOnlyOpensAnExplicitPreview() {
        compose.setContent { GhostTheme(darkTheme = false) { GhostApp(FoundationPreviewFixtures.loaded, {}) } }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Send SOS").performScrollTo().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("SOS preview").assertIsDisplayed()
        compose.onNodeWithText("Sending is unavailable in this preview.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close preview").performClick()
        screenshot("home-light")
        compose.onNodeWithText("Requests").performClick().assertIsSelected()
        compose.onNodeWithText("Requests & offers").assertIsDisplayed()
        compose.onNodeWithText("Local report · Open").performScrollTo().assertIsDisplayed()
        screenshot("requests-light")
        compose.onNodeWithText("Mission").performClick()
        compose.onNodeWithText("Cloud unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resource allocation confirmed").assertDoesNotExist()
        screenshot("mission-light")
        compose.onNodeWithText("Network").performClick()
        compose.onNodeWithText("Radio simulation").performScrollTo().assertIsDisplayed()
        screenshot("network-light")
    }

    @Test
    fun selectedRouteAndOpenPreviewSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GhostTheme { GhostApp(FoundationPreviewFixtures.loaded, {}) } }
        compose.onNodeWithText("Requests").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Requests").assertIsSelected()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Send SOS").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("SOS preview").assertIsDisplayed()
        compose.onNodeWithText("Close preview").performClick()
        compose.onNodeWithText("SOS preview").assertDoesNotExist()
    }

    @Test
    fun darkTwoHundredPercentTextKeepsSosAndEveryTabReachable() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                GhostTheme(darkTheme = true) { GhostApp(FoundationPreviewFixtures.loaded, {}) }
            }
        }
        compose.onNodeWithText("Send SOS").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Close preview").assertIsDisplayed().performClick()
        screenshot("home-dark-200")
        for (destination in listOf("Requests", "Mission", "Network", "Home")) {
            compose.onNodeWithText(destination).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp).performClick().assertIsSelected()
            screenshot("${destination.lowercase()}-dark-200")
        }
    }

    @Test
    fun retryRecoversFromErrorAndEmptyRequestsRemainHonest() {
        val state = mutableStateOf(FoundationState(failed = true))
        var retries = 0
        compose.setContent { GhostTheme { GhostApp(state.value, { retries++; state.value = FoundationPreviewFixtures.empty }) } }
        compose.onNodeWithText("Requests").performClick()
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.onNodeWithText("No local requests yet").assertIsDisplayed()
        assertEquals(1, retries)
        screenshot("requests-empty")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-mdpi")
    fun landscapeContentScrollsAboveNavigation() {
        compose.setContent { GhostTheme { Surface { GhostApp(FoundationPreviewFixtures.loaded, {}) } } }
        compose.onNodeWithText("Send SOS").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Close preview").performClick()
        compose.onNodeWithText("Network").performClick().assertIsSelected()
        compose.onNodeWithText("Gateway sharing is off").performScrollTo().assertIsDisplayed()
        screenshot("network-landscape")
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/cp01-ui/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
