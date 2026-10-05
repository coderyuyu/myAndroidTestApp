package com.gpxedt.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.ui.components.PointActionBottomSheet
import com.gpxedt.app.ui.map.components.EditModeToggleButton
import com.gpxedt.app.ui.map.components.MapLayerMenu
import com.gpxedt.app.ui.waypoint.EditWaypointDialog
import com.gpxedt.app.util.GeoSpatialUtil
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot

private const val OSM_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm-tiles": {
      "type": "raster",
      "tiles": [
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
  "layers": [
    {
      "id": "osm-tiles-layer",
      "type": "raster",
      "source": "osm-tiles",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
"""

// Layer & Source identifiers
private const val TRACK_SOURCE_ID = "track_source"
private const val TRACK_LAYER_ID = "track_layer"
private const val INTERMEDIATE_VERTICES_SOURCE_ID = "intermediate_vertices_source"
private const val INTERMEDIATE_VERTICES_LAYER_ID = "intermediate_vertices_layer"
private const val TERMINAL_MARKERS_SOURCE_ID = "terminal_markers_source"
private const val TERMINAL_MARKERS_LAYER_ID = "terminal_markers_layer"
private const val DRAG_PREVIEW_SOURCE_ID = "drag_preview_source"
private const val DRAG_PREVIEW_LINE_LAYER_ID = "drag_preview_line_layer"
private const val DRAG_PREVIEW_POINT_LAYER_ID = "drag_preview_point_layer"
private const val WAYPOINTS_SOURCE_ID = "waypoints_source"
private const val WAYPOINTS_LABEL_SOURCE_ID = "waypoints_label_source"
private const val WAYPOINTS_LAYER_ID = "waypoints_layer"
private const val WAYPOINTS_LABEL_LAYER_ID = "waypoints_label_layer"

private const val WPT_FLAG_RED_ICON_ID = GpxWaypoint.SYM_FLAG_RED
private const val WPT_FLAG_YELLOW_ICON_ID = GpxWaypoint.SYM_FLAG_YELLOW
private const val WPT_FLAG_GREEN_ICON_ID = GpxWaypoint.SYM_FLAG_GREEN

/**
 * Interactive track editing map screen consolidating route drag & drop vertex insertion,
 * point action sheet (Move, Delete, Set as WPT), and show/hide trackpoint vertex rendering.
 */
@Composable
fun TrackEditMapScreen(
    viewModel: TrackEditViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(uiState.userMessage) {
        val msg = uiState.userMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissUserMessage()
        }
    }

    val mapHolder = remember { mutableListOf<MapLibreMap>() }

    val mapView = remember {
        object : MapView(context) {
            private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
            private val density = context.resources.displayMetrics.density
            private val segmentTouchThresholdPx = 40f * density
            private val vertexTouchThresholdPx = 36f * density

            private var downX = 0f
            private var downY = 0f
            private var isDraggingFromSegment = false
            private var isDraggingMoveVertex = false
            private var isTouchDownNearSegment = false
            private var candidateVertexIndex: Int? = null

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val map = mapHolder.firstOrNull() ?: return super.dispatchTouchEvent(event)
                val currentTrack = viewModel.uiState.value.gpxData.trackPoints
                val currentState = viewModel.uiState.value

                // Accidental Touch Prevention:
                // When edit mode is locked (!isEditModeEnabled), bypass all editing gesture interception
                // (vertex tap menu, move mode, drag-insertion) and allow raw touch events to pass directly
                // to MapLibre MapView for native pan/zoom gestures.
                if (!currentState.isEditModeEnabled) {
                    isDraggingFromSegment = false
                    isDraggingMoveVertex = false
                    isTouchDownNearSegment = false
                    candidateVertexIndex = null
                    return super.dispatchTouchEvent(event)
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        isDraggingFromSegment = false
                        isDraggingMoveVertex = false
                        isTouchDownNearSegment = false
                        candidateVertexIndex = null

                        val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))

                        // 1. Move Mode: dragging the selected moving vertex
                        if (currentState.isMoveMode && currentState.movingPointIndex != null) {
                            isDraggingMoveVertex = true
                            viewModel.updateMovePosition(latLng.latitude, latLng.longitude)
                            return true
                        }

                        // 2. Vertex Tap candidate check (screen distance <= 36dp)
                        val closestIdx = GeoSpatialUtil.findClosestVertexIndex(
                            latLng.latitude, latLng.longitude, currentTrack
                        )
                        if (closestIdx != null) {
                            val pt = currentTrack[closestIdx]
                            val screenPt = map.projection.toScreenLocation(LatLng(pt.lat, pt.lon))
                            val dist = hypot(event.x - screenPt.x, event.y - screenPt.y)
                            if (dist <= vertexTouchThresholdPx) {
                                candidateVertexIndex = closestIdx
                            }
                        }

                        // 3. Segment Drag candidate check (screen distance <= 40dp)
                        if (currentTrack.size >= 2) {
                            val nearest = GeoSpatialUtil.findNearestSegment(
                                latLng.latitude, latLng.longitude, currentTrack
                            )
                            if (nearest != null) {
                                val projScreen = map.projection.toScreenLocation(
                                    LatLng(nearest.projectedPoint.lat, nearest.projectedPoint.lon)
                                )
                                val segDist = hypot(event.x - projScreen.x, event.y - projScreen.y)
                                if (segDist <= segmentTouchThresholdPx) {
                                    isTouchDownNearSegment = true
                                }
                            }
                        }

                        return super.dispatchTouchEvent(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val distMoved = hypot(event.x - downX, event.y - downY)
                        val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))

                        if (isDraggingMoveVertex) {
                            viewModel.updateMovePosition(latLng.latitude, latLng.longitude)
                            return true
                        }

                        if (isTouchDownNearSegment && distMoved > touchSlop) {
                            if (!isDraggingFromSegment) {
                                isDraggingFromSegment = true
                                val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                                viewModel.startDragInsertion(latLng.latitude, latLng.longitude)
                            } else {
                                viewModel.updateDragInsertion(latLng.latitude, latLng.longitude)
                            }
                            return true
                        }

                        if (isDraggingFromSegment) {
                            viewModel.updateDragInsertion(latLng.latitude, latLng.longitude)
                            return true
                        }

                        return super.dispatchTouchEvent(event)
                    }

                    MotionEvent.ACTION_UP -> {
                        val distMoved = hypot(event.x - downX, event.y - downY)
                        val latLng = map.projection.fromScreenLocation(PointF(event.x, event.y))

                        if (isDraggingMoveVertex) {
                            isDraggingMoveVertex = false
                            val idx = currentState.movingPointIndex
                            if (idx != null) {
                                viewModel.commitMovePoint(idx, latLng.latitude, latLng.longitude)
                            }
                            return true
                        }

                        if (isDraggingFromSegment) {
                            isDraggingFromSegment = false
                            isTouchDownNearSegment = false
                            viewModel.commitDragInsertion(latLng.latitude, latLng.longitude)
                            return true
                        }

                        isTouchDownNearSegment = false

                        if (distMoved < touchSlop && candidateVertexIndex != null) {
                            val vIdx = candidateVertexIndex!!
                            candidateVertexIndex = null
                            viewModel.onPointTapped(vIdx)
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                            return true
                        }

                        return super.dispatchTouchEvent(event)
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (isDraggingMoveVertex) {
                            isDraggingMoveVertex = false
                            viewModel.cancelMoveMode()
                        }
                        if (isDraggingFromSegment) {
                            isDraggingFromSegment = false
                            isTouchDownNearSegment = false
                            viewModel.cancelDragInsertion()
                        }
                        isTouchDownNearSegment = false
                        candidateVertexIndex = null
                        return super.dispatchTouchEvent(event)
                    }

                    else -> return super.dispatchTouchEvent(event)
                }
            }
        }.apply {
            onCreate(null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    addOnStyleImageMissingListener { id ->
                        mapHolder.firstOrNull()?.getStyle { style ->
                            if (style.getImage(id) == null) {
                                val color = GpxWaypoint.getFlagColorInt(id)
                                style.addImage(id, createFlagBitmap(context, color))
                            }
                        }
                    }

                    getMapAsync { map ->
                        mapHolder.clear()
                        mapHolder.add(map)

                        map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                            initMapLayers(style, context)
                            updateMapRender(
                                style = style,
                                gpxData = uiState.gpxData,
                                showTrackpoints = uiState.showTrackpoints,
                                dragInsertCoord = uiState.dragInsertCoordinate,
                                dragInsertIndex = uiState.dragInsertProjectedIndex,
                                movingPos = uiState.movingPointPosition,
                                movingIndex = uiState.movingPointIndex,
                                context = context
                            )
                            zoomToTrackBounds(map, uiState.gpxData)
                        }

                        // Map click listener: if tapping near a vertex, open vertex action sheet
                        map.addOnMapClickListener { latLng ->
                            if (!viewModel.uiState.value.isEditModeEnabled) {
                                return@addOnMapClickListener false
                            }
                            val screenPt = map.projection.toScreenLocation(latLng)
                            val density = context.resources.displayMetrics.density
                            val vertexTouchThresholdPx = 36f * density
                            val currentTrack = viewModel.uiState.value.gpxData.trackPoints

                            val closestVertexIdx = GeoSpatialUtil.findClosestVertexIndex(
                                latLng.latitude, latLng.longitude, currentTrack
                            )
                            if (closestVertexIdx != null) {
                                val pt = currentTrack[closestVertexIdx]
                                val vertexScreen = map.projection.toScreenLocation(LatLng(pt.lat, pt.lon))
                                val dist = hypot(screenPt.x - vertexScreen.x, screenPt.y - vertexScreen.y)
                                if (dist <= vertexTouchThresholdPx) {
                                    viewModel.onPointTapped(closestVertexIdx)
                                    return@addOnMapClickListener true
                                }
                            }
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Reactive Map Rendering Updates
        LaunchedEffect(
            uiState.gpxData,
            uiState.showTrackpoints,
            uiState.dragInsertCoordinate,
            uiState.dragInsertProjectedIndex,
            uiState.movingPointPosition,
            uiState.movingPointIndex
        ) {
            mapHolder.firstOrNull()?.getStyle { style ->
                updateMapRender(
                    style = style,
                    gpxData = uiState.gpxData,
                    showTrackpoints = uiState.showTrackpoints,
                    dragInsertCoord = uiState.dragInsertCoordinate,
                    dragInsertIndex = uiState.dragInsertProjectedIndex,
                    movingPos = uiState.movingPointPosition,
                    movingIndex = uiState.movingPointIndex,
                    context = context
                )
            }
        }

        // Edit Mode Toggle Button (Top-Start corner: Lock/Unlock for accidental touch prevention)
        EditModeToggleButton(
            isEditMode = uiState.isEditModeEnabled,
            onToggle = { viewModel.toggleEditMode() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 16.dp)
        )

        // Layer Control Menu (Top-End corner)
        MapLayerMenu(
            showTrackpoints = uiState.showTrackpoints,
            onToggleTrackpoints = { viewModel.toggleShowTrackpoints(it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        )

        // Move Mode Top Indicator & Action Banner
        AnimatedVisibility(
            visible = uiState.isMoveMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenWith,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Move Point #${(uiState.movingPointIndex ?: 0) + 1} (Drag to relocate)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { viewModel.cancelMoveMode() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Point Action Bottom Sheet
        if (uiState.isActionMenuOpen && uiState.selectedPoint != null && uiState.selectedPointIndex != null) {
            PointActionBottomSheet(
                pointIndex = uiState.selectedPointIndex!!,
                point = uiState.selectedPoint!!,
                totalPointsCount = uiState.gpxData.trackPoints.size,
                onMoveClick = { viewModel.startMoveMode(uiState.selectedPointIndex!!) },
                onDeleteClick = { viewModel.deletePoint(uiState.selectedPointIndex!!) },
                onSetAsWptClick = { viewModel.startConvertToWaypoint(uiState.selectedPointIndex!!) },
                onDismiss = { viewModel.dismissActionMenu() }
            )
        }

        // Edit Waypoint Dialog (triggered when converting a point to WPT)
        if (uiState.isEditWaypointDialogOpen && uiState.pendingWaypoint != null) {
            EditWaypointDialog(
                waypoint = uiState.pendingWaypoint!!,
                title = "Convert Trackpoint to Waypoint (設為航點)",
                onDismiss = { viewModel.dismissEditWaypointDialog() },
                onConfirm = { updated ->
                    viewModel.confirmWaypoint(updated)
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

private fun initMapLayers(style: Style, context: Context) {
    // 0. Standard Waypoint Flag Icons
    style.addImage(WPT_FLAG_RED_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#E53935")))
    style.addImage(WPT_FLAG_YELLOW_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#FBC02D")))
    style.addImage(WPT_FLAG_GREEN_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#43A047")))

    // 1. Route Polyline Layer
    if (style.getSource(TRACK_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID))
    }
    if (style.getLayer(TRACK_LAYER_ID) == null) {
        val lineLayer = LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).apply {
            setProperties(
                lineColor(AndroidColor.parseColor("#E53935")),
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(lineLayer)
    }

    // 2. Drag & Drop Insertion / Move Preview Line & Marker
    if (style.getSource(DRAG_PREVIEW_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(DRAG_PREVIEW_SOURCE_ID))
    }
    if (style.getLayer(DRAG_PREVIEW_LINE_LAYER_ID) == null) {
        val previewLine = LineLayer(DRAG_PREVIEW_LINE_LAYER_ID, DRAG_PREVIEW_SOURCE_ID).apply {
            setProperties(
                lineColor(AndroidColor.parseColor("#FF9800")),
                lineWidth(3.5f),
                lineDasharray(arrayOf(2f, 2f))
            )
        }
        style.addLayer(previewLine)
    }
    if (style.getLayer(DRAG_PREVIEW_POINT_LAYER_ID) == null) {
        val previewPoint = CircleLayer(DRAG_PREVIEW_POINT_LAYER_ID, DRAG_PREVIEW_SOURCE_ID).apply {
            setProperties(
                circleRadius(8f),
                circleColor(AndroidColor.parseColor("#FF9800")),
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.WHITE)
            )
        }
        style.addLayer(previewPoint)
    }

    // 3. Intermediate Trackpoint Dots Layer (Conditionally toggled)
    if (style.getSource(INTERMEDIATE_VERTICES_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(INTERMEDIATE_VERTICES_SOURCE_ID))
    }
    if (style.getLayer(INTERMEDIATE_VERTICES_LAYER_ID) == null) {
        val intermediateLayer = CircleLayer(INTERMEDIATE_VERTICES_LAYER_ID, INTERMEDIATE_VERTICES_SOURCE_ID).apply {
            setProperties(
                circleRadius(4.5f),
                circleColor(AndroidColor.WHITE),
                circleStrokeWidth(1.8f),
                circleStrokeColor(AndroidColor.parseColor("#E53935"))
            )
        }
        style.addLayer(intermediateLayer)
    }

    // 4. Terminal Markers (Start: Green, End: Red)
    if (style.getSource(TERMINAL_MARKERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(TERMINAL_MARKERS_SOURCE_ID))
    }
    if (style.getLayer(TERMINAL_MARKERS_LAYER_ID) == null) {
        val terminalLayer = CircleLayer(TERMINAL_MARKERS_LAYER_ID, TERMINAL_MARKERS_SOURCE_ID).apply {
            setProperties(
                circleRadius(9f),
                circleColor(get("color")),
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.WHITE)
            )
        }
        style.addLayer(terminalLayer)
    }

    // 5. Waypoints Layer
    if (style.getSource(WAYPOINTS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(WAYPOINTS_SOURCE_ID))
    }
    if (style.getLayer(WAYPOINTS_LAYER_ID) == null) {
        val wptSymbolLayer = SymbolLayer(WAYPOINTS_LAYER_ID, WAYPOINTS_SOURCE_ID).apply {
            setProperties(
                iconImage(get("icon_image")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconSize(1.0f)
            )
        }
        style.addLayer(wptSymbolLayer)
    }

    // 6. Waypoints Label Layer
    if (style.getSource(WAYPOINTS_LABEL_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(WAYPOINTS_LABEL_SOURCE_ID))
    }
    if (style.getLayer(WAYPOINTS_LABEL_LAYER_ID) == null) {
        val wptLabel = SymbolLayer(WAYPOINTS_LABEL_LAYER_ID, WAYPOINTS_LABEL_SOURCE_ID).apply {
            setProperties(
                textField("{title}"),
                textSize(12f),
                textColor(AndroidColor.BLACK),
                textHaloColor(AndroidColor.WHITE),
                textHaloWidth(1.5f),
                textOffset(arrayOf(0f, 1.4f)),
                textAllowOverlap(true),
                textIgnorePlacement(false)
            )
        }
        style.addLayer(wptLabel)
    }
}

private fun updateMapRender(
    style: Style,
    gpxData: GpxData,
    showTrackpoints: Boolean,
    dragInsertCoord: Pair<Double, Double>?,
    dragInsertIndex: Int?,
    movingPos: Pair<Double, Double>?,
    movingIndex: Int?,
    context: Context
) {
    if (style.getSource(WAYPOINTS_SOURCE_ID) == null || style.getImage(WPT_FLAG_RED_ICON_ID) == null) {
        initMapLayers(style, context)
    }

    val trackPoints = gpxData.trackPoints

    // 1. Update Polyline (incorporating real-time moving vertex if active)
    val displayPoints = if (movingPos != null && movingIndex != null && movingIndex in trackPoints.indices) {
        trackPoints.toMutableList().apply {
            this[movingIndex] = this[movingIndex].copy(lat = movingPos.first, lon = movingPos.second)
        }
    } else {
        trackPoints
    }

    val trackSource = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)
    if (displayPoints.size >= 2) {
        val coords = displayPoints.map { Point.fromLngLat(it.lon, it.lat) }
        trackSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(coords)))
    } else {
        trackSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 2. Conditional Intermediate Vertex Rendering
    // When showTrackpoints is true: Render intermediate vertex markers.
    // When false: Skip allocations and pass emptyArray to guarantee 60 FPS on >5,000 pt tracks.
    val intermediateSource = style.getSourceAs<GeoJsonSource>(INTERMEDIATE_VERTICES_SOURCE_ID)
    val intermediateLayer = style.getLayer(INTERMEDIATE_VERTICES_LAYER_ID)

    if (showTrackpoints && displayPoints.size > 2) {
        intermediateLayer?.setProperties(visibility(Property.VISIBLE))
        val intermediateFeatures = (1 until displayPoints.size - 1).map { idx ->
            val pt = displayPoints[idx]
            Feature.fromGeometry(Point.fromLngLat(pt.lon, pt.lat)).apply {
                addNumberProperty("index", idx)
            }
        }
        intermediateSource?.setGeoJson(FeatureCollection.fromFeatures(intermediateFeatures.toTypedArray()))
    } else {
        intermediateLayer?.setProperties(visibility(Property.NONE))
        intermediateSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 3. Terminal Start & End Markers (Always visible regardless of toggle)
    val terminalSource = style.getSourceAs<GeoJsonSource>(TERMINAL_MARKERS_SOURCE_ID)
    if (displayPoints.isNotEmpty()) {
        val terminalFeatures = mutableListOf<Feature>()
        // Start: Green (#4CAF50)
        val startPt = displayPoints.first()
        terminalFeatures.add(
            Feature.fromGeometry(Point.fromLngLat(startPt.lon, startPt.lat)).apply {
                addStringProperty("type", "start")
                addStringProperty("color", "#4CAF50")
            }
        )
        // End: Red (#F44336)
        if (displayPoints.size > 1) {
            val endPt = displayPoints.last()
            terminalFeatures.add(
                Feature.fromGeometry(Point.fromLngLat(endPt.lon, endPt.lat)).apply {
                    addStringProperty("type", "end")
                    addStringProperty("color", "#F44336")
                }
            )
        }
        terminalSource?.setGeoJson(FeatureCollection.fromFeatures(terminalFeatures.toTypedArray()))
    } else {
        terminalSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 4. Drag & Drop Insertion Preview
    val dragPreviewSource = style.getSourceAs<GeoJsonSource>(DRAG_PREVIEW_SOURCE_ID)
    if (dragInsertCoord != null && dragInsertIndex != null && trackPoints.size >= 2) {
        val dragFeatures = mutableListOf<Feature>()
        val insertIdx = dragInsertIndex.coerceIn(1, trackPoints.size - 1)
        val prevPt = trackPoints[insertIdx - 1]
        val nextPt = trackPoints[insertIdx]

        // Preview line: Prev -> DragPoint -> Next
        val previewCoords = listOf(
            Point.fromLngLat(prevPt.lon, prevPt.lat),
            Point.fromLngLat(dragInsertCoord.second, dragInsertCoord.first),
            Point.fromLngLat(nextPt.lon, nextPt.lat)
        )
        dragFeatures.add(Feature.fromGeometry(LineString.fromLngLats(previewCoords)))
        // Preview point marker at drop coordinate
        dragFeatures.add(Feature.fromGeometry(Point.fromLngLat(dragInsertCoord.second, dragInsertCoord.first)))

        dragPreviewSource?.setGeoJson(FeatureCollection.fromFeatures(dragFeatures.toTypedArray()))
    } else {
        dragPreviewSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 5. Standalone Waypoints (Always visible regardless of toggle)
    val wptFeatures = gpxData.waypoints.map { wpt ->
        val feat = Feature.fromGeometry(Point.fromLngLat(wpt.lon, wpt.lat))
        val normalizedSym = GpxWaypoint.normalizeSymbol(wpt.sym)
        feat.addStringProperty("title", wpt.name)
        feat.addStringProperty("desc", wpt.desc ?: "")
        feat.addStringProperty("sym", normalizedSym)
        feat.addStringProperty("icon_image", normalizedSym)
        feat
    }
    val waypointsSource = style.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE_ID)
    waypointsSource?.setGeoJson(FeatureCollection.fromFeatures(wptFeatures.toTypedArray()))

    val waypointsLabelSource = style.getSourceAs<GeoJsonSource>(WAYPOINTS_LABEL_SOURCE_ID)
    waypointsLabelSource?.setGeoJson(FeatureCollection.fromFeatures(wptFeatures.toTypedArray()))
}

private fun zoomToTrackBounds(map: MapLibreMap, gpxData: GpxData) {
    val box = gpxData.boundingBox ?: return
    try {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(box.minLat, box.minLon))
            .include(LatLng(box.maxLat, box.maxLon))
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
    } catch (_: Exception) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(box.centerLat, box.centerLon), 12.0))
    }
}

private fun createFlagBitmap(context: Context, flagColorInt: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val widthPx = (28 * density).toInt().coerceAtLeast(56)
    val heightPx = (32 * density).toInt().coerceAtLeast(64)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val strokeWidth = 2.5f * density
    val poleX = widthPx / 2f
    val poleTop = 4f * density
    val poleBottom = heightPx - 3f * density

    val flagHeight = 14f * density
    val flagRight = widthPx - 3f * density
    val flagPath = Path().apply {
        moveTo(poleX, poleTop)
        lineTo(flagRight, poleTop + flagHeight / 2f)
        lineTo(poleX, poleTop + flagHeight)
        close()
    }

    val flagStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        this.strokeWidth = strokeWidth * 1.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(flagPath, flagStrokePaint)

    val flagFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = flagColorInt
    }
    canvas.drawPath(flagPath, flagFillPaint)

    val poleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        this.strokeWidth = strokeWidth * 1.6f
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(poleX, poleTop, poleX, poleBottom, poleBorderPaint)

    val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.parseColor("#37474F")
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawLine(poleX, poleTop, poleX, poleBottom, polePaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = flagColorInt
    }
    val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        this.strokeWidth = 1.5f * density
    }
    canvas.drawCircle(poleX, poleBottom, 2.5f * density, dotStroke)
    canvas.drawCircle(poleX, poleBottom, 2.5f * density, dotPaint)

    return bitmap
}
