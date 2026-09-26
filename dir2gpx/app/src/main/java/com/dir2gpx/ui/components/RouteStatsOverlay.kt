package com.dir2gpx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dir2gpx.model.GpxData
import com.dir2gpx.ui.theme.StatsCardBg

/**
 * Semi-transparent overlay card displaying route statistics on top of the map.
 *
 * Shows:
 * - Total distance (km, formatted to 2 decimal places)
 * - Duration (hours and minutes)
 * - Waypoint count
 * - Track point count
 *
 * @param gpxData The route data containing statistics.
 * @param modifier Modifier for positioning.
 */
@Composable
fun RouteStatsOverlay(
    gpxData: GpxData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(StatsCardBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Title
        Text(
            text = gpxData.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Stats row 1: Distance & Duration
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatItem(
                icon = Icons.Filled.Straighten,
                label = "%.2f km".format(gpxData.totalDistanceKm)
            )
            StatItem(
                icon = Icons.Filled.Schedule,
                label = formatDuration(gpxData.duration.toMillis())
            )
        }

        // Stats row 2: Point counts
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatItem(
                icon = Icons.Filled.LocationOn,
                label = "${gpxData.waypoints.size} waypoints"
            )
            StatItem(
                icon = Icons.Filled.Route,
                label = "${gpxData.trackPoints.size} track pts"
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF90CAF9)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE0E0E0)
        )
    }
}

/**
 * Formats milliseconds into a human-readable duration string.
 */
private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
