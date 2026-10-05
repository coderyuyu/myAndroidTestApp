package com.gpxedt.app.ui.waypoint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.Waypoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dialog for editing or creating a Waypoint, supporting name, description,
 * canonical flag symbol variant (Red, Yellow, Green), coordinates, elevation, and timestamp.
 */
@Composable
fun EditWaypointDialog(
    waypoint: Waypoint,
    title: String = "Edit Waypoint (編輯航點)",
    onDismiss: () -> Unit,
    onConfirm: (Waypoint) -> Unit
) {
    var name by remember(waypoint) { mutableStateOf(waypoint.name) }
    var description by remember(waypoint) { mutableStateOf(waypoint.desc ?: "") }
    var symbol by remember(waypoint) {
        mutableStateOf(waypoint.sym?.let { GpxWaypoint.normalizeSymbol(it) } ?: GpxWaypoint.SYM_FLAG_RED)
    }

    var latText by remember(waypoint) {
        mutableStateOf("%.6f".format(Locale.US, waypoint.lat))
    }
    var lonText by remember(waypoint) {
        mutableStateOf("%.6f".format(Locale.US, waypoint.lon))
    }

    var isNameError by remember { mutableStateOf(false) }
    var isCoordError by remember { mutableStateOf(false) }

    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (isNameError && it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name (名稱) *") },
                    isError = isNameError,
                    supportingText = if (isNameError) {
                        { Text("Name cannot be empty") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Symbol selection: Canonical Flags
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Waypoint Symbol (航點符號圖標)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val flagVariants = listOf(
                            Triple(GpxWaypoint.SYM_FLAG_RED, "Red Flag", androidx.compose.ui.graphics.Color(0xFFE53935)),
                            Triple(GpxWaypoint.SYM_FLAG_YELLOW, "Yellow Flag", androidx.compose.ui.graphics.Color(0xFFFBC02D)),
                            Triple(GpxWaypoint.SYM_FLAG_GREEN, "Green Flag", androidx.compose.ui.graphics.Color(0xFF43A047))
                        )

                        flagVariants.forEach { (symKey, label, flagColor) ->
                            val isSelected = symbol == symKey
                            FilterChip(
                                selected = isSelected,
                                onClick = { symbol = symKey },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Flag,
                                        contentDescription = label,
                                        tint = flagColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = flagColor.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, flagColor) else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (備註說明)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                // Coordinates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = {
                            latText = it
                            isCoordError = false
                        },
                        label = { Text("Latitude (緯度)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = isCoordError,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = {
                            lonText = it
                            isCoordError = false
                        },
                        label = { Text("Longitude (經度)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = isCoordError,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isCoordError) {
                    Text(
                        text = "Invalid coordinates: Lat [-90, 90], Lon [-180, 180]",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Read-only Elevation and Time metadata display
                if (waypoint.ele != null || waypoint.time != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        waypoint.ele?.let { ele ->
                            Text(
                                text = "Elevation: %.1f m".format(Locale.US, ele),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        waypoint.time?.let { time ->
                            Text(
                                text = "Time: ${timeFormatter.format(time)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        isNameError = true
                        return@Button
                    }

                    val lat = latText.toDoubleOrNull()
                    val lon = lonText.toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        isCoordError = true
                        return@Button
                    }

                    val updatedWaypoint = waypoint.copy(
                        lat = lat,
                        lon = lon,
                        name = trimmedName,
                        desc = description.trim().ifEmpty { null },
                        sym = symbol
                    )
                    onConfirm(updatedWaypoint)
                }
            ) {
                Text("Save (儲存)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel (取消)")
            }
        }
    )
}
