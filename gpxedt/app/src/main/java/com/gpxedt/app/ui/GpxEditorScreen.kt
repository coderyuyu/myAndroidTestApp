package com.gpxedt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.gpxedt.app.ui.waypoint.AddWaypointDialog
import com.gpxedt.app.ui.components.BottomControlPanel
import com.gpxedt.app.ui.components.EditWaypointDialog
import com.gpxedt.app.ui.components.EditorTopAppBar
import com.gpxedt.app.ui.components.OpenGpxFileDialog
import com.gpxedt.app.ui.components.OutOfRouteDialog
import com.gpxedt.app.ui.components.ServerSettingsDialog
import com.gpxedt.app.ui.components.WaypointListDialog
import com.gpxedt.app.ui.components.PointActionBottomSheet
import com.gpxedt.app.ui.map.components.MapLayerMenu
import com.gpxedt.app.ui.map.MapViewContainer
import com.gpxedt.app.viewmodel.GpxEditorViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

@Composable
fun GpxEditorScreen(
    viewModel: GpxEditorViewModel,
    onOpenGpxFile: () -> Unit,
    onSaveGpxFile: () -> Unit,
    onSaveAsGpxFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.processSelectedPhoto(context, uri)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        photoPickerLauncher.launch("image/*")
    }

    val launchPhotoPicker = {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        } else {
            photoPickerLauncher.launch("image/*")
        }
    }

    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissUserMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            EditorTopAppBar(
                fileName = uiState.fileName,
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                hasFile = uiState.gpxData.trackPoints.isNotEmpty() || uiState.gpxData.waypoints.isNotEmpty(),
                waypointsCount = uiState.gpxData.waypoints.size,
                onOpenClick = { viewModel.openGpxFileSelectionDialog(context) },
                onSaveClick = {
                    if (uiState.fileUri != null) {
                        viewModel.openSaveConfirmDialog()
                    } else {
                        onSaveGpxFile()
                    }
                },
                onSaveAsClick = onSaveAsGpxFile,
                onUndoClick = { viewModel.undo() },
                onRedoClick = { viewModel.redo() },
                onWaypointListClick = { viewModel.openWaypointListDialog() },
                onSettingsClick = { viewModel.openServerSettingsDialog() }
            )
        },
        bottomBar = {
            if (uiState.gpxData.trackPoints.isNotEmpty() || uiState.gpxData.waypoints.isNotEmpty()) {
                BottomControlPanel(
                    pointsCount = uiState.gpxData.trackPoints.size,
                    startPointerIndex = uiState.startPointerIndex,
                    middlePointerIndex = uiState.middlePointerIndex,
                    endPointerIndex = uiState.endPointerIndex,
                    startPoint = uiState.startPoint,
                    middlePoint = uiState.middlePoint,
                    endPoint = uiState.endPoint,
                    routingProfile = uiState.routingProfile,
                    totalDistanceMeters = uiState.gpxData.totalDistanceMeters,
                    isLoading = uiState.isLoading,
                    canSpan = uiState.canSpan,
                    canReset = uiState.canReset,
                    waypointsCount = uiState.gpxData.waypoints.size,
                    onStartPointerChanged = { viewModel.setStartPointer(it) },
                    onMiddlePointerChanged = { viewModel.setMiddlePointer(it) },
                    onEndPointerChanged = { viewModel.setEndPointer(it) },
                    onPointerMoving = { viewModel.onPointerMoving(it) },
                    onSpan = { viewModel.span() },
                    onReset = { viewModel.reset() },
                    onReplaceRoute = { viewModel.replaceSegmentWithOnlineRoute() },
                    onAddWaypointClick = { viewModel.openAddWaypointForMiddlePointer() },
                    onAddPhotoWaypointClick = launchPhotoPicker,
                    onWaypointListClick = { viewModel.openWaypointListDialog() },
                    onProfileChanged = { viewModel.setRoutingProfile(it) }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Fullscreen Map Container
            MapViewContainer(
                gpxData = uiState.gpxData,
                startPointerIndex = uiState.startPointerIndex,
                middlePointerIndex = uiState.middlePointerIndex,
                endPointerIndex = uiState.endPointerIndex,
                mapBoundsTrigger = uiState.mapBoundsTrigger,
                focusLocation = uiState.focusLocation,
                cameraCenterLocation = uiState.cameraCenterLocation,
                showTrackpoints = uiState.showTrackpoints,
                dragInsertCoordinate = uiState.dragInsertCoordinate,
                dragInsertIndex = uiState.dragInsertProjectedIndex,
                movingVertexPosition = uiState.movingVertexPosition,
                movingVertexIndex = uiState.movingVertexIndex,
                onPointTapped = { viewModel.setMiddlePointer(it) },
                onVertexTapped = { viewModel.onVertexTapped(it) },
                onStartDragInsertion = { lat, lon -> viewModel.startDragInsertion(lat, lon) },
                onUpdateDragInsertion = { lat, lon -> viewModel.updateDragInsertion(lat, lon) },
                onCommitDragInsertion = { lat, lon -> viewModel.commitDragInsertion(lat, lon) },
                onCancelDragInsertion = { viewModel.cancelDragInsertion() },
                onUpdateMovePosition = { lat, lon -> viewModel.updateMoveVertexPosition(lat, lon) },
                onCommitMovePosition = { idx, lat, lon -> viewModel.commitMoveVertex(idx, lat, lon) },
                onCancelMovePosition = { viewModel.cancelMoveVertexMode() },
                onMapLongClick = { lat, lon ->
                    viewModel.openAddWaypointDialog(lat, lon)
                }
            )

            // Map Layer Menu: Show/Hide Trackpoints toggle
            MapLayerMenu(
                showTrackpoints = uiState.showTrackpoints,
                onToggleTrackpoints = { viewModel.toggleShowTrackpoints(it) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )

            // Move Mode Top Indicator & Action Banner
            if (uiState.isMoveVertexMode) {
                androidx.compose.material3.Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.OpenWith,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Move Point #${(uiState.movingVertexIndex ?: 0) + 1} (Drag to relocate)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        androidx.compose.material3.IconButton(
                            onClick = { viewModel.cancelMoveVertexMode() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // If empty track, show onboarding hint
            if (uiState.gpxData.trackPoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No GPX Loaded",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the folder icon at the top to open a GPX file, or long-press the map to add a waypoint.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Loading overlay
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = uiState.loadingMessage ?: "Loading...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.isAddWaypointDialogOpen) {
        AddWaypointDialog(
            location = uiState.pendingWaypointLocation,
            initialWaypoint = uiState.pendingPhotoWaypoint,
            inheritedTime = uiState.pendingWaypointTime,
            isTimeInheritedFromTrack = uiState.isPendingWaypointTimeFromTrack,
            inheritedEle = uiState.pendingWaypointEle,
            photoDistanceMeters = uiState.photoRouteDistanceMeters,
            onPickPhotoClick = launchPhotoPicker,
            onDismiss = { viewModel.dismissAddWaypointDialog() },
            onConfirm = { name, desc, sym, lat, lon, time, ele ->
                viewModel.addWaypoint(name, desc, sym, lat, lon, time, ele)
            }
        )
    }

    val outOfRoute = uiState.outOfRouteInfo
    if (outOfRoute != null) {
        OutOfRouteDialog(
            info = outOfRoute,
            onDismiss = { viewModel.dismissOutOfRouteDialog() }
        )
    }

    if (uiState.isWaypointListDialogOpen) {
        WaypointListDialog(
            waypoints = uiState.gpxData.waypoints,
            currentSortOrder = uiState.waypointSortOrder,
            onSortOrderChange = { viewModel.setWaypointSortOrder(it) },
            onDismiss = { viewModel.dismissWaypointListDialog() },
            onLocate = { viewModel.locateWaypoint(it) },
            onEdit = { viewModel.openEditWaypointDialog(it) },
            onDelete = { viewModel.deleteWaypoint(it) },
            onAddNew = {
                viewModel.dismissWaypointListDialog()
                viewModel.openAddWaypointForMiddlePointer()
            }
        )
    }

    if (uiState.editingWaypoint != null) {
        EditWaypointDialog(
            waypoint = uiState.editingWaypoint!!,
            onDismiss = { viewModel.dismissEditWaypointDialog() },
            onConfirm = { updated ->
                viewModel.updateWaypoint(uiState.editingWaypoint!!, updated)
            }
        )
    }

    if (uiState.isServerSettingsDialogOpen) {
        ServerSettingsDialog(
            currentServerUrl = uiState.osrmServerUrl,
            onDismiss = { viewModel.dismissServerSettingsDialog() },
            onSave = { viewModel.updateServerUrl(it) }
        )
    }

    if (uiState.isSaveConfirmDialogOpen) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissSaveConfirmDialog() },
            title = {
                Text(text = "Replace File?")
            },
            text = {
                Text(
                    text = "Are you sure you want to replace \"${uiState.fileName ?: "this file"}\" with the current edits? The existing file will be overwritten."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmSave(context.contentResolver)
                    }
                ) {
                    Text("Replace")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissSaveConfirmDialog() }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.isOpenGpxFileDialogOpen) {
        val resolver = LocalContext.current.contentResolver
        OpenGpxFileDialog(
            files = uiState.availableGpxFiles,
            isScanning = uiState.isScanningGpxFiles,
            onFileSelected = { fileInfo ->
                viewModel.loadSelectedGpxFile(fileInfo, resolver)
            },
            onBrowseSystemFiles = {
                viewModel.dismissGpxFileSelectionDialog()
                onOpenGpxFile()
            },
            onDismiss = { viewModel.dismissGpxFileSelectionDialog() }
        )
    }

    if (uiState.isPointActionSheetOpen && uiState.selectedVertex != null && uiState.selectedVertexIndex != null) {
        PointActionBottomSheet(
            pointIndex = uiState.selectedVertexIndex!!,
            point = uiState.selectedVertex!!,
            totalPointsCount = uiState.gpxData.trackPoints.size,
            onMoveClick = { viewModel.startMoveVertexMode(uiState.selectedVertexIndex!!) },
            onDeleteClick = { viewModel.deleteVertex(uiState.selectedVertexIndex!!) },
            onSetAsWptClick = { viewModel.convertVertexToWaypoint(uiState.selectedVertexIndex!!) },
            onDismiss = { viewModel.dismissPointActionSheet() }
        )
    }
}

