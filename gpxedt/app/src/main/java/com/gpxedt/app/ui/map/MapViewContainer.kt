package com.gpxedt.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
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

private const val TRACK_SOURCE_ID = "track_source"
private const val TRACK_LAYER_ID = "track_layer"
private const val SEGMENT_SOURCE_ID = "segment_source"
private const val SEGMENT_LAYER_ID = "segment_layer"
private const val MARKERS_SOURCE_ID = "markers_source"
private const val MARKERS_LAYER_ID = "markers_layer"
private const val WAYPOINTS_SOURCE_ID = "waypoints_source"
private const val WAYPOINTS_LABEL_SOURCE_ID = "waypoints_label_source"
private const val WAYPOINTS_LAYER_ID = "waypoints_layer"
private const val WAYPOINTS_LABEL_LAYER_ID = "waypoints_label_layer"
private const val WPT_TRIANGLE_ICON_ID = "wpt_triangle_icon"
private const val WPT_FLAG_RED_ICON_ID = GpxWaypoint.SYM_FLAG_RED
private const val WPT_FLAG_YELLOW_ICON_ID = GpxWaypoint.SYM_FLAG_YELLOW
private const val WPT_FLAG_GREEN_ICON_ID = GpxWaypoint.SYM_FLAG_GREEN
private const val INTERMEDIATE_VERTICES_SOURCE_ID = "intermediate_vertices_source"
private const val INTERMEDIATE_VERTICES_LAYER_ID = "intermediate_vertices_layer"
private const val DRAG_PREVIEW_SOURCE_ID = "drag_preview_source"
private const val DRAG_PREVIEW_LINE_LAYER_ID = "drag_preview_line_layer"
private const val DRAG_PREVIEW_POINT_LAYER_ID = "drag_preview_point_layer"

