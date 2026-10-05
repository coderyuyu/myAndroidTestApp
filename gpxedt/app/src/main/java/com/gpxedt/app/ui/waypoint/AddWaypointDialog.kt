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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpxedt.app.data.repository.GeocodingRepositoryImpl
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.util.GpxDateTimeFormatter
import java.time.Instant
import java.util.Locale

/**
 * Dialog for adding a new Waypoint with automated information prefilling:
 * 1. Timestamp synchronization: Inherits from trackpoint if selected/on-route, else defaults to Instant.now().
 * 2. Reverse geocoding: Asynchronously resolves place names off Main thread, showing micro progress indicator.
 * 3. Dirty tracking: Respects user-entered name without overriding manual edits.
 * 4. Symbol selector: Strictly restricted to canonical flag variants (Red, Yellow, Green).
 */
@Composable
fun AddWaypointDialog(
    location: Pair<Double, Double>?,
    initialWaypoint: GpxWaypoint? = null,
    inheritedTrackPoint: TrackPoint? = null,
    inheritedTime: Instant? = null,
    isTimeInheritedFromTrack: Boolean = false,
    inheritedEle: Double? = null,
    photoDistanceMeters: Double? = null,
    onPickPhotoClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, desc: String?, sym: String?, lat: Double, lon: Double, time: Instant?, ele: Double?) -> Unit,
    viewModel: AddWaypointViewModel = viewModel()
) {
    val context = LocalContext.current.applicationContext

    // Initialize ViewModel state on composition or parameters change
    LaunchedEffect(location, initialWaypoint, inheritedTrackPoint, inheritedTime) {
        if (viewModel.geocodingRepository == null) {
            viewModel.setGeocodingRepository(GeocodingRepositoryImpl(context))
        }
        viewModel.initialize(
            lat = location?.first,
            lon = location?.second,
            initialWaypoint = initialWaypoint,
            inheritedTrackPoint = inheritedTrackPoint,
            inheritedTime = inheritedTime,
            isTimeInheritedFromTrack = isTimeInheritedFromTrack,
            inheritedEle = inheritedEle
        )
    }

    val name by viewModel.nameState.collectAsState()
    val isGeocodingLoading by viewModel.isGeocodingLoading.collectAsState()
    val description by viewModel.descriptionState.collectAsState()
    val symbol by viewModel.symbolState.collectAsState()
    val latText by viewModel.latTextState.collectAsState()
    val lonText by viewModel.lonTextState.collectAsState()
    val selectedTime by viewModel.selectedTime.collectAsState()
    val isTimeFromTrack by viewModel.isTimeInheritedFromTrack.collectAsState()
    val isNameError by viewModel.isNameError.collectAsState()
    val isCoordError by viewModel.isCoordError.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Top Header Row with Cancel and Save buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = if (initialWaypoint != null) "Add Photo Waypoint (新增照片航點)" else "Add Waypoint (新增航點)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = {
                            val waypoint = viewModel.buildWaypointIfValid()
                            if (waypoint != null) {
                                onConfirm(
                                    waypoint.name,
                                    waypoint.desc,
                                    waypoint.sym,
                                    waypoint.lat,
                                    waypoint.lon,
                                    waypoint.time,
                                    waypoint.ele
                                )
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }

                HorizontalDivider()

                // Form Fields (Scrollable middle section)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Photo Route Proximity Status Badge
                    if (photoDistanceMeters != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
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

                    // Timestamp Synchronized Status & Preview Card
                    val timeBadgeTitle = when {
                        initialWaypoint?.time != null -> "Photo Time (照片時間)"
                        isTimeFromTrack -> "Track Time (軌跡時間 - 繼承)"
                        else -> "Timestamp (系統時間)"
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTimeFromTrack) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Timestamp",
                                tint = if (isTimeFromTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "$timeBadgeTitle: ${GpxDateTimeFormatter.formatLocalDisplay(selectedTime)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "GPX UTC: ${GpxDateTimeFormatter.formatIsoUtc(selectedTime)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Name field with reverse geocoding micro-indicator
                    OutlinedTextField(
                        value = name,
                        onValueChange = { viewModel.onNameChanged(it) },
                        label = { Text("Name (名稱)*") },
                        isError = isNameError,
                        supportingText = {
                            if (isNameError) {
                                Text("Name cannot be empty", color = MaterialTheme.colorScheme.error)
                            } else if (isGeocodingLoading) {
                                Text("Resolving location name...", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        trailingIcon = {
                            if (isGeocodingLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
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
                            onValueChange = { viewModel.onLatChanged(it) },
                            label = { Text("Latitude (緯度)*") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lonText,
                            onValueChange = { viewModel.onLonChanged(it) },
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
                        onValueChange = { viewModel.onDescriptionChanged(it) },
                        label = { Text("Description (描述)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Standard Flag Symbol Selector (strictly restricted to Flag Red, Flag Yellow, Flag Green)
                    Text(
                        text = "Symbol (航點標誌)*",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GpxWaypoint.STANDARD_FLAG_SYMBOLS.forEach { flagOption ->
                            val isSelected = symbol == flagOption
                            val flagColor = GpxWaypoint.getFlagColor(flagOption)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.onSymbolChanged(flagOption) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Flag,
                                        contentDescription = flagOption,
                                        tint = flagColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = flagOption,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                border = if (isSelected) BorderStroke(1.5.dp, flagColor) else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = flagColor.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Select from Photo EXIF button relocated to the very bottom below all input fields
                    if (onPickPhotoClick != null) {
                        Spacer(modifier = Modifier.height(4.dp))
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
                            Text("Select from Photo EXIF")
                        }
                    }
                }
            }
        }
    }
}
