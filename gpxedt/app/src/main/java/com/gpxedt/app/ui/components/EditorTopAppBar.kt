package com.gpxedt.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopAppBar(
    fileName: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    hasFile: Boolean,
    waypointsCount: Int = 0,
    onOpenClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveAsClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onWaypointListClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                text = fileName ?: "GPX Editor",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        actions = {
            // Undo
            IconButton(
                onClick = onUndoClick,
                enabled = canUndo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo"
                )
            }

            // Redo
            IconButton(
                onClick = onRedoClick,
                enabled = canRedo
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo"
                )
            }

            // Waypoints List
            IconButton(
                onClick = onWaypointListClick
            ) {
                BadgedBox(
                    badge = {
                        if (waypointsCount > 0) {
                            Badge { Text("$waypointsCount") }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "Waypoints List"
                    )
                }
            }

            // Open GPX
            IconButton(onClick = onOpenClick) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Open GPX"
                )
            }

            // Save
            IconButton(
                onClick = onSaveClick,
                enabled = hasFile
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save"
                )
            }

            // Save As
            IconButton(
                onClick = onSaveAsClick,
                enabled = hasFile
            ) {
                Icon(
                    imageVector = Icons.Default.SaveAs,
                    contentDescription = "Save As"
                )
            }

            // Routing Server Settings
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Routing Server Settings"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.fillMaxWidth()
    )
}
