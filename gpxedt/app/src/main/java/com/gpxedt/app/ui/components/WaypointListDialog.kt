package com.gpxedt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.ui.theme.WaypointMarkerColor
import java.util.Locale

@Composable
fun WaypointListDialog(
    waypoints: List<Waypoint>,
    onDismiss: () -> Unit,
    onLocate: (Waypoint) -> Unit,
    onEdit: (Waypoint) -> Unit,
    onDelete: (Waypoint) -> Unit,
    onAddNew: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredWaypoints = remember(waypoints, searchQuery) {
        if (searchQuery.isBlank()) {
            waypoints
        } else {
            waypoints.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        (it.desc?.contains(searchQuery, ignoreCase = true) == true) ||
                        (it.sym?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    var waypointToDelete by remember { mutableStateOf<Waypoint?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Waypoints (${waypoints.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                // Search field
                if (waypoints.size > 3) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search waypoints...", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                if (filteredWaypoints.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (waypoints.isEmpty()) "No waypoints in this GPX file." else "No matching waypoints found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredWaypoints) { wpt ->
                            WaypointCardItem(
                                waypoint = wpt,
                                onLocate = { onLocate(wpt) },
                                onEdit = { onEdit(wpt) },
                                onDelete = { waypointToDelete = wpt }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddNew) {
                    Icon(Icons.Default.AddLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add New")
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )

    // Delete confirmation subdialog
    if (waypointToDelete != null) {
        val target = waypointToDelete!!
        AlertDialog(
            onDismissRequest = { waypointToDelete = null },
            title = { Text("Delete Waypoint") },
            text = { Text("Are you sure you want to delete waypoint \"${target.name}\"? You can undo this action.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(target)
                        waypointToDelete = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { waypointToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun WaypointCardItem(
    waypoint: Waypoint,
    onLocate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onLocate() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Marker badge (Dark Green Triangle)
            Surface(
                color = WaypointMarkerColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2f, 2f)
                            lineTo(size.width - 2f, size.height - 2f)
                            lineTo(2f, size.height - 2f)
                            close()
                        }
                        drawPath(path, color = WaypointMarkerColor)
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = waypoint.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Lat: %.5f, Lon: %.5f".format(Locale.US, waypoint.lat, waypoint.lon) +
                            (waypoint.ele?.let { " | %.1fm".format(Locale.US, it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!waypoint.desc.isNullOrBlank()) {
                    Text(
                        text = waypoint.desc,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!waypoint.sym.isNullOrBlank()) {
                    Text(
                        text = "Sym: ${waypoint.sym}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = onLocate,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PinDrop,
                        contentDescription = "Locate",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
