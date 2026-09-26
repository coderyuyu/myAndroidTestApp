package com.gpxedt.app.ui.components

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gpxedt.app.model.Waypoint

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val COMMON_SYMBOLS = listOf("Flag, Red", "Summit", "Campground", "Water Source", "Danger Area", "Trailhead", "Scenic Area")

@Composable
fun AddWaypointDialog(
    location: Pair<Double, Double>?,
    initialWaypoint: Waypoint? = null,
    photoDistanceMeters: Double? = null,
    onPickPhotoClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, desc: String?, sym: String?, lat: Double, lon: Double, time: Instant?, ele: Double?) -> Unit
) {
    var name by remember(initialWaypoint) { mutableStateOf(initialWaypoint?.name ?: "") }
    var description by remember(initialWaypoint) { mutableStateOf(initialWaypoint?.desc ?: "") }
    var symbol by remember(initialWaypoint) { mutableStateOf(initialWaypoint?.sym ?: "Flag, Red") }

    // Resolve initial coordinates (initialWaypoint has highest precedence)
    val defaultLat = initialWaypoint?.lat ?: location?.first
    val defaultLon = initialWaypoint?.lon ?: location?.second

    var latText by remember(initialWaypoint, location) {
        val lat = defaultLat
        mutableStateOf(if (lat != null && !(lat == 0.0 && (defaultLon ?: 0.0) == 0.0)) "%.6f".format(Locale.US, lat) else "")
    }
    var lonText by remember(initialWaypoint, location) {
        val lon = defaultLon
        mutableStateOf(if (lon != null && !(lon == 0.0 && (defaultLat ?: 0.0) == 0.0)) "%.6f".format(Locale.US, lon) else "")
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
                text = if (initialWaypoint != null) "Add Photo Waypoint (新增照片航點)" else "Add Waypoint (新增航點)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Select from Photo button
                if (onPickPhotoClick != null) {
                    OutlinedButton(
                        onClick = onPickPhotoClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Select Photo",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select from Photo EXIF (從照片選取)")
                    }
                }

                // Photo Route Proximity Status Badge
                if (photoDistanceMeters != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "On Route",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "On Route (在路線上)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Distance to track: %.1f m".format(Locale.US, photoDistanceMeters),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (initialWaypoint?.time != null) {
                    Text(
                        text = "Photo Time: ${timeFormatter.format(initialWaypoint.time)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name (名稱)*") },
                    isError = isNameError,
                    supportingText = {
                        if (isNameError) Text("Name cannot be empty")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Editable Coordinates
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
                        label = { Text("Latitude (緯度)*") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = {
                            lonText = it
                            isCoordError = false
                        },
                        label = { Text("Longitude (經度)*") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isCoordError) {
                    Text(
                        text = "Invalid Latitude (-90..90) or Longitude (-180..180)",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (描述)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Symbol (符號): $symbol",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    COMMON_SYMBOLS.take(3).forEach { sym ->
                        FilterChip(
                            selected = symbol == sym,
                            onClick = { symbol = sym },
                            label = { Text(sym, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    COMMON_SYMBOLS.drop(3).take(4).forEach { sym ->
                        FilterChip(
                            selected = symbol == sym,
                            onClick = { symbol = sym },
                            label = { Text(sym, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latText.toDoubleOrNull()
                    val lon = lonText.toDoubleOrNull()

                    if (name.isBlank()) {
                        isNameError = true
                    } else if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        isCoordError = true
                    } else {
                        onConfirm(
                            name.trim(),
                            description.trim().ifEmpty { null },
                            symbol.trim().ifEmpty { null },
                            lat,
                            lon,
                            initialWaypoint?.time,
                            initialWaypoint?.ele
                        )
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditWaypointDialog(
    waypoint: Waypoint,
    onDismiss: () -> Unit,
    onConfirm: (updatedWaypoint: Waypoint) -> Unit
) {
    var name by remember { mutableStateOf(waypoint.name) }
    var description by remember { mutableStateOf(waypoint.desc ?: "") }
    var symbol by remember { mutableStateOf(waypoint.sym ?: "Flag, Red") }
    var latText by remember { mutableStateOf(waypoint.lat.toString()) }
    var lonText by remember { mutableStateOf(waypoint.lon.toString()) }
    var isNameError by remember { mutableStateOf(false) }
    var isCoordError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Waypoint (編輯航點)",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name (名稱)*") },
                    isError = isNameError,
                    supportingText = {
                        if (isNameError) Text("Name cannot be empty")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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
                        label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = {
                            lonText = it
                            isCoordError = false
                        },
                        label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (isCoordError) {
                    Text(
                        text = "Invalid Latitude or Longitude format",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (描述)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Symbol (符號): $symbol",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    COMMON_SYMBOLS.take(3).forEach { sym ->
                        FilterChip(
                            selected = symbol == sym,
                            onClick = { symbol = sym },
                            label = { Text(sym, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    COMMON_SYMBOLS.drop(3).take(4).forEach { sym ->
                        FilterChip(
                            selected = symbol == sym,
                            onClick = { symbol = sym },
                            label = { Text(sym, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = latText.toDoubleOrNull()
                    val lon = lonText.toDoubleOrNull()
                    if (name.isBlank()) {
                        isNameError = true
                    } else if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        isCoordError = true
                    } else {
                        val updated = waypoint.copy(
                            name = name.trim(),
                            desc = description.trim().ifEmpty { null },
                            sym = symbol.trim().ifEmpty { null },
                            lat = lat,
                            lon = lon
                        )
                        onConfirm(updated)
                    }
                }
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
