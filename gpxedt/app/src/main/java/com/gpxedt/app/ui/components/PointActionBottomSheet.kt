package com.gpxedt.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpxedt.app.model.TrackPoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Modal bottom sheet presented upon tapping a trackpoint vertex.
 * Offers Move (drag mode), Delete (removes point with 2-point minimum guard),
 * and Set as WPT (converts to waypoint and launches the edit dialog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointActionBottomSheet(
    pointIndex: Int,
    point: TrackPoint,
    totalPointsCount: Int,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSetAsWptClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canDelete = totalPointsCount > 2

    val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Point Information
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trackpoint #${pointIndex + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Total $totalPointsCount pts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Coordinate summary
                Text(
                    text = "Lat: %.6f, Lon: %.6f".format(Locale.US, point.lat, point.lon),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Elevation & Time metadata if present
                if (point.ele != null || point.time != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        point.ele?.let { ele ->
                            Text(
                                text = "Ele: %.1f m".format(Locale.US, ele),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        point.time?.let { time ->
                            Text(
                                text = "Time: ${timeFormatter.format(time)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Action Items
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1. Move
                ActionRowItem(
                    icon = Icons.Default.OpenWith,
                    title = "Move (移動座標)",
                    subtitle = "Drag this vertex to a new location on the map",
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = onMoveClick
                )

                // 2. Set as WPT
                ActionRowItem(
                    icon = Icons.Default.Flag,
                    title = "Set as WPT (設為航點)",
                    subtitle = "Convert point to waypoint and open edit dialog",
                    iconTint = MaterialTheme.colorScheme.secondary,
                    onClick = onSetAsWptClick
                )

                // 3. Delete
                ActionRowItem(
                    icon = Icons.Default.Delete,
                    title = "Delete (刪除節點)",
                    subtitle = if (canDelete) {
                        "Remove vertex and reconnect neighboring points"
                    } else {
                        "Cannot delete: Route requires at least 2 points"
                    },
                    iconTint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    enabled = canDelete,
                    onClick = onDeleteClick
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1.0f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