@Composable
fun MapViewContainer(
    gpxData: GpxData,
    startPointerIndex: Int,
    middlePointerIndex: Int,
    endPointerIndex: Int,
    mapBoundsTrigger: Int,
    focusLocation: Pair<Double, Double>? = null,
    cameraCenterLocation: Pair<Double, Double>? = null,
    showTrackpoints: Boolean = true,
    dragInsertCoordinate: Pair<Double, Double>? = null,
    dragInsertIndex: Int? = null,
    movingVertexPosition: Pair<Double, Double>? = null,
    movingVertexIndex: Int? = null,
    onPointTapped: (Int) -> Unit,
    onVertexTapped: ((Int) -> Unit)? = null,
    onStartDragInsertion: ((Double, Double) -> Unit)? = null,
    onUpdateDragInsertion: ((Double, Double) -> Unit)? = null,
    onCommitDragInsertion: ((Double, Double) -> Unit)? = null,
    onCancelDragInsertion: (() -> Unit)? = null,
    onUpdateMovePosition: ((Double, Double) -> Unit)? = null,
    onCommitMovePosition: ((Int, Double, Double) -> Unit)? = null,
    onCancelMovePosition: (() -> Unit)? = null,
    onMapLongClick: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // State holder updated on every recomposition so dispatchTouchEvent always reads latest state
    val touchState = remember {
        object {
            var gpxData: GpxData = gpxData
            var movingVertexIndex: Int? = movingVertexIndex
            var onVertexTapped: ((Int) -> Unit)? = onVertexTapped
            var onStartDragInsertion: ((Double, Double) -> Unit)? = onStartDragInsertion
            var onUpdateDragInsertion: ((Double, Double) -> Unit)? = onUpdateDragInsertion
            var onCommitDragInsertion: ((Double, Double) -> Unit)? = onCommitDragInsertion
            var onCancelDragInsertion: (() -> Unit)? = onCancelDragInsertion
            var onUpdateMovePosition: ((Double, Double) -> Unit)? = onUpdateMovePosition
            var onCommitMovePosition: ((Int, Double, Double) -> Unit)? = onCommitMovePosition
            var onCancelMovePosition: (() -> Unit)? = onCancelMovePosition
        }
    }

    touchState.gpxData = gpxData
    touchState.movingVertexIndex = movingVertexIndex
    touchState.onVertexTapped = onVertexTapped
    touchState.onStartDragInsertion = onStartDragInsertion
    touchState.onUpdateDragInsertion = onUpdateDragInsertion
    touchState.onCommitDragInsertion = onCommitDragInsertion
    touchState.onCancelDragInsertion = onCancelDragInsertion
    touchState.onUpdateMovePosition = onUpdateMovePosition
    touchState.onCommitMovePosition = onCommitMovePosition
    touchState.onCancelMovePosition = onCancelMovePosition

    val mapHolder = remember { mutableListOf<MapLibreMap>() }

    val mapView = remember {
        object : MapView(context) {
            private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
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
                val currentTrack = touchState.gpxData.trackPoints

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        isDraggingFromSegment = false
                        isDraggingMoveVertex = false
                        isTouchDownNearSegment = false
                        candidateVertexIndex = null

                        val latLng = map.projection.fromScreenLocation(android.graphics.PointF(event.x, event.y))

                        // 1. Move Mode: dragging the selected moving vertex
                        if (touchState.movingVertexIndex != null) {
                            isDraggingMoveVertex = true
                            touchState.onUpdateMovePosition?.invoke(latLng.latitude, latLng.longitude)
                            return true
                        }

                        // 2. Vertex Tap candidate check (screen distance <= 36dp)
                        val closestIdx = com.gpxedt.app.util.GeoSpatialUtil.findClosestVertexIndex(
                            latLng.latitude, latLng.longitude, currentTrack
                        )
                        if (closestIdx != null) {
                            val pt = currentTrack[closestIdx]
                            val screenPt = map.projection.toScreenLocation(LatLng(pt.lat, pt.lon))
                            val dist = kotlin.math.hypot(event.x - screenPt.x, event.y - screenPt.y)
                            if (dist <= vertexTouchThresholdPx) {
                                candidateVertexIndex = closestIdx
                            }
                        }

                        // 3. Segment Drag candidate check (screen distance <= 40dp)
                        if (currentTrack.size >= 2) {
                            val nearest = com.gpxedt.app.util.GeoSpatialUtil.findNearestSegment(
                                latLng.latitude, latLng.longitude, currentTrack
                            )
                            if (nearest != null) {
                                val projScreen = map.projection.toScreenLocation(
                                    LatLng(nearest.projectedPoint.lat, nearest.projectedPoint.lon)
                                )
                                val segDist = kotlin.math.hypot(event.x - projScreen.x, event.y - projScreen.y)
                                if (segDist <= segmentTouchThresholdPx) {
                                    isTouchDownNearSegment = true
                                }
                            }
                        }

                        return super.dispatchTouchEvent(event)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val distMoved = kotlin.math.hypot(event.x - downX, event.y - downY)
                        val latLng = map.projection.fromScreenLocation(android.graphics.PointF(event.x, event.y))

                        if (isDraggingMoveVertex) {
                            touchState.onUpdateMovePosition?.invoke(latLng.latitude, latLng.longitude)
                            return true
                        }

                        if (isTouchDownNearSegment && distMoved > touchSlop) {
                            if (!isDraggingFromSegment) {
                                isDraggingFromSegment = true
                                // Cancel child gestures so MapLibre map camera doesn't pan
                                val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                                touchState.onStartDragInsertion?.invoke(latLng.latitude, latLng.longitude)
                            } else {
                                touchState.onUpdateDragInsertion?.invoke(latLng.latitude, latLng.longitude)
                            }
                            return true
                        }

                        if (isDraggingFromSegment) {
                            touchState.onUpdateDragInsertion?.invoke(latLng.latitude, latLng.longitude)
                            return true
                        }

                        return super.dispatchTouchEvent(event)
                    }

                    MotionEvent.ACTION_UP -> {
                        val distMoved = kotlin.math.hypot(event.x - downX, event.y - downY)
                        val latLng = map.projection.fromScreenLocation(android.graphics.PointF(event.x, event.y))

                        if (isDraggingMoveVertex) {
                            isDraggingMoveVertex = false
                            val idx = touchState.movingVertexIndex
                            if (idx != null) {
                                touchState.onCommitMovePosition?.invoke(idx, latLng.latitude, latLng.longitude)
                            }
                            return true
                        }

                        if (isDraggingFromSegment) {
                            isDraggingFromSegment = false
                            isTouchDownNearSegment = false
                            touchState.onCommitDragInsertion?.invoke(latLng.latitude, latLng.longitude)
                            return true
                        }

                        isTouchDownNearSegment = false

                        if (distMoved < touchSlop && candidateVertexIndex != null) {
                            val vIdx = candidateVertexIndex!!
                            candidateVertexIndex = null
                            touchState.onVertexTapped?.invoke(vIdx)
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
                            touchState.onCancelMovePosition?.invoke()
                        }
                        if (isDraggingFromSegment) {
                            isDraggingFromSegment = false
                            isTouchDownNearSegment = false
                            touchState.onCancelDragInsertion?.invoke()
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

    // Lifecycle observer for MapView
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
                            updateMapData(
                                style, gpxData, startPointerIndex, middlePointerIndex, endPointerIndex,
                                showTrackpoints, dragInsertCoordinate, dragInsertIndex, movingVertexPosition, movingVertexIndex, context
                            )
                            zoomToTrackBounds(map, gpxData)
                        }

                        // Map click listener: if tapping near a vertex, open vertex action sheet; else select closest point
                        map.addOnMapClickListener { latLng ->
                            val screenPt = map.projection.toScreenLocation(latLng)
                            val density = context.resources.displayMetrics.density
                            val vertexTouchThresholdPx = 36f * density

                            val closestVertexIdx = com.gpxedt.app.util.GeoSpatialUtil.findClosestVertexIndex(
                                latLng.latitude, latLng.longitude, gpxData.trackPoints
                            )
                            if (closestVertexIdx != null) {
                                val pt = gpxData.trackPoints[closestVertexIdx]
                                val vertexScreen = map.projection.toScreenLocation(LatLng(pt.lat, pt.lon))
                                val dist = kotlin.math.hypot(screenPt.x - vertexScreen.x, screenPt.y - vertexScreen.y)
                                if (dist <= vertexTouchThresholdPx) {
                                    onVertexTapped?.invoke(closestVertexIdx)
                                    return@addOnMapClickListener true
                                }
                            }

                            val closestIdx = findClosestPointIndex(latLng, gpxData.trackPoints)
                            if (closestIdx != null) {
                                onPointTapped(closestIdx)
                                true
                            } else {
                                false
                            }
                        }

                        // Map long click listener: add waypoint
                        map.addOnMapLongClickListener { latLng ->
                            onMapLongClick(latLng.latitude, latLng.longitude)
                            true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    // Reactively update layers when data or pointer positions change
    LaunchedEffect(
        gpxData, startPointerIndex, middlePointerIndex, endPointerIndex,
        showTrackpoints, dragInsertCoordinate, dragInsertIndex, movingVertexPosition, movingVertexIndex
    ) {
        mapHolder.firstOrNull()?.getStyle { style ->
            updateMapData(
                style, gpxData, startPointerIndex, middlePointerIndex, endPointerIndex,
                showTrackpoints, dragInsertCoordinate, dragInsertIndex, movingVertexPosition, movingVertexIndex, context
            )
        }
    }

    // Zoom to bounds when triggered
    LaunchedEffect(mapBoundsTrigger) {
        mapHolder.firstOrNull()?.let { map ->
            zoomToTrackBounds(map, gpxData)
        }
    }

    // Animate to specific focus location (e.g. waypoint selected from list)
    LaunchedEffect(focusLocation) {
        if (focusLocation != null) {
            mapHolder.firstOrNull()?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(focusLocation.first, focusLocation.second),
                    15.0
                )
            )
        }
    }

    // Center map smoothly on moving pointer
    LaunchedEffect(cameraCenterLocation) {
        if (cameraCenterLocation != null) {
            mapHolder.firstOrNull()?.easeCamera(
                CameraUpdateFactory.newLatLng(
                    LatLng(cameraCenterLocation.first, cameraCenterLocation.second)
                ),
                120
            )
        }
    }
}

private fun initMapLayers(style: Style, context: Context) {
    // 0. Register WPT Standard Flag Icons and Legacy Triangle Icon
    style.addImage(WPT_FLAG_RED_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#E53935")))
    style.addImage(WPT_FLAG_YELLOW_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#FBC02D")))
    style.addImage(WPT_FLAG_GREEN_ICON_ID, createFlagBitmap(context, AndroidColor.parseColor("#43A047")))
    style.addImage(WPT_TRIANGLE_ICON_ID, createWptTriangleBitmap(context))

    // 1. GPX Track Line Layer
    if (style.getSource(TRACK_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID))
    }
    if (style.getLayer(TRACK_LAYER_ID) == null) {
        val lineLayer = LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).apply {
            setProperties(
                lineColor(AndroidColor.parseColor("#E53935")), // Vibrant red
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(lineLayer)
    }

    // 2. Selected Segment for Rerouting / Span Line Layer (highlight in orange)
    if (style.getSource(SEGMENT_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(SEGMENT_SOURCE_ID))
    }
    if (style.getLayer(SEGMENT_LAYER_ID) == null) {
        val segmentLayer = LineLayer(SEGMENT_LAYER_ID, SEGMENT_SOURCE_ID).apply {
            setProperties(
                lineColor(AndroidColor.parseColor("#FF9800")), // Orange highlight
                lineWidth(6.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(segmentLayer)
    }

    // 3. Highlight Markers (Start: Green, Middle/WPT: Blue, End: Red)
    if (style.getSource(MARKERS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(MARKERS_SOURCE_ID))
    }
    if (style.getLayer(MARKERS_LAYER_ID) == null) {
        val markersLayer = CircleLayer(MARKERS_LAYER_ID, MARKERS_SOURCE_ID).apply {
            setProperties(
                circleRadius(9f),
                circleColor(get("color")), // Data-driven color from feature property
                circleStrokeWidth(2.5f),
                circleStrokeColor(AndroidColor.WHITE)
            )
        }
        style.addLayer(markersLayer)
    }

    // 4. Waypoints Layer (Rendered as Colored Flag Icon matching symbol)
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

    // 5. Waypoints Label Layer (Dedicated source to prevent font glyph loading from blocking triangle icon)
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

    // 6. Intermediate Trackpoint Dots Layer (Conditionally toggled)
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

    // 7. Drag & Drop Insertion / Move Preview
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
}

private fun updateMapData(
    style: Style,
    gpxData: GpxData,
    startPointerIndex: Int,
    middlePointerIndex: Int,
    endPointerIndex: Int,
    showTrackpoints: Boolean = true,
    dragInsertCoordinate: Pair<Double, Double>? = null,
    dragInsertIndex: Int? = null,
    movingPos: Pair<Double, Double>? = null,
    movingIndex: Int? = null,
    context: Context
) {
    // Ensure layers and images are registered if style was just loaded or restored
    if (style.getSource(WAYPOINTS_SOURCE_ID) == null || style.getImage(WPT_FLAG_RED_ICON_ID) == null) {
        initMapLayers(style, context)
    }

    val trackPoints = gpxData.trackPoints

    // Update Polyline incorporating real-time moving vertex position
    val displayPoints = if (movingPos != null && movingIndex != null && movingIndex in trackPoints.indices) {
        trackPoints.toMutableList().apply {
            this[movingIndex] = this[movingIndex].copy(lat = movingPos.first, lon = movingPos.second)
        }
    } else {
        trackPoints
    }

    // 1. Update Track Polyline
    val trackSource = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)
    if (displayPoints.size >= 2) {
        val coords = displayPoints.map { Point.fromLngLat(it.lon, it.lat) }
        val line = LineString.fromLngLats(coords)
        trackSource?.setGeoJson(Feature.fromGeometry(line))
    } else {
        trackSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 2. Update Segment between Start and End
    val segmentSource = style.getSourceAs<GeoJsonSource>(SEGMENT_SOURCE_ID)
    if (startPointerIndex < endPointerIndex && endPointerIndex < displayPoints.size) {
        val segPoints = displayPoints.subList(startPointerIndex, endPointerIndex + 1)
        if (segPoints.size >= 2) {
            val coords = segPoints.map { Point.fromLngLat(it.lon, it.lat) }
            segmentSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(coords)))
        } else {
            segmentSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    } else {
        segmentSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 3. Update 3 Pointer Markers: Start (Green), Middle/WPT (Blue), End (Red)
    val markerFeatures = mutableListOf<Feature>()
    if (displayPoints.isNotEmpty()) {
        // Start Pointer: Green (#4CAF50)
        val clampedStart = startPointerIndex.coerceIn(0, displayPoints.size - 1)
        val startPt = displayPoints[clampedStart]
        val startFeat = Feature.fromGeometry(Point.fromLngLat(startPt.lon, startPt.lat)).apply {
            addStringProperty("type", "start")
            addStringProperty("color", "#4CAF50") // Green
        }
        markerFeatures.add(startFeat)

        // Middle Pointer (WPT): Blue (#2196F3)
        val clampedMiddle = middlePointerIndex.coerceIn(0, displayPoints.size - 1)
        val middlePt = displayPoints[clampedMiddle]
        val middleFeat = Feature.fromGeometry(Point.fromLngLat(middlePt.lon, middlePt.lat)).apply {
            addStringProperty("type", "middle_wpt")
            addStringProperty("color", "#2196F3") // Blue
        }
        markerFeatures.add(middleFeat)

        // End Pointer: Red (#F44336)
        val clampedEnd = endPointerIndex.coerceIn(0, displayPoints.size - 1)
        val endPt = displayPoints[clampedEnd]
        val endFeat = Feature.fromGeometry(Point.fromLngLat(endPt.lon, endPt.lat)).apply {
            addStringProperty("type", "end")
            addStringProperty("color", "#F44336") // Red
        }
        markerFeatures.add(endFeat)
    }

    val markersSource = style.getSourceAs<GeoJsonSource>(MARKERS_SOURCE_ID)
    markersSource?.setGeoJson(FeatureCollection.fromFeatures(markerFeatures))

    // 4. Update Intermediate Trackpoint Dots conditionally
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

    // 5. Update Drag Preview
    val dragPreviewSource = style.getSourceAs<GeoJsonSource>(DRAG_PREVIEW_SOURCE_ID)
    if (dragInsertCoordinate != null && dragInsertIndex != null && trackPoints.size >= 2) {
        val dragFeatures = mutableListOf<Feature>()
        val insertIdx = dragInsertIndex.coerceIn(1, trackPoints.size - 1)
        val prevPt = trackPoints[insertIdx - 1]
        val nextPt = trackPoints[insertIdx]

        val previewCoords = listOf(
            Point.fromLngLat(prevPt.lon, prevPt.lat),
            Point.fromLngLat(dragInsertCoordinate.second, dragInsertCoordinate.first),
            Point.fromLngLat(nextPt.lon, nextPt.lat)
        )
        dragFeatures.add(Feature.fromGeometry(LineString.fromLngLats(previewCoords)))
        dragFeatures.add(Feature.fromGeometry(Point.fromLngLat(dragInsertCoordinate.second, dragInsertCoordinate.first)))
        dragPreviewSource?.setGeoJson(FeatureCollection.fromFeatures(dragFeatures.toTypedArray()))
    } else {
        dragPreviewSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 6. Update Waypoints (Icon layer & Label layer)
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
    waypointsSource?.setGeoJson(FeatureCollection.fromFeatures(wptFeatures))

    val waypointsLabelSource = style.getSourceAs<GeoJsonSource>(WAYPOINTS_LABEL_SOURCE_ID)
    waypointsLabelSource?.setGeoJson(FeatureCollection.fromFeatures(wptFeatures))
}

private fun zoomToTrackBounds(map: MapLibreMap, gpxData: GpxData) {
    val box = gpxData.boundingBox ?: return
    try {
        val bounds = LatLngBounds.Builder()
            .include(LatLng(box.minLat, box.minLon))
            .include(LatLng(box.maxLat, box.maxLon))
            .build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
    } catch (e: Exception) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(box.centerLat, box.centerLon), 12.0))
    }
}

private fun findClosestPointIndex(clickLatLng: LatLng, points: List<TrackPoint>): Int? {
    if (points.isEmpty()) return null
    val clickPoint = TrackPoint(clickLatLng.latitude, clickLatLng.longitude)

    var closestIdx = -1
    var minDistance = Double.MAX_VALUE

    points.forEachIndexed { idx, pt ->
        val dist = clickPoint.distanceTo(pt)
        if (dist < minDistance) {
            minDistance = dist
            closestIdx = idx
        }
    }

    return closestIdx
}

private fun createWptTriangleBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (24 * density).toInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val strokeWidth = 3f * density
    val padding = strokeWidth

    val path = Path().apply {
        // Equilateral upward pointing triangle
        moveTo(sizePx / 2f, padding) // Top apex
        lineTo(sizePx - padding, sizePx - padding) // Bottom right
        lineTo(padding, sizePx - padding) // Bottom left
        close()
    }

    // Inner fill first (dark green #1B5E20)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.parseColor("#1B5E20")
    }
    canvas.drawPath(path, fillPaint)

    // Outer stroke on top (crisp white border for high contrast against dark/light maps & red tracks)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        this.strokeWidth = strokeWidth
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(path, strokePaint)

    return bitmap
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

    // 1. Flag cloth (waving to the right from the pole)
    val flagHeight = 14f * density
    val flagRight = widthPx - 3f * density
    val flagPath = Path().apply {
        moveTo(poleX, poleTop)
        lineTo(flagRight, poleTop + flagHeight / 2f)
        lineTo(poleX, poleTop + flagHeight)
        close()
    }

    // Outer white stroke on flag for high contrast
    val flagStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AndroidColor.WHITE
        this.strokeWidth = strokeWidth * 1.5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawPath(flagPath, flagStrokePaint)

    // Flag fill
    val flagFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = flagColorInt
    }
    canvas.drawPath(flagPath, flagFillPaint)

    // 2. Draw pole with white border
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

    // 3. Base pinpoint dot at the pole tip
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
