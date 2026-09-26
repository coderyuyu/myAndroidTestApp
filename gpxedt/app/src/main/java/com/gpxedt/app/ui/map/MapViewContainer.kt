package com.gpxedt.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color as AndroidColor
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
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
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

@Composable
fun MapViewContainer(
    gpxData: GpxData,
    startPointerIndex: Int,
    middlePointerIndex: Int,
    endPointerIndex: Int,
    mapBoundsTrigger: Int,
    focusLocation: Pair<Double, Double>? = null,
    cameraCenterLocation: Pair<Double, Double>? = null,
    onPointTapped: (Int) -> Unit,
    onMapLongClick: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
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

    val mapHolder = remember { mutableListOf<MapLibreMap>() }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    addOnStyleImageMissingListener { id ->
                        if (id == WPT_TRIANGLE_ICON_ID) {
                            mapHolder.firstOrNull()?.getStyle { style ->
                                if (style.getImage(WPT_TRIANGLE_ICON_ID) == null) {
                                    style.addImage(WPT_TRIANGLE_ICON_ID, createWptTriangleBitmap(context))
                                }
                            }
                        }
                    }

                    getMapAsync { map ->
                        mapHolder.clear()
                        mapHolder.add(map)

                        map.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { style ->
                            initMapLayers(style, context)
                            updateMapData(style, gpxData, startPointerIndex, middlePointerIndex, endPointerIndex, context)
                            zoomToTrackBounds(map, gpxData)
                        }

                        // Map click listener: select closest point
                        map.addOnMapClickListener { latLng ->
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
    LaunchedEffect(gpxData, startPointerIndex, middlePointerIndex, endPointerIndex) {
        mapHolder.firstOrNull()?.getStyle { style ->
            updateMapData(style, gpxData, startPointerIndex, middlePointerIndex, endPointerIndex, context)
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
    // 0. Register WPT Dark Green Triangle Icon
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

    // 4. Waypoints Layer (Rendered as Dark Green Triangle on the Route)
    if (style.getSource(WAYPOINTS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(WAYPOINTS_SOURCE_ID))
    }
    if (style.getLayer(WAYPOINTS_LAYER_ID) == null) {
        val wptSymbolLayer = SymbolLayer(WAYPOINTS_LAYER_ID, WAYPOINTS_SOURCE_ID).apply {
            setProperties(
                iconImage(WPT_TRIANGLE_ICON_ID),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconAnchor(Property.ICON_ANCHOR_CENTER),
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
}

private fun updateMapData(
    style: Style,
    gpxData: GpxData,
    startPointerIndex: Int,
    middlePointerIndex: Int,
    endPointerIndex: Int,
    context: Context
) {
    // Ensure layers and images are registered if style was just loaded or restored
    if (style.getSource(WAYPOINTS_SOURCE_ID) == null || style.getImage(WPT_TRIANGLE_ICON_ID) == null) {
        initMapLayers(style, context)
    }

    val trackPoints = gpxData.trackPoints

    // 1. Update Track Polyline
    val trackSource = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)
    if (trackPoints.size >= 2) {
        val coords = trackPoints.map { Point.fromLngLat(it.lon, it.lat) }
        val line = LineString.fromLngLats(coords)
        trackSource?.setGeoJson(Feature.fromGeometry(line))
    } else {
        trackSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }

    // 2. Update Segment between Start and End
    val segmentSource = style.getSourceAs<GeoJsonSource>(SEGMENT_SOURCE_ID)
    if (startPointerIndex < endPointerIndex && endPointerIndex < trackPoints.size) {
        val segPoints = trackPoints.subList(startPointerIndex, endPointerIndex + 1)
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
    if (trackPoints.isNotEmpty()) {
        // Start Pointer: Green (#4CAF50)
        val clampedStart = startPointerIndex.coerceIn(0, trackPoints.size - 1)
        val startPt = trackPoints[clampedStart]
        val startFeat = Feature.fromGeometry(Point.fromLngLat(startPt.lon, startPt.lat)).apply {
            addStringProperty("type", "start")
            addStringProperty("color", "#4CAF50") // Green
        }
        markerFeatures.add(startFeat)

        // Middle Pointer (WPT): Blue (#2196F3)
        val clampedMiddle = middlePointerIndex.coerceIn(0, trackPoints.size - 1)
        val middlePt = trackPoints[clampedMiddle]
        val middleFeat = Feature.fromGeometry(Point.fromLngLat(middlePt.lon, middlePt.lat)).apply {
            addStringProperty("type", "middle_wpt")
            addStringProperty("color", "#2196F3") // Blue
        }
        markerFeatures.add(middleFeat)

        // End Pointer: Red (#F44336)
        val clampedEnd = endPointerIndex.coerceIn(0, trackPoints.size - 1)
        val endPt = trackPoints[clampedEnd]
        val endFeat = Feature.fromGeometry(Point.fromLngLat(endPt.lon, endPt.lat)).apply {
            addStringProperty("type", "end")
            addStringProperty("color", "#F44336") // Red
        }
        markerFeatures.add(endFeat)
    }

    val markersSource = style.getSourceAs<GeoJsonSource>(MARKERS_SOURCE_ID)
    markersSource?.setGeoJson(FeatureCollection.fromFeatures(markerFeatures))

    // 4. Update Waypoints (Icon layer & Label layer)
    val wptFeatures = gpxData.waypoints.map { wpt ->
        val feat = Feature.fromGeometry(Point.fromLngLat(wpt.lon, wpt.lat))
        feat.addStringProperty("title", wpt.name)
        feat.addStringProperty("desc", wpt.desc ?: "")
        feat.addStringProperty("sym", wpt.sym ?: "")
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
