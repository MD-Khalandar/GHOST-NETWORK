package org.ghost.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun GhostScreen(title: String, subtitle: String, modifier: Modifier = Modifier,
                contentPadding: PaddingValues = PaddingValues(0.dp), simulated: Boolean = false, content: LazyListScope.() -> Unit) {
    // Keep the scroll viewport between app bars so bring-into-view cannot place a
    // focused button behind an opaque bar (especially at 200% text or in landscape).
    Box(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (simulated) item("simulation") { SimulationNotice() }
            content()
        }
    }
}

@Composable
fun SimulationNotice() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
        Text("Preview only · no messages are sent", modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun InfoCard(title: String, detail: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun StatusLabel(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun LoadingCard() = InfoCard("Opening saved requests…", "Your requests will appear here shortly.") {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
fun ErrorCard(onRetry: () -> Unit) = InfoCard("Couldn't open saved requests", "Try again to see the requests on this phone.") {
    OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
}
