package com.dir2gpx.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dir2gpx.model.GpxData
import com.dir2gpx.model.UiState
import com.dir2gpx.ui.components.InputSection
import com.dir2gpx.ui.components.OsmMapView
import com.dir2gpx.ui.components.RouteStatsOverlay
import com.dir2gpx.viewmodel.MainViewModel
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Main screen composable containing the input section, OSMDroid map, FABs, and stats overlay.
 *
 * @param initialUrl Optional URL received via share intent.
 * @param viewModel The MainViewModel instance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialUrl: String = "",
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Input state
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var startTime by rememberSaveable { mutableStateOf(Instant.now().toEpochMilli()) }
    var endTime by rememberSaveable {
        mutableStateOf(Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli())
    }
    var useSatellite by rememberSaveable { mutableStateOf(false) }

    // Map reference for zoom control
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // SAF file picker launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        uri?.let { viewModel.exportGpx(context, it) }
    }

    // Extract GPX data from success state
    val gpxData: GpxData? = (uiState as? UiState.Success)?.gpxData

    // Show error messages via snackbar
    LaunchedEffect(uiState) {
        if (uiState is UiState.Error) {
            snackbarHostState.showSnackbar((uiState as UiState.Error).message)
        }
        if (uiState is UiState.Success) {
            Toast.makeText(context, "Route converted successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            // Top: Collapsible Input Section
            InputSection(
                url = url,
                onUrlChange = { url = it },
                startTime = Instant.ofEpochMilli(startTime),
                endTime = Instant.ofEpochMilli(endTime),
                onStartTimeChange = { startTime = it.toEpochMilli() },
                onEndTimeChange = { endTime = it.toEpochMilli() },
                isLoading = uiState is UiState.Loading,
                hasResult = gpxData != null,
                onConvert = {
                    viewModel.convertUrl(
                        url = url,
                        startTime = Instant.ofEpochMilli(startTime),
                        endTime = Instant.ofEpochMilli(endTime)
                    )
                },
                onExport = {
                    val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(Instant.now())
                    exportLauncher.launch("$timestamp.gpx")
                },
                onShare = { viewModel.shareGpx(context) }
            )

            // Bottom: Map with overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // OSMDroid MapView
                OsmMapView(
                    gpxData = gpxData,
                    useSatellite = useSatellite,
                    onMapReady = { mapViewRef = it },
                    modifier = Modifier.fillMaxSize()
                )

                // Route Stats Overlay (top-left of map)
                androidx.compose.animation.AnimatedVisibility(
                    visible = gpxData != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    val data = gpxData
                    if (data != null) {
                        RouteStatsOverlay(gpxData = data)
                    }
                }

                // FAB Column (bottom-right of map)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Clear Map FAB
                    AnimatedVisibility(
                        visible = gpxData != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.clearState() },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Clear Map",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Toggle Tiles FAB
                    SmallFloatingActionButton(
                        onClick = { useSatellite = !useSatellite },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Layers,
                            contentDescription = "Toggle tile source",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Zoom to Route Bounds FAB
                    FloatingActionButton(
                        onClick = {
                            gpxData?.let { data ->
                                mapViewRef?.let { map ->
                                    val allPoints = data.trackPoints + data.waypoints
                                    if (allPoints.isNotEmpty()) {
                                        val lats = allPoints.map { it.latitude }
                                        val lons = allPoints.map { it.longitude }
                                        val minLat = lats.minOrNull() ?: 0.0
                                        val maxLat = lats.maxOrNull() ?: 0.0
                                        val minLon = lons.minOrNull() ?: 0.0
                                        val maxLon = lons.maxOrNull() ?: 0.0

                                        val latDelta = maxOf(maxLat - minLat, 0.005)
                                        val lonDelta = maxOf(maxLon - minLon, 0.005)

                                        val safeBox = BoundingBox(
                                            maxLat + latDelta * 0.15,
                                            maxLon + lonDelta * 0.15,
                                            minLat - latDelta * 0.15,
                                            minLon - lonDelta * 0.15
                                        )
                                        val w = map.width
                                        val h = map.height
                                        val pad = if (w > 0 && h > 0) minOf(48, w / 4, h / 4).coerceAtLeast(16) else 16
                                        try {
                                            map.zoomToBoundingBox(safeBox, true, pad)
                                        } catch (_: Exception) {
                                            map.controller.setCenter(safeBox.centerWithDateLine)
                                            map.controller.setZoom(13.0)
                                        }
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.CenterFocusStrong,
                            contentDescription = "Zoom to route bounds"
                        )
                    }
                }
            }
        }
    }
}
