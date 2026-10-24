package org.ghost.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun GhostScreen(title: String, subtitle: String, modifier: Modifier = Modifier,
                contentPadding: PaddingValues = PaddingValues(0.dp), content: LazyListScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().consumeWindowInsets(contentPadding),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction) + 24.dp,
                top = contentPadding.calculateTopPadding() + 24.dp,
                end = contentPadding.calculateEndPadding(direction) + 24.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("simulation") { SimulationNotice() }
            content()
        }
    }
}

@Composable
fun SimulationNotice() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
        Text("Simulated preview · no messages are sent", modifier = Modifier.fillMaxWidth().padding(16.dp),
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
fun LoadingCard() = InfoCard("Loading local information", "Your reports will appear here when ready.") {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
fun ErrorCard(onRetry: () -> Unit) = InfoCard("Couldn’t load local information", "Try again to refresh this preview.") {
    OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
}
