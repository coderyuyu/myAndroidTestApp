package com.dir2gpx.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dir2gpx.model.GpxData
import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import com.dir2gpx.ui.theme.MarkerAmber
import com.dir2gpx.ui.theme.MarkerGreen
import com.dir2gpx.ui.theme.MarkerRed
import com.dir2gpx.ui.theme.RouteBlue
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Jetpack Compose wrapper for OSMDroid MapView with reactive data binding.
 *
 * Handles lifecycle events (resume/pause), manages overlays (polyline + markers),
 * and provides safe camera bounds zooming.
 *
 * @param gpxData The route data to display. When non-null, draws polyline and markers.
 * @param useSatellite Whether to use USGS satellite/topo tiles (true) or standard OSM tiles (false).
 * @param onMapReady Callback invoked once the MapView is initialized.
 * @param modifier Modifier for the AndroidView container.
 */
@Composable
fun OsmMapView(
    gpxData: GpxData?,
    useSatellite: Boolean,
    onMapReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    val paddingPx = with(density) { 48.dp.toPx().toInt() }

    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }

    // Ensure OSMDroid configuration is loaded with application preferences
    remember {
        val sharedPrefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, sharedPrefs)
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Create MapView with proper layout parameters and settings
    val mapView = remember {
        MapView(context).apply {
            clipToOutline = true
            clipToPadding = true
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setDestroyMode(false)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(20.0, 0.0))

            // Start tile download threads immediately
            onResume()
        }
    }

    // Bind MapView lifecycle to the Compose LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // If lifecycle is already in resumed state, make sure MapView is active
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
        }
    }

    // Update tile source when satellite toggle changes
    LaunchedEffect(useSatellite) {
        mapView.setTileSource(
            if (useSatellite) TileSourceFactory.USGS_SAT
            else TileSourceFactory.MAPNIK
        )
        mapView.invalidate()
    }

    // Update overlays when GPX data changes
    LaunchedEffect(gpxData) {
        mapView.overlays.clear()

        if (gpxData != null && gpxData.trackPoints.isNotEmpty()) {
            // Draw polyline
            val polyline = Polyline(mapView).apply {
                outlinePaint.color = RouteBlue.toArgb()
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                outlinePaint.isAntiAlias = true

                val geoPoints = gpxData.trackPoints.map { pt ->
                    GeoPoint(pt.latitude, pt.longitude)
                }
                setPoints(geoPoints)
            }
            mapView.overlays.add(polyline)

            // Draw waypoint markers
            for (waypoint in gpxData.waypoints) {
                val marker = createMarker(mapView, waypoint, timeFormatter)
                mapView.overlays.add(marker)
            }

            // Safe zoom to route bounding box
            val allPoints = gpxData.trackPoints + gpxData.waypoints
            if (allPoints.isNotEmpty()) {
                val lats = allPoints.map { it.latitude }
                val lons = allPoints.map { it.longitude }
                val minLat = lats.minOrNull() ?: 0.0
                val maxLat = lats.maxOrNull() ?: 0.0
                val minLon = lons.minOrNull() ?: 0.0
                val maxLon = lons.maxOrNull() ?: 0.0

                // Minimum delta of ~500m to prevent divide-by-zero or extreme zoom levels
                val latDelta = maxOf(maxLat - minLat, 0.005)
                val lonDelta = maxOf(maxLon - minLon, 0.005)

                val safeBox = BoundingBox(
                    maxLat + latDelta * 0.15,
                    maxLon + lonDelta * 0.15,
                    minLat - latDelta * 0.15,
                    minLon - lonDelta * 0.15
                )

                mapView.post {
                    val viewW = mapView.width
                    val viewH = mapView.height
                    if (viewW > 0 && viewH > 0) {
                        val safePadding = minOf(paddingPx, viewW / 4, viewH / 4).coerceAtLeast(16)
                        try {
                            mapView.zoomToBoundingBox(safeBox, true, safePadding)
                        } catch (_: Exception) {
                            mapView.controller.setCenter(safeBox.centerWithDateLine)
                            mapView.controller.setZoom(13.0)
                        }
                    } else {
                        mapView.controller.setCenter(safeBox.centerWithDateLine)
                        mapView.controller.setZoom(13.0)
                    }
                }
            }
        }

        mapView.invalidate()
    }

    // Render the MapView in Compose
    AndroidView(
        factory = {
            onMapReady(mapView)
            mapView
        },
        modifier = modifier.clipToBounds()
    )
}

/**
 * Creates a styled OSMDroid Marker for a waypoint.
 */
private fun createMarker(
    mapView: MapView,
    point: RoutePoint,
    timeFormatter: DateTimeFormatter
): Marker {
    return Marker(mapView).apply {
        position = GeoPoint(point.latitude, point.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = point.name

        // Build snippet with coordinates and time
        val coordText = "%.5f, %.5f".format(point.latitude, point.longitude)
        val timeText = point.timestamp?.let { timeFormatter.format(it) } ?: ""
        snippet = if (timeText.isNotEmpty()) "$coordText\n$timeText" else coordText

        // Color-coded icon based on point type
        val color = when (point.type) {
            PointType.ORIGIN -> MarkerGreen.toArgb()
            PointType.DESTINATION -> MarkerRed.toArgb()
            PointType.VIA -> MarkerAmber.toArgb()
            PointType.TRACK -> RouteBlue.toArgb()
        }
        icon = createCircleMarkerDrawable(mapView, color, point.type)
    }
}

/**
 * Creates a circle drawable for a map marker with the given color.
 */
private fun createCircleMarkerDrawable(
    mapView: MapView,
    color: Int,
    pointType: PointType
): Drawable {
    val size = when (pointType) {
        PointType.ORIGIN -> 40
        PointType.DESTINATION -> 40
        PointType.VIA -> 32
        PointType.TRACK -> 16
    }

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    // Outer circle
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    // White inner circle for origin/destination
    if (pointType == PointType.ORIGIN || pointType == PointType.DESTINATION) {
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 4f, whitePaint)

        // Inner colored dot
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 6f, innerPaint)
    }

    // White border for via points
    if (pointType == PointType.VIA) {
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, borderPaint)
    }

    return BitmapDrawable(mapView.context.resources, bitmap)
}
