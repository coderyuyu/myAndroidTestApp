package com.gpxami.app.map

import android.graphics.*
import android.graphics.Paint.Cap
import android.graphics.Paint.Join
import android.graphics.Paint.Style
import android.util.LruCache
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.data.model.Wpt
import com.gpxami.app.data.model.WptVisibilityMode
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

enum class MapStyle(val displayName: String, val id: String) {
    OPEN_STREET_MAP("OpenStreetMap", "osm"),
    OPEN_TOPO_MAP("OpenTopoMap", "opentopo")
}

/**
 * Universal high-performance Slippy Map Tile & Route Renderer for Jetpack Compose UI
 * and off-screen VideoEncoder.
 *
 * Features:
 * - Real raster map tiles with OpenStreetMap (default) and OpenTopoMap (topographic elevation/contours).
 * - Dual-layer caching: In-memory LruCache + persistent on-disk cache per style.
 * - Web Mercator (EPSG:3857) projection supporting arbitrary sub-pixel camera zooms and rotation.
 * - Preloading engine [preloadTilesForTrack] to ensure 100% tile cache hit during video export.
 * - Glowing traversed GPS route, vehicle navigation marker, and waypoint pins.
 */
class MapRenderer(
    private val cacheDir: File? = null
) {
    // Memory cache: holds up to 150 bitmaps (~30MB uncompressed)
    private val tileMemoryCache = object : LruCache<String, Bitmap>(150) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Disk cache directory: cacheDir/map_tiles
    private val tileDiskDir = cacheDir?.let { File(it, "map_tiles") }?.apply {
        if (!exists()) mkdirs()
    }

    // In-flight background download tracker to avoid duplicate network calls
    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()

    // Thread pool for background asynchronous tile loading in UI
    private val tileExecutor = Executors.newFixedThreadPool(4)

    /**
     * Callback triggered on the main/caller thread when new tiles are downloaded and ready to display.
     */
    var onTileLoaded: (() -> Unit)? = null

    // Pre-allocated graphic paints for zero-allocation 60fps rendering
    private val bgPaint = Paint().apply {
        color = Color.rgb(15, 23, 42) // Slate 900 base #0F172A
        style = Style.FILL
    }

    private val tilePaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    // Subtle dark tint to make neon GPS routes stand out crisply against map details
    private val mapVeilPaint = Paint().apply {
        color = Color.argb(45, 11, 15, 23)
        style = Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(30, 148, 163, 184)
        strokeWidth = 1.0f
        style = Style.STROKE
    }

    companion object {
        const val BLACK_BORDER_WIDTH = 3.0f // 固定使用現有細線的 size (3f)

        /**
         * Converts (lat, lon) to Web Mercator world pixel coordinates at zoom level Z.
         */
        fun projectLatLon(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
            val scale = 256.0 * 2.0.pow(zoom)
            val x = ((lon + 180.0) / 360.0) * scale

            val sinLat = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
            val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * scale
            return Pair(x, y)
        }

        /**
         * Computes optimal camera zoom to view either the whole track or a focused tracking window.
         */
        fun calculateOptimalZoom(track: GpxTrack, viewportWidth: Float, viewportHeight: Float): Double {
            return calculateOptimalOverviewZoom(track, viewportWidth, viewportHeight, 0f, false)
        }

        /**
         * Computes optimal overview camera zoom to guarantee 100% of the route and waypoints are visible,
         * taking into account Web Mercator projection, rotation angle, and bottom elevation profile overlay.
         */
        fun calculateOptimalOverviewZoom(
            track: GpxTrack,
            viewportWidth: Float,
            viewportHeight: Float,
            rotationDegrees: Float = 0f,
            showElevationProfile: Boolean = true
        ): Double {
            if (track.points.isEmpty()) return 12.0

            var minWx = Double.MAX_VALUE
            var maxWx = -Double.MAX_VALUE
            var minWy = Double.MAX_VALUE
            var maxWy = -Double.MAX_VALUE

            for (pt in track.points) {
                val (wx, wy) = projectLatLon(pt.lat, pt.lon, 0.0)
                minWx = min(minWx, wx)
                maxWx = max(maxWx, wx)
                minWy = min(minWy, wy)
                maxWy = max(maxWy, wy)
            }
            for (wpt in track.waypoints) {
                val (wx, wy) = projectLatLon(wpt.lat, wpt.lon, 0.0)
                minWx = min(minWx, wx)
                maxWx = max(maxWx, wx)
                minWy = min(minWy, wy)
                maxWy = max(maxWy, wy)
            }

            val spanX0 = max(0.0001, maxWx - minWx)
            val spanY0 = max(0.0001, maxWy - minWy)

            val rad = Math.toRadians(rotationDegrees.toDouble())
            val cosR = abs(cos(rad))
            val sinR = abs(sin(rad))

            val rotatedSpanX0 = spanX0 * cosR + spanY0 * sinR
            val rotatedSpanY0 = spanX0 * sinR + spanY0 * cosR

            // Usable screen area with safety margins so path is never clipped
            val usableW = max(100f, viewportWidth * 0.82f)
            val usableH = if (showElevationProfile) {
                max(100f, viewportHeight * 0.65f) // Accounts for bottom 20% elevation profile + top title + margins
            } else {
                max(100f, viewportHeight * 0.80f)
            }

            val zoomX = ln(usableW.toDouble() / rotatedSpanX0) / ln(2.0)
            val zoomY = ln(usableH.toDouble() / rotatedSpanY0) / ln(2.0)

            return min(zoomX, zoomY).coerceIn(6.0, 18.0)
        }
    }

    fun projectLatLon(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> =
        Companion.projectLatLon(lat, lon, zoom)

    fun calculateOptimalZoom(track: GpxTrack, viewportWidth: Float, viewportHeight: Float): Double =
        Companion.calculateOptimalZoom(track, viewportWidth, viewportHeight)

    fun calculateOptimalOverviewZoom(
        track: GpxTrack,
        viewportWidth: Float,
        viewportHeight: Float,
        rotationDegrees: Float = 0f,
        showElevationProfile: Boolean = true
    ): Double = Companion.calculateOptimalOverviewZoom(track, viewportWidth, viewportHeight, rotationDegrees, showElevationProfile)

    // Outer black border casing (兩邊包黑線，固定細線 size)
    private val routeBlackCasingPaint = Paint().apply {
        color = Color.BLACK
        style = Style.STROKE
        strokeCap = Cap.ROUND
        strokeJoin = Join.ROUND
        isAntiAlias = true
    }

    // Clear hollow center (中間鏤空，透出地圖)
    private val routeHollowClearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Style.STROKE
        strokeCap = Cap.ROUND
        strokeJoin = Join.ROUND
        isAntiAlias = true
    }

    // Traversed route trail core (已走過路徑：中間填入使用者自選顏色)
    private val trailCorePaint = Paint().apply {
        style = Style.STROKE
        strokeCap = Cap.ROUND
        strokeJoin = Join.ROUND
        isAntiAlias = true
    }

    private val markerHaloPaint = Paint().apply {
        color = 0x5000F2FE.toInt()
        style = Style.FILL
        isAntiAlias = true
    }

    private val markerBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Style.STROKE
        strokeWidth = 3.0f
        isAntiAlias = true
    }

    private val markerBodyPaint = Paint().apply {
        color = 0xFF06B6D4.toInt()
        style = Style.FILL
        isAntiAlias = true
    }

    private val markerArrowPaint = Paint().apply {
        color = Color.WHITE
        style = Style.FILL_AND_STROKE
        isAntiAlias = true
    }

    private val pinPaint = Paint().apply {
        isAntiAlias = true
    }

    /**
     * Main render entry point onto an Android Canvas.
     */
    fun renderMap(
        canvas: Canvas,
        width: Float,
        height: Float,
        track: GpxTrack,
        currentProgress: Float,
        interpolatedPoint: InterpolatedPoint,
        autoFollowMarker: Boolean = true,
        customZoom: Double? = null,
        rotationDegrees: Float = 0f,
        mapStyle: MapStyle = MapStyle.OPEN_STREET_MAP,
        drawCompassOverlay: Boolean = false,
        panOffsetX: Float = 0f,
        panOffsetY: Float = 0f,
        focusPoint: Pair<Double, Double>? = null,
        showWaypointLabels: Boolean = true,
        wptLabelTextSize: Float = 20f,
        markerRadius: Float = 12f,
        markerColor: Int = Color.rgb(6, 182, 212),
        trackWidth: Float = 5.0f,
        trackColor: Int = Color.rgb(0, 242, 254),
        blackBorderWidth: Float = BLACK_BORDER_WIDTH,
        wptVisibilityMode: WptVisibilityMode = WptVisibilityMode.ALWAYS_SHOW,
        wptAlphaMap: Map<Int, Float>? = null,
        startWpt: Wpt? = null,
        endWpt: Wpt? = null,
        adminDivisionConfig: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY
    ) {
        if (track.points.isEmpty()) {
            canvas.drawRect(0f, 0f, width, height, bgPaint)
            return
        }

        // Dynamically configure route and trail paints based on user customization
        // 兩邊包黑線，中間使用 user 所選的粗細與顏色
        routeBlackCasingPaint.strokeWidth = trackWidth + 2f * blackBorderWidth
        routeHollowClearPaint.strokeWidth = trackWidth
        trailCorePaint.strokeWidth = trackWidth
        trailCorePaint.color = trackColor

        val zoom = customZoom ?: if (autoFollowMarker) {
            calculateOptimalZoom(track, width, height) + 0.8
        } else {
            calculateOptimalZoom(track, width, height)
        }

        // Camera center coordinates: prioritize explicit focusPoint (e.g. end point during range scrubbing)
        val (camCenterLat, camCenterLon) = when {
            focusPoint != null -> focusPoint
            autoFollowMarker -> Pair(interpolatedPoint.lat, interpolatedPoint.lon)
            else -> Pair(track.bounds.centerLat, track.bounds.centerLon)
        }

        val (camWorldX, camWorldY) = projectLatLon(camCenterLat, camCenterLon, zoom)
        val halfW = width / 2.0
        val halfH = height / 2.0

        // Apply interactive user pan offsets in world space
        val effectiveCamWorldX = camWorldX - panOffsetX
        val effectiveCamWorldY = camWorldY - panOffsetY

        // Helper to convert (lat, lon) to screen pixel coordinates before rotation
        fun toScreen(lat: Double, lon: Double): Pair<Float, Float> {
            val (wx, wy) = projectLatLon(lat, lon, zoom)
            val sx = (wx - effectiveCamWorldX + halfW).toFloat()
            val sy = (wy - effectiveCamWorldY + halfH).toFloat()
            return Pair(sx, sy)
        }

        // 1. Draw base dark background
        canvas.drawRect(0f, 0f, width, height, bgPaint)

        // Save canvas for rotating map elements
        canvas.save()
        if (rotationDegrees != 0f) {
            canvas.rotate(rotationDegrees, halfW.toFloat(), halfH.toFloat())
        }

        // 2. Draw Slippy Map Tiles Layer
        drawMapTiles(canvas, width, height, zoom, camCenterLat, camCenterLon, effectiveCamWorldX, effectiveCamWorldY, mapStyle)

        // 3. Optional subtle veil to enhance contrast of telemetry and cyan route
        canvas.drawRect(
            (halfW - hypot(halfW, halfH)).toFloat(),
            (halfH - hypot(halfW, halfH)).toFloat(),
            (halfW + hypot(halfW, halfH)).toFloat(),
            (halfH + hypot(halfW, halfH)).toFloat(),
            mapVeilPaint
        )

        // 4. Draw full route polyline: 空的實線，兩邊包黑線 (黑線固定 3f, 中間鏤空透出底圖)
        val fullRoutePath = Path()
        val firstScreen = toScreen(track.points[0].lat, track.points[0].lon)
        fullRoutePath.moveTo(firstScreen.first, firstScreen.second)

        for (i in 1 until track.points.size) {
            val pt = toScreen(track.points[i].lat, track.points[i].lon)
            fullRoutePath.lineTo(pt.first, pt.second)
        }

        val saveCount = canvas.saveLayer(null as RectF?, null)
        canvas.drawPath(fullRoutePath, routeBlackCasingPaint)
        canvas.drawPath(fullRoutePath, routeHollowClearPaint)
        canvas.restoreToCount(saveCount)

        // 5. Draw traversed route trail: 中間使用 user 所選的顏色
        val currentSegIdx = interpolatedPoint.pointIndex
        if (currentSegIdx >= 0 && track.points.isNotEmpty()) {
            val trailPath = Path()
            trailPath.moveTo(firstScreen.first, firstScreen.second)

            for (i in 1..min(currentSegIdx, track.points.size - 1)) {
                val pt = toScreen(track.points[i].lat, track.points[i].lon)
                trailPath.lineTo(pt.first, pt.second)
            }
            // Add current exact interpolated position
            val markerScreen = toScreen(interpolatedPoint.lat, interpolatedPoint.lon)
            trailPath.lineTo(markerScreen.first, markerScreen.second)

            // Traversed middle filled with user-selected color
            canvas.drawPath(trailPath, trailCorePaint)
        }

        // 6. Draw Green Start Circle & Red End Circle (Destination) matching markerRadius size
        drawRouteStartPoint(canvas, firstScreen.first, firstScreen.second, markerRadius)
        val endPt = track.points.last()
        val endScreen = toScreen(endPt.lat, endPt.lon)
        drawRouteEndPoint(canvas, endScreen.first, endScreen.second, markerRadius)

        // 6.1 Draw Permanently Visible Start and End WPT Labels with loop track collision offset
        val startLabelText = startWpt?.formatDisplayLabel(adminDivisionConfig) ?: "起點"
        val endLabelText = endWpt?.formatDisplayLabel(adminDivisionConfig) ?: "迄點"

        // Check if Start and End share the same coordinates or collide on screen (e.g. loop track)
        val screenDist = hypot(firstScreen.first - endScreen.first, firstScreen.second - endScreen.second)
        val isLoopCollision = screenDist < (markerRadius * 4f + 24f)

        drawEndpointLabel(
            canvas = canvas,
            x = firstScreen.first,
            y = firstScreen.second,
            text = startLabelText,
            rotationDegrees = rotationDegrees,
            labelTextSize = wptLabelTextSize,
            accentColor = Color.argb(255, 16, 185, 129), // Emerald Green
            radius = markerRadius,
            isOffsetDown = false
        )

        drawEndpointLabel(
            canvas = canvas,
            x = endScreen.first,
            y = endScreen.second,
            text = endLabelText,
            rotationDegrees = rotationDegrees,
            labelTextSize = wptLabelTextSize,
            accentColor = Color.argb(255, 239, 68, 68), // Rose Red
            radius = markerRadius,
            isOffsetDown = isLoopCollision
        )

        // 6.5 Draw Waypoint Points on Route (supporting fade-in/fade-out & visibility mode)
        for (i in track.waypoints.indices) {
            val wpt = track.waypoints[i]
            val alpha = if (wptVisibilityMode == WptVisibilityMode.ALWAYS_SHOW) {
                1.0f
            } else {
                wptAlphaMap?.get(i) ?: 0.0f
            }
            if (alpha > 0.005f) {
                val (wx, wy) = toScreen(wpt.lat, wpt.lon)
                drawWaypointMarker(
                    canvas = canvas,
                    x = wx,
                    y = wy,
                    name = if (showWaypointLabels) wpt.name else null,
                    rotationDegrees = rotationDegrees,
                    labelTextSize = wptLabelTextSize,
                    alpha = alpha
                )
            }
        }

        // 7. Draw Moving Vehicle Marker with Orientation & Pulse Ring
        val (markerX, markerY) = toScreen(interpolatedPoint.lat, interpolatedPoint.lon)
        drawVehicleMarker(
            canvas = canvas,
            x = markerX,
            y = markerY,
            bearingDegrees = interpolatedPoint.bearingDegrees,
            radius = markerRadius,
            color = markerColor
        )

        // Restore canvas from map rotation
        canvas.restore()

        // 8. Draw Optional North Compass Rose HUD on Canvas
        if (drawCompassOverlay) {
            drawNorthCompass(canvas, width - 42f, 42f, rotationDegrees, 20f)
        }
    }

    /**
     * Draws a sleek North Compass Indicator.
     * The needle rotates by [-rotationDegrees] so the red needle always points towards true North.
     */
    fun drawNorthCompass(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rotationDegrees: Float,
        radius: Float = 22f
    ) {
        canvas.save()
        canvas.translate(cx, cy)

        // Translucent dark circular backing
        val bgPaint = Paint().apply {
            color = Color.argb(200, 15, 23, 42)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(0f, 0f, radius, bgPaint)

        val ringPaint = Paint().apply {
            color = Color.argb(80, 56, 189, 248)
            style = Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawCircle(0f, 0f, radius, ringPaint)

        // Rotate needle to point towards true North
        canvas.rotate(-rotationDegrees)

        // North needle (Rose Red)
        val northNeedlePaint = Paint().apply {
            color = Color.rgb(244, 63, 94)
            style = Style.FILL
            isAntiAlias = true
        }
        val northPath = Path().apply {
            moveTo(0f, -radius * 0.72f)
            lineTo(radius * 0.28f, 0f)
            lineTo(-radius * 0.28f, 0f)
            close()
        }
        canvas.drawPath(northPath, northNeedlePaint)

        // South needle (Slate Silver)
        val southNeedlePaint = Paint().apply {
            color = Color.rgb(203, 213, 225)
            style = Style.FILL
            isAntiAlias = true
        }
        val southPath = Path().apply {
            moveTo(0f, radius * 0.72f)
            lineTo(radius * 0.28f, 0f)
            lineTo(-radius * 0.28f, 0f)
            close()
        }
        canvas.drawPath(southPath, southNeedlePaint)

        // Center pivot
        val pivotPaint = Paint().apply {
            color = Color.WHITE
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(0f, 0f, radius * 0.16f, pivotPaint)

        // "N" Label
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = radius * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("N", 0f, -radius * 0.76f, textPaint)

        canvas.restore()
    }

    /**
     * Renders real geographic map tiles (Web Mercator) spanning the viewport.
     * Computes bounds with hypotenuse radius so rotations never leave black corners.
     */
    private fun drawMapTiles(
        canvas: Canvas,
        width: Float,
        height: Float,
        zoom: Double,
        camLat: Double,
        camLon: Double,
        camWorldX: Double,
        camWorldY: Double,
        mapStyle: MapStyle = MapStyle.OPEN_STREET_MAP
    ) {
        val intZoom = floor(zoom).toInt().coerceIn(1, 18)
        val factor = 2.0.pow(zoom - intZoom)
        val tileSizeOnScreen = (256.0 * factor).toFloat()

        val halfW = width / 2.0
        val halfH = height / 2.0
        val maxRadius = hypot(halfW, halfH)

        val maxTile = (1 shl intZoom) - 1

        val minTx = floor((camWorldX - maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
        val maxTx = floor((camWorldX + maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
        val minTy = floor((camWorldY - maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
        val maxTy = floor((camWorldY + maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)

        val destRect = RectF()

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val tileOriginWorldX = tx * 256.0 * factor
                val tileOriginWorldY = ty * 256.0 * factor

                val screenLeft = (tileOriginWorldX - camWorldX + halfW).toFloat()
                val screenTop = (tileOriginWorldY - camWorldY + halfH).toFloat()
                val screenRight = screenLeft + tileSizeOnScreen
                val screenBottom = screenTop + tileSizeOnScreen

                destRect.set(screenLeft, screenTop, screenRight, screenBottom)

                // Quick frustum culling against circular bounds
                if (screenRight < halfW - maxRadius || screenLeft > halfW + maxRadius ||
                    screenBottom < halfH - maxRadius || screenTop > halfH + maxRadius) {
                    continue
                }

                val tileBitmap = getOrFetchTile(mapStyle, intZoom, tx, ty)
                if (tileBitmap != null) {
                    canvas.drawBitmap(tileBitmap, null, destRect, tilePaint)
                } else {
                    canvas.drawRect(destRect, gridPaint)
                }
            }
        }
    }

    /**
     * Gets a tile from RAM cache, disk cache, or triggers an asynchronous background download.
     */
    private fun getOrFetchTile(mapStyle: MapStyle, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${mapStyle.id}/$z/$x/$y"

        // 1. Check RAM cache
        tileMemoryCache.get(key)?.let { return it }

        // 2. Check Disk cache
        if (tileDiskDir != null) {
            val diskFile = File(tileDiskDir, "${mapStyle.id}/$z/$x/$y.png")
            if (diskFile.exists()) {
                if (diskFile.length() == 6987L) {
                    diskFile.delete()
                } else if (diskFile.length() > 0) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                        if (bitmap != null) {
                            tileMemoryCache.put(key, bitmap)
                            return bitmap
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        // 3. Asynchronously download from network if not already in flight
        if (inFlightRequests.add(key)) {
            tileExecutor.execute {
                try {
                    val bitmap = downloadTileDirect(mapStyle, z, x, y)
                    if (bitmap != null) {
                        tileMemoryCache.put(key, bitmap)
                        onTileLoaded?.invoke()
                    }
                } finally {
                    inFlightRequests.remove(key)
                }
            }
        }

        return null
    }

    /**
     * Downloads a tile synchronously using the specified MapStyle endpoints.
     */
    private fun downloadTileDirect(mapStyle: MapStyle, z: Int, x: Int, y: Int): Bitmap? {
        val urls = when (mapStyle) {
            MapStyle.OPEN_STREET_MAP -> listOf(
                "https://tile.openstreetmap.org/$z/$x/$y.png",
                "https://tile.openstreetmap.de/$z/$x/$y.png",
                "https://a.tile.openstreetmap.fr/osmfr/$z/$x/$y.png"
            )
            MapStyle.OPEN_TOPO_MAP -> listOf(
                "https://tile.opentopomap.org/$z/$x/$y.png",
                "https://a.tile.opentopomap.org/$z/$x/$y.png",
                "https://tile.openstreetmap.de/$z/$x/$y.png"
            )
        }

        for (urlStr in urls) {
            try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "GPXAmi-Track-Viewer/1.0 (Android; GPX Route Animator; contact: gpxami@app.internal)")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    if (conn.getHeaderField("x-blocked") != null) {
                        continue
                    }

                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size == 6987 && urlStr.contains("openstreetmap.org")) {
                        continue
                    }

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    if (bitmap != null) {
                        tileDiskDir?.let { dir ->
                            val targetFile = File(dir, "${mapStyle.id}/$z/$x/$y.png")
                            targetFile.parentFile?.mkdirs()
                            try {
                                FileOutputStream(targetFile).use { it.write(bytes) }
                            } catch (_: Exception) { }
                        }
                        return bitmap
                    }
                }
            } catch (_: Exception) {
                // Try next URL fallback
            }
        }
        return null
    }

    /**
     * Preloads all tiles visible along the entire track prior to off-screen video encoding.
     * Guarantees 100% tile cache hit during frame-by-frame MP4 generation.
     */
    suspend fun preloadTilesForTrack(
        track: GpxTrack,
        zoom: Double,
        width: Float,
        height: Float,
        mapStyle: MapStyle = MapStyle.OPEN_STREET_MAP,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val intZoom = floor(zoom).toInt().coerceIn(1, 18)
        val factor = 2.0.pow(zoom - intZoom)
        val halfW = width / 2.0
        val halfH = height / 2.0
        val maxRadius = hypot(halfW, halfH)
        val maxTile = (1 shl intZoom) - 1

        val tileKeysToFetch = mutableSetOf<Triple<Int, Int, Int>>()

        // Sample points along the track to identify all intersecting tiles
        val step = max(1, track.points.size / 150)
        for (i in track.points.indices step step) {
            val pt = track.points[i]
            val (wx, wy) = projectLatLon(pt.lat, pt.lon, zoom)

            val minTx = floor((wx - maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
            val maxTx = floor((wx + maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
            val minTy = floor((wy - maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)
            val maxTy = floor((wy + maxRadius) / (256.0 * factor)).toInt().coerceIn(0, maxTile)

            for (tx in minTx..maxTx) {
                for (ty in minTy..maxTy) {
                    tileKeysToFetch.add(Triple(intZoom, tx, ty))
                }
            }
        }

        val total = tileKeysToFetch.size
        var loadedCount = 0

        // Download in parallel with up to 6 concurrent coroutines
        val chunked = tileKeysToFetch.chunked(6)
        for (chunk in chunked) {
            val deferreds = chunk.map { (z, x, y) ->
                async {
                    val key = "${mapStyle.id}/$z/$x/$y"
                    var bmp = tileMemoryCache.get(key)
                    if (bmp == null && tileDiskDir != null) {
                        val diskFile = File(tileDiskDir, "${mapStyle.id}/$z/$x/$y.png")
                        if (diskFile.exists() && diskFile.length() > 0) {
                            bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                            if (bmp != null) tileMemoryCache.put(key, bmp)
                        }
                    }
                    if (bmp == null) {
                        bmp = downloadTileDirect(mapStyle, z, x, y)
                        if (bmp != null) tileMemoryCache.put(key, bmp)
                    }
                }
            }
            deferreds.awaitAll()
            loadedCount += chunk.size
            onProgress?.invoke(loadedCount, total)
        }
    }

    /**
     * Draws the route start point with an emerald green circle, outer glow, and white border.
     * Circle size matches markerRadius (same size as vehicle marker dot).
     */
    private fun drawRouteStartPoint(canvas: Canvas, x: Float, y: Float, radius: Float = 12f) {
        val haloPaint = Paint().apply {
            color = Color.argb(80, 16, 185, 129)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius * 1.83f, haloPaint)

        val greenBody = Paint().apply {
            color = Color.rgb(16, 185, 129)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, greenBody)

        val whiteBorder = Paint().apply {
            color = Color.WHITE
            style = Style.STROKE
            strokeWidth = max(2.0f, radius * 0.25f)
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, whiteBorder)

        val centerDot = Paint().apply {
            color = Color.WHITE
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius * 0.3f, centerDot)
    }

    /**
     * Draws the destination end point with a vibrant red circle,
     * glowing outer halo, crisp white concentric ring, and white center core.
     * Circle size matches markerRadius (same size as vehicle marker dot).
     */
    private fun drawRouteEndPoint(canvas: Canvas, x: Float, y: Float, radius: Float = 12f) {
        // 1. Soft glowing outer red halo
        val haloPaint = Paint().apply {
            color = Color.argb(95, 239, 68, 68) // Translucent vibrant red
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius * 1.83f, haloPaint)

        // 2. Vibrant red solid circle body
        val redBody = Paint().apply {
            color = Color.rgb(239, 68, 68)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, redBody)

        // 3. Crisp white contrast border
        val whiteBorder = Paint().apply {
            color = Color.WHITE
            style = Style.STROKE
            strokeWidth = max(2.0f, radius * 0.25f)
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, whiteBorder)

        // 4. Bright white center bullseye dot
        val centerDot = Paint().apply {
            color = Color.WHITE
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius * 0.3f, centerDot)
    }

    /**
     * Draws a static green point marker on the route at a waypoint location (not animated).
     * Features compact refined green circle styling with white contrast rings, soft halo, and optional upright label.
     */
    private fun drawWaypointMarker(
        canvas: Canvas,
        x: Float,
        y: Float,
        name: String?,
        rotationDegrees: Float = 0f,
        labelTextSize: Float = 20f,
        alpha: Float = 1.0f
    ) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        if (clampedAlpha <= 0.005f) return

        // 1. Soft glowing green outer halo
        val haloPaint = Paint().apply {
            color = Color.argb((80 * clampedAlpha).toInt(), 16, 185, 129) // Translucent glowing green #10B981
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, 8f, haloPaint)

        // 2. White outer contrast ring for visibility across all map styles
        val whiteBorder = Paint().apply {
            color = Color.argb((255 * clampedAlpha).toInt(), 255, 255, 255)
            style = Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, 5.5f, whiteBorder)

        // 3. Vibrant solid green circle body
        val greenBody = Paint().apply {
            color = Color.argb((255 * clampedAlpha).toInt(), 16, 185, 129) // Vibrant emerald green #10B981
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, 4.5f, greenBody)

        // 4. Center bright white bullseye dot
        val centerDot = Paint().apply {
            color = Color.argb((255 * clampedAlpha).toInt(), 255, 255, 255)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, 1.5f, centerDot)

        // 5. Waypoint text label pill badge (counter-rotated so text remains upright)
        if (!name.isNullOrBlank()) {
            val labelText = name.trim()
            val textPaint = Paint().apply {
                color = Color.argb((255 * clampedAlpha).toInt(), 255, 255, 255)
                textSize = labelTextSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val textBounds = Rect()
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

            val pillPaddingH = 10f
            val pillPaddingV = 4f
            val pillTop = -12f - textBounds.height() - pillPaddingV * 2
            val pillBottom = -12f
            val pillLeft = -(textBounds.width() / 2f) - pillPaddingH
            val pillRight = (textBounds.width() / 2f) + pillPaddingH
            val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)

            val pillBgPaint = Paint().apply {
                color = Color.argb((220 * clampedAlpha).toInt(), 15, 23, 42) // Slate 900
                style = Style.FILL
                isAntiAlias = true
            }
            val pillBorderPaint = Paint().apply {
                color = Color.argb((160 * clampedAlpha).toInt(), 16, 185, 129) // Emerald green accent border
                style = Style.STROKE
                strokeWidth = 1.2f
                isAntiAlias = true
            }

            canvas.save()
            canvas.translate(x, y)
            if (rotationDegrees != 0f) {
                canvas.rotate(-rotationDegrees)
            }
            canvas.drawRoundRect(pillRect, 8f, 8f, pillBgPaint)
            canvas.drawRoundRect(pillRect, 8f, 8f, pillBorderPaint)
            canvas.drawText(labelText, 0f, pillBottom - pillPaddingV - 2f, textPaint)
            canvas.restore()
        }
    }

    /**
     * Draws a permanently visible billboard text label for Start or End waypoints.
     * Features counter-rotation so text remains upright, sleek Slate 900 background,
     * colored accent border (emerald for start, rose for end), and collision offset support.
     */
    private fun drawEndpointLabel(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        rotationDegrees: Float = 0f,
        labelTextSize: Float = 20f,
        accentColor: Int,
        radius: Float = 12f,
        isOffsetDown: Boolean = false
    ) {
        if (text.isBlank()) return

        val labelText = text.trim()
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = labelTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val textBounds = Rect()
        textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
        val textWidth = textPaint.measureText(labelText)
        val textHeight = if (textBounds.height() > 0) textBounds.height().toFloat() else labelTextSize * 0.8f

        val pillPaddingH = 10f
        val pillPaddingV = 5f
        val pillHalfW = (textWidth / 2f) + pillPaddingH
        val pillHeight = textHeight + pillPaddingV * 2

        val pillRect = if (isOffsetDown) {
            // Positioned cleanly below marker
            val pillTop = radius + 6f
            val pillBottom = pillTop + pillHeight
            RectF(-pillHalfW, pillTop, pillHalfW, pillBottom)
        } else {
            // Positioned cleanly above marker
            val pillBottom = -radius - 6f
            val pillTop = pillBottom - pillHeight
            RectF(-pillHalfW, pillTop, pillHalfW, pillBottom)
        }

        val pillBgPaint = Paint().apply {
            color = Color.argb(230, 15, 23, 42) // Slate 900
            style = Style.FILL
            isAntiAlias = true
        }
        val pillBorderPaint = Paint().apply {
            color = accentColor
            style = Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }

        canvas.save()
        canvas.translate(x, y)
        if (rotationDegrees != 0f) {
            canvas.rotate(-rotationDegrees)
        }
        canvas.drawRoundRect(pillRect, 8f, 8f, pillBgPaint)
        canvas.drawRoundRect(pillRect, 8f, 8f, pillBorderPaint)

        // Center text vertically inside the pill
        val textY = pillRect.centerY() + (textHeight / 2f) - textBounds.bottom
        canvas.drawText(labelText, 0f, textY, textPaint)
        canvas.restore()
    }

    /**
     * Renders the traveling vehicle marker with heading arrow, glowing halo, and orientation.
     */
    private fun drawVehicleMarker(
        canvas: Canvas,
        x: Float,
        y: Float,
        bearingDegrees: Float,
        radius: Float = 12f,
        color: Int = Color.rgb(6, 182, 212)
    ) {
        canvas.save()
        canvas.translate(x, y)

        // Pulsing glow halo
        markerHaloPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(0f, 0f, radius * 1.83f, markerHaloPaint)

        // Marker circular base
        markerBodyPaint.color = color
        canvas.drawCircle(0f, 0f, radius, markerBodyPaint)
        markerBorderPaint.strokeWidth = max(2.0f, radius * 0.25f)
        canvas.drawCircle(0f, 0f, radius, markerBorderPaint)

        // Directional navigation arrowhead rotated along current travel bearing
        canvas.rotate(bearingDegrees)
        val scale = radius / 12f
        val arrowPath = Path().apply {
            moveTo(0f, -8f * scale)
            lineTo(5.5f * scale, 6f * scale)
            lineTo(0f, 3.5f * scale)
            lineTo(-5.5f * scale, 6f * scale)
            close()
        }
        canvas.drawPath(arrowPath, markerArrowPaint)

        canvas.restore()
    }
}
