package com.gpxedt.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpxedt.app.network.OsrmRoutingApi

@Composable
fun ServerSettingsDialog(
    currentServerUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var serverUrlText by remember { mutableStateOf(currentServerUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Routing Server (OSRM)")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Using Open Source Routing Machine (OSRM) from https://project-osrm.org/. No API key is required!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = serverUrlText,
                    onValueChange = { serverUrlText = it },
                    label = { Text("OSRM Server Base URL") },
                    placeholder = { Text(OsrmRoutingApi.DEFAULT_OSRM_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { serverUrlText = OsrmRoutingApi.DEFAULT_OSRM_URL },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset to Default (project-osrm.org)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(serverUrlText) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
