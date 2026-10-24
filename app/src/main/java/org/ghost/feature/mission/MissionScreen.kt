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
    GhostScreen("Help status", "Follow progress on arranging help.", modifier, contentPadding, state.simulated) {
        when {
            state.loading -> item { LoadingCard() }
            state.failed -> item { ErrorCard(onRetry) }
            else -> item("status") {
                InfoCard(state.mission.status.label(), if (state.simulated) "Example status." else "Arranging help online isn't available yet. Requests remain saved on this phone.") {
                    state.mission.message?.let { Text(it.userMessage()) }
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
                Text(if (showExplanation) "Hide explanation" else "How getting help works")
            }
        }
        if (showExplanation) {
            item("policy") {
                InfoCard("How help will be arranged", "When online help is available, a coordinator will review suggestions. People offering help will choose whether to accept.") {
                    Text("Saving or sharing a request does not mean help has been confirmed.")
                    Text("Arranging help online will need a phone with internet access. Nearby phones can still pass along requests without internet.")
                }
            }
        }
    }
}
