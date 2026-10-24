package org.ghost.feature.foundation

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.ghost.design.GhostTheme
import org.ghost.domain.model.*
import org.ghost.feature.home.HomeScreen
import org.ghost.feature.home.OfferHelpScreen
import org.ghost.feature.network.NetworkScreen
import org.ghost.app.LocalUiState
import org.ghost.transport.nearby.NearbyState
import org.ghost.feature.requests.RequestsScreen
import org.ghost.gateway.api.GatewayReachability
import org.ghost.transport.api.PeerHandle

/** Presentation fixtures; previews never start I/O. */
object FoundationPreviewFixtures {
    val empty = FoundationState(cloud = GatewayReachability.OFFLINE, simulated = true)
    val loaded = empty.copy(
        requests = listOf(DashboardSosSummary(
            RequestId("11111111-1111-4111-8111-111111111111"), SosCategory.WATER, SosState.OPEN,
            "Two people need drinking water near the marked community hall.", 2, "Sector 4",
            NodeId("b".repeat(64)), 1792859000000, 1792859000000, false,
        )),
        offers = listOf(OfferSummary(
            OfferId("22222222-2222-4222-8222-222222222222"), NodeId("a".repeat(64)),
            ResourceType.WATER, 20, "liters", "Sector 4", 1792861200000,
            OfferState.AVAILABLE, 1792859000000, false,
        )),
    )
    val stale = loaded.copy(requests = loaded.requests.map { it.copy(stale = true) },
        offers = loaded.offers.map { it.copy(stale = true, state = OfferState.EXPIRED) })
    val connected = loaded.copy(peers = listOf(PeerHandle("simulation", "B")), cloud = GatewayReachability.ONLINE)
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) = GhostTheme { Surface { content() } }

@Preview(name = "Home · loaded", showBackground = true, widthDp = 360, heightDp = 800)
@Preview(name = "Home · dark · 200% text", showBackground = true, widthDp = 360, heightDp = 800,
    fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomePreview() = PreviewFrame { HomeScreen(FoundationPreviewFixtures.loaded, {}, {}, {}, {}) }

@Preview(name = "Home · loading", widthDp = 360, heightDp = 800)
@Composable
private fun LoadingPreview() = PreviewFrame { HomeScreen(FoundationState(loading = true), {}, {}, {}, {}) }

@Preview(name = "Requests · loaded", widthDp = 360, heightDp = 800)
@Preview(name = "Requests · landscape", widthDp = 800, heightDp = 360)
@Composable
private fun RequestsPreview() = PreviewFrame { RequestsScreen(FoundationPreviewFixtures.loaded, {}) }

@Preview(name = "Requests · empty", widthDp = 360, heightDp = 800)
@Composable
private fun EmptyPreview() = PreviewFrame { RequestsScreen(FoundationPreviewFixtures.empty, {}) }

@Preview(name = "Requests · stale", widthDp = 360, heightDp = 800)
@Composable
private fun StalePreview() = PreviewFrame { RequestsScreen(FoundationPreviewFixtures.stale, {}) }

@Preview(name = "Offer help", widthDp = 360, heightDp = 800)
@Composable
private fun OfferHelpPreview() = PreviewFrame { OfferHelpScreen(LocalUiState(), null, simulated = true) }

@Preview(name = "Network · error", widthDp = 360, heightDp = 800)
@Composable
private fun ErrorPreview() = PreviewFrame { NetworkScreen(FoundationState(failed = true), {}) }

@Preview(name = "Network · connected fixture", widthDp = 360, heightDp = 800)
@Composable
private fun NetworkPreview() = PreviewFrame {
    NetworkScreen(FoundationPreviewFixtures.connected, {}, local = LocalUiState(
        network = NearbyState(active = true), peerIdentities = mapOf("B" to "b".repeat(64))))
}
