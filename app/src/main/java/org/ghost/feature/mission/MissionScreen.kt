package org.ghost.feature.mission

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.label

@Composable
fun MissionScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                  contentPadding: PaddingValues = PaddingValues(0.dp)) {
    GhostScreen("Cloud mission", "Coordination status, with people in control.", modifier, contentPadding) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> item("status") {
                InfoCard(state.mission.status.label(), "Simulated status. No live coordinator is running in this preview.") {
                    state.mission.message?.let { Text(it) }
                    state.mission.version?.let { Text("Proposal version $it") }
                }
            }
        }
        item("policy") {
            InfoCard("Human approval comes first", "An operator reviews proposals in the web dashboard. Offer owners accept or decline their own offers.")
        }
        item("receipt") {
            InfoCard("Keep each step clear", "Cloud acceptance, operator approval, phone receipt and confirmed assistance are separate steps.")
        }
        item("offline") {
            InfoCard("When the cloud is unavailable", "Coordination waits for an internet gateway. There is no coordinator running on this phone.")
        }
    }
}
