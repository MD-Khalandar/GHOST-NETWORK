package org.ghost.feature.mission

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.ghost.design.*
import org.ghost.feature.foundation.FoundationState
import org.ghost.feature.foundation.label

@Composable
fun MissionScreen(state: FoundationState, onRetry: () -> Unit, modifier: Modifier = Modifier,
                  contentPadding: PaddingValues = PaddingValues(0.dp)) {
    var showExplanation by rememberSaveable { mutableStateOf(false) }
    GhostScreen("Cloud mission", "Your request's coordination status.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> item("status") {
                InfoCard(state.mission.status.label(), if (state.simulated) "Preview status." else "Cloud synchronization is not implemented yet. Reports remain saved on this phone.") {
                    state.mission.message?.let { Text(it) }
                    state.mission.version?.let { Text("Proposal version $it") }
                }
            }
        }
        item("explanation-toggle") {
            TextButton(
                onClick = { showExplanation = !showExplanation },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    stateDescription = if (showExplanation) "Expanded" else "Collapsed"
                },
            ) {
                Text(if (showExplanation) "Hide coordination details" else "How coordination works")
            }
        }
        if (showExplanation) {
            item("policy") {
                InfoCard("People approve assistance", "An operator reviews proposals in the web dashboard. Offer owners accept or decline their own offers.") {
                    Text("Cloud acceptance, operator approval, phone receipt and confirmed assistance are separate steps.")
                    Text("Without an internet gateway, cloud coordination waits. Your phone does not run a coordinator.")
                }
            }
        }
    }
}
