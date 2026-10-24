package org.ghost.feature.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.ghost.gateway.GatewayUiState

@Composable
fun GatewaySetupDialog(state: GatewayUiState, busy: Boolean, error: String?, onClose: () -> Unit,
                       onSave: (String, String) -> Unit, onRemove: () -> Unit) {
    var url by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    // Credentials never enter saved-instance state or preview fixtures.
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Cloud gateway setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Only gateway phones need this setup. Joining the mesh lets this phone upload SOS reports whose owners allowed cloud coordination.")
                OutlinedTextField(url, { if (it.length <= 512) url = it }, enabled = !busy, singleLine = true,
                    label = { Text("Server address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { if (it.length <= 256) token = it }, enabled = !busy, singleLine = true,
                    label = { Text("Gateway access token") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (state.configured) Text("Leave the token blank to keep it for the same server.")
                Text(if (state.cloudKeyPinned) "Cloud signing key is configured." else "Uploads are disabled until the server's public signing key is configured in this app build.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.configured) TextButton(onRemove, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove gateway setup") }
            }
        },
        confirmButton = { TextButton({ onSave(url, token.trim()) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Saving…" else "Save setup") } },
        dismissButton = { TextButton(onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
