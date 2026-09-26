package com.gpxedt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpxedt.app.model.RoutingProfile
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.ui.theme.AccentOrange
import com.gpxedt.app.ui.theme.PrimaryBlue
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BottomControlPanel(
    pointsCount: Int,
    startPointerIndex: Int,
    middlePointerIndex: Int,
    endPointerIndex: Int,
    startPoint: TrackPoint?,
    middlePoint: TrackPoint?,
    endPoint: TrackPoint?,
    routingProfile: RoutingProfile,
    totalDistanceMeters: Double,
    isLoading: Boolean,
    canSpan: Boolean,
    canReset: Boolean,
    waypointsCount: Int,
    onStartPointerChanged: (Int) -> Unit,
    onMiddlePointerChanged: (Int) -> Unit,
    onEndPointerChanged: (Int) -> Unit,
    onPointerMoving: (Int) -> Unit,
    onSpan: () -> Unit,
    onReset: () -> Unit,
    onReplaceRoute: () -> Unit,
    onAddWaypointClick: () -> Unit,
    onAddPhotoWaypointClick: () -> Unit,
    onWaypointListClick: () -> Unit,
    onProfileChanged: (RoutingProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // Ensures buttons are never obscured by Android navigation bar
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
        ) {
            // Summary row: points count and total distance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Track: $pointsCount pts | %.2f km".format(Locale.US, totalDistanceMeters / 1000.0),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Range: [$startPointerIndex → $endPointerIndex]",
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3-Pointer Slider: Left (Start - Green), Middle (WPT - Blue), Right (End - Red)
            if (pointsCount > 1) {
                ThreePointerSlider(
                    startIndex = startPointerIndex,
                    middleIndex = middlePointerIndex,
                    endIndex = endPointerIndex,
                    maxIndex = pointsCount - 1,
                    onStartChanged = onStartPointerChanged,
                    onMiddleChanged = onMiddlePointerChanged,
                    onEndChanged = onEndPointerChanged,
                    onPointerMoving = onPointerMoving
                )
            }

            // Middle / WPT Point Info Card
            if (middlePoint != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(MiddlePointerColor, RoundedCornerShape(2.dp))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "WPT Pointer #$middlePointerIndex",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MiddlePointerColor
                                )
                            }
                            val eleText = middlePoint.ele?.let { "%.1f m".format(Locale.US, it) } ?: "N/A"
                            Text(
                                text = "Ele: $eleText",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Lat: %.6f, Lon: %.6f".format(Locale.US, middlePoint.lat, middlePoint.lon),
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp
                            )
                            val timeText = middlePoint.time?.let { timeFormatter.format(it) } ?: "No Time"
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Routing Profile selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Profile:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                FilterChip(
                    selected = routingProfile == RoutingProfile.FOOT_HIKING,
                    onClick = { onProfileChanged(RoutingProfile.FOOT_HIKING) },
                    label = { Text("Foot", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = routingProfile == RoutingProfile.CYCLING_REGULAR,
                    onClick = { onProfileChanged(RoutingProfile.CYCLING_REGULAR) },
                    label = { Text("Bike", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = routingProfile == RoutingProfile.DRIVING_CAR,
                    onClick = { onProfileChanged(RoutingProfile.DRIVING_CAR) },
                    label = { Text("Car", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Span Button (crops track to current Start..End pointers)
                Button(
                    onClick = onSpan,
                    enabled = canSpan,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                ) {
                    Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Span (設為新起終)", fontSize = 11.sp)
                }

                // Reset Button (resets slide bar to original span)
                OutlinedButton(
                    onClick = onReset,
                    enabled = canReset
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset (重置)", fontSize = 11.sp)
                }

                // Route Replace (between Start pointer and End pointer)
                val canReroute = startPointerIndex < endPointerIndex
                Button(
                    onClick = onReplaceRoute,
                    enabled = canReroute && !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Route Replace (重繞)", fontSize = 11.sp)
                }

                // Add Waypoint (adds at the Middle pointer location)
                Button(
                    onClick = onAddWaypointClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MiddlePointerColor)
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add WPT (新增航點)", fontSize = 11.sp)
                }

                // Add Photo Waypoint (reads EXIF, checks route, and adds)
                Button(
                    onClick = onAddPhotoWaypointClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Photo WPT (照片航點)", fontSize = 11.sp)
                }

                // List Waypoints
                OutlinedButton(
                    onClick = onWaypointListClick
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("WPT List ($waypointsCount)", fontSize = 11.sp)
                }
            }
        }
    }
}
