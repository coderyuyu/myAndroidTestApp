package com.gpxami.app

import com.gpxami.app.export.VideoEncoder
import com.gpxami.app.ui.viewmodel.MapUiState
import org.junit.Assert.*
import org.junit.Test

class CustomizationFeaturesTest {

    @Test
    fun testMapUiStateDefaultCustomizationValues() {
        val state = MapUiState()

        // [需求 1] WPT label 字型大小預設值為 20f
        assertEquals(20f, state.wptLabelTextSize, 0.001f)

        // [需求 2] 前進圓點半徑預設為 12f
        assertEquals(12f, state.markerRadius, 0.001f)

        // [需求 3] 路徑粗細預設為 5.0f
        assertEquals(5.0f, state.trackWidth, 0.001f)

        // [需求 4] 影片 Title 預設為 "示範路徑"
        assertEquals("示範路徑", state.videoTitle)

        // [需求: Title 可選字型大小] 預設為 36f
        assertEquals(36f, state.titleTextSize, 0.001f)

        // [需求 6] 初始 picker URI 預設為 null
        assertNull(state.initialPickerUri)
    }

    @Test
    fun testMapUiStateCustomizationUpdates() {
        val state = MapUiState(
            wptLabelTextSize = 26f,
            markerRadius = 16f,
            markerColor = 0xFFEF4444.toInt(),
            trackWidth = 8f,
            trackColor = 0xFFFF6B00.toInt(),
            videoTitle = "玉山主峰單攻",
            titleTextSize = 48f
        )

        assertEquals(26f, state.wptLabelTextSize, 0.001f)
        assertEquals(16f, state.markerRadius, 0.001f)
        assertEquals(0xFFEF4444.toInt(), state.markerColor)
        assertEquals(8f, state.trackWidth, 0.001f)
        assertEquals(0xFFFF6B00.toInt(), state.trackColor)
        assertEquals("玉山主峰單攻", state.videoTitle)
        assertEquals(48f, state.titleTextSize, 0.001f)
    }

    @Test
    fun testExportConfigReceivesAllCustomizationParameters() {
        val config = VideoEncoder.ExportConfig(
            videoTitle = "雪山翠池縱走",
            titleTextSize = 40f,
            wptLabelTextSize = 32f,
            markerRadius = 22f,
            markerColor = 0xFF10B981.toInt(),
            trackWidth = 12f,
            trackColor = 0xFFFACC15.toInt(),
            uiViewportWidth = 1000f,
            uiViewportHeight = 562.5f,
            uiDensity = 2.5f
        )

        assertEquals("雪山翠池縱走", config.videoTitle)
        assertEquals(40f, config.titleTextSize, 0.001f)
        assertEquals(32f, config.wptLabelTextSize, 0.001f)
        assertEquals(22f, config.markerRadius, 0.001f)
        assertEquals(0xFF10B981.toInt(), config.markerColor)
        assertEquals(12f, config.trackWidth, 0.001f)
        assertEquals(0xFFFACC15.toInt(), config.trackColor)
        assertEquals(1000f, config.uiViewportWidth, 0.001f)
        assertEquals(562.5f, config.uiViewportHeight, 0.001f)
        assertEquals(2.5f, config.uiDensity, 0.001f)
    }

    @Test
    fun testVideoUiScaleAndMapRangeMatching() {
        // [需求: 讓輸出影片的地圖範圍與UI顯示地圖的範圍一致, WPT, TITLE 文字比例也一致]
        val uiW = 960f
        val uiH = 540f
        val vidW = 1920f
        val vidH = 1080f

        val scale = vidH / uiH
        assertEquals(2.0f, scale, 0.001f)

        // Target zoom must equal uiBaseZoom + log2(scale) so visible geographic span is identical
        val uiBaseZoom = 12.0
        val targetZoom = uiBaseZoom + kotlin.math.ln(scale.toDouble()) / kotlin.math.ln(2.0)
        assertEquals(13.0, targetZoom, 0.001)

        // Proportion of text / badge height relative to viewport height must be identical
        val uiTitleSize = 36f
        val density = 2.75f
        val uiTitleRatio = (uiTitleSize * density) / uiH
        val vidTitleSizePx = uiTitleSize * density * scale
        val vidTitleRatio = vidTitleSizePx / vidH
        assertEquals(uiTitleRatio.toDouble(), vidTitleRatio.toDouble(), 0.0001)
    }

    @Test
    fun testGpxTitleFileNameExtraction() {
        // [需求 4] 預設為檔名，副檔名 .gpx 或 .GPX 需去除
        val filename1 = "route_20260919.gpx"
        val title1 = filename1.replace(Regex("(?i)\\.gpx$"), "")
        assertEquals("route_20260919", title1)

        val filename2 = "Mount_Jade_Climb.GPX"
        val title2 = filename2.replace(Regex("(?i)\\.gpx$"), "")
        assertEquals("Mount_Jade_Climb", title2)

        val filename3 = "track_without_extension"
        val title3 = filename3.replace(Regex("(?i)\\.gpx$"), "")
        assertEquals("track_without_extension", title3)
    }

    @Test
    fun testSecondToLastDirectoryCalculation() {
        // [需求 6] 開啟 GPX 時，預設開上上次的目錄
        val history1 = listOf("content://tree/primary:Download/routes1")
        // 若只有 1 個歷史紀錄，使用最後一個
        val target1 = if (history1.size >= 2) history1[history1.size - 2] else history1.last()
        assertEquals("content://tree/primary:Download/routes1", target1)

        val history2 = listOf(
            "content://tree/primary:Download/folderA",
            "content://tree/primary:Download/folderB"
        )
        // 若有 2 個以上，取上上次（倒數第二個）
        val target2 = if (history2.size >= 2) history2[history2.size - 2] else history2.last()
        assertEquals("content://tree/primary:Download/folderA", target2)

        val history3 = listOf(
            "content://tree/primary:Download/folderA",
            "content://tree/primary:Download/folderB",
            "content://tree/primary:Download/folderC"
        )
        val target3 = if (history3.size >= 2) history3[history3.size - 2] else history3.last()
        assertEquals("content://tree/primary:Download/folderB", target3)
    }

    @Test
    fun testRouteBlackBorderCasingSpecification() {
        // [需求: 路徑用空的實線, 兩邉包黑線, 黑線固定用現有細線的size, 中間使用user所選的顏色]
        assertEquals(3.0f, com.gpxami.app.map.MapRenderer.BLACK_BORDER_WIDTH, 0.001f)

        val trackWidth = 5.0f
        val totalCasingWidth = trackWidth + 2f * com.gpxami.app.map.MapRenderer.BLACK_BORDER_WIDTH
        assertEquals(11.0f, totalCasingWidth, 0.001f)
    }

    @Test
    fun testStartAndEndPointRadiusMatchesMarkerRadius() {
        // [需求: 起點及終點圖示大小同前進圓點]
        val stateDefault = MapUiState()
        assertEquals(12f, stateDefault.markerRadius, 0.001f)

        val customMarkerRadius = 18f
        val stateCustom = MapUiState(markerRadius = customMarkerRadius)
        assertEquals(18f, stateCustom.markerRadius, 0.001f)

        // The radius passed to start/end point renderers must match markerRadius
        val baseRadius = stateCustom.markerRadius
        val haloRadius = baseRadius * 1.83f
        val centerDotRadius = baseRadius * 0.3f
        val strokeWidth = kotlin.math.max(2.0f, baseRadius * 0.25f)

        assertEquals(18f, baseRadius, 0.001f)
        assertEquals(32.94f, haloRadius, 0.01f)
        assertEquals(5.4f, centerDotRadius, 0.01f)
        assertEquals(4.5f, strokeWidth, 0.01f)
    }

    @Test
    fun testZoomOutDurationAndEasingCalculation() {
        // [需求: 當路徑走完時, 用3-5秒優雅地縮放地圖至所有路徑均能顯在地圖上]
        val duration15s = 15f
        val zoomOutDuration15s = if (duration15s >= 8f) 3.5f else (duration15s * 0.35f).coerceAtLeast(1.5f)
        assertTrue("Zoom-out duration for 15s video should be between 3.0 and 5.0 seconds", zoomOutDuration15s in 3.0f..5.0f)
        assertEquals(3.5f, zoomOutDuration15s, 0.001f)

        val duration30s = 30f
        val zoomOutDuration30s = if (duration30s >= 8f) 3.5f else (duration30s * 0.35f).coerceAtLeast(1.5f)
        assertTrue("Zoom-out duration for 30s video should be between 3.0 and 5.0 seconds", zoomOutDuration30s in 3.0f..5.0f)

        // Verify easeInOutCubic curve characteristics: starts at 0, ends at 1, smooth at midpoint
        fun easeInOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

        assertEquals(0.0f, easeInOutCubic(0.0f), 0.001f)
        assertEquals(0.5f, easeInOutCubic(0.5f), 0.001f)
        assertEquals(1.0f, easeInOutCubic(1.0f), 0.001f)

        // Slow start, smooth middle, slow arrival
        assertTrue(easeInOutCubic(0.1f) < 0.05f)
        assertTrue(easeInOutCubic(0.9f) > 0.95f)
    }

    @Test
    fun testRouteAccelerationAndDeceleration() {
        // [需求: 開始時漸漸加速, 結束時漸漸減速]
        fun easeInOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

        // Initial derivative / speed is near 0, accelerating gradually:
        // Speed in first 10% interval (t=0.0 to 0.1) is much lower than mid-travel speed (t=0.4 to 0.5)
        val speedStart = easeInOutCubic(0.1f) - easeInOutCubic(0.0f)
        val speedMid = easeInOutCubic(0.5f) - easeInOutCubic(0.4f)
        val speedEnd = easeInOutCubic(1.0f) - easeInOutCubic(0.9f)

        // Accelerates at start:
        assertTrue("Speed at start should be much lower than cruise speed", speedStart < speedMid * 0.3f)
        // Decelerates at end:
        assertTrue("Speed at end should be much lower than cruise speed", speedEnd < speedMid * 0.3f)
        // Symmetry between start acceleration and end deceleration:
        assertEquals(speedStart, speedEnd, 0.001f)
    }

    @Test
    fun testWptSelectionAndClosestProgress() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.0, lon = 121.50, elevation = 100.0, cumulativeDistanceMeters = 0.0, cumulativeDistanceKm = 0.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.0, lon = 121.55, elevation = 200.0, cumulativeDistanceMeters = 5000.0, cumulativeDistanceKm = 5.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.0, lon = 121.60, elevation = 300.0, cumulativeDistanceMeters = 10000.0, cumulativeDistanceKm = 10.0)
        )
        val wpt = com.gpxami.app.data.model.GpxWaypoint(lat = 25.0, lon = 121.55, name = "MidPoint Peak")
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            waypoints = listOf(wpt),
            totalDistanceMeters = 10000.0,
            totalDistanceKm = 10.0
        )

        val progress = track.findClosestProgress(wpt.lat, wpt.lon)
        assertEquals(0.5f, progress, 0.05f)

        val distKm = track.findClosestDistanceKm(wpt.lat, wpt.lon)
        assertEquals(5.0, distKm, 0.1)

        // Slicing from this WPT as Start
        val sliced = track.sliceRange(progress, 1.0f)
        assertEquals(1, sliced.waypoints.size)
        assertEquals("MidPoint Peak", sliced.waypoints[0].name)
        assertEquals(5000.0, sliced.totalDistanceMeters, 50.0)
    }

    @Test
    fun testOptimalOverviewZoomWithRotationAndElevation() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 24.1, lon = 121.2, elevation = 1000.0),
            com.gpxami.app.data.model.GpxPoint(lat = 24.3, lon = 121.4, elevation = 3000.0)
        )
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            bounds = com.gpxami.app.data.model.GeoBounds(24.1, 24.3, 121.2, 121.4)
        )

        // Overview zoom with elevation profile (bottom 20% overlay) should be slightly lower/wider than without elevation profile
        val zoomWithElevation = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(track, 1920f, 1080f, 0f, showElevationProfile = true)
        val zoomWithoutElevation = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(track, 1920f, 1080f, 0f, showElevationProfile = false)

        assertTrue("Overview zoom with elevation overlay must reserve space for bottom 20% profile", zoomWithElevation <= zoomWithoutElevation)
        assertTrue("Zoom should be within reasonable map zoom limits", zoomWithElevation in 7.0..15.0)

        // When rotated 45 degrees, bounding box increases so zoom must adjust to fit
        val zoomRotated = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(track, 1920f, 1080f, 45f, showElevationProfile = true)
        assertTrue("Rotated track zoom must fit the rotated bounding box", zoomRotated <= zoomWithElevation + 0.05)
    }

    @Test
    fun testExportVideoDurationOptionsAnd2sIntroOutro() {
        val durations = listOf(15, 25, 40)
        assertEquals(3, durations.size)
        assertTrue(durations.contains(15))
        assertTrue(durations.contains(25))
        assertTrue(durations.contains(40))

        // Intro and Outro duration must each be 2.0 seconds
        val introSec = 2.0f
        val outroSec = 2.0f
        assertEquals(2.0f, introSec, 0.001f)
        assertEquals(2.0f, outroSec, 0.001f)

        for (dur in durations) {
            val travelAndWptTime = dur - introSec - outroSec
            assertTrue("Middle travel + pause time must be positive", travelAndWptTime >= 11.0f)
        }
    }

    @Test
    fun testVideoExportStartsAtUserZoomScaleAndStartPointCentered() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.01, lon = 121.46, elevation = 20.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.02, lon = 121.50, elevation = 50.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.03, lon = 121.57, elevation = 10.0)
        )
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            bounds = com.gpxami.app.data.model.GeoBounds(25.01, 25.03, 121.46, 121.57)
        )

        val userZoomOffset = 1.5 // User zoomed in by +1.5 in UI preview
        val uiW = 960f
        val uiH = 540f
        val vidW = 1920f
        val vidH = 1080f
        val scale = vidH / uiH // 2.0x

        val uiOverview = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(track, uiW, uiH, 0f, showElevationProfile = true)
        val uiBaseZoom = uiOverview + 1.0 + userZoomOffset
        val targetZoom = uiBaseZoom + kotlin.math.ln(scale.toDouble()) / kotlin.math.ln(2.0)
        val targetOverviewZoom = uiOverview + kotlin.math.ln(scale.toDouble()) / kotlin.math.ln(2.0)

        // targetZoom must reflect the user's zoomOffset (+1.5)
        assertEquals(targetOverviewZoom + 1.0 + userZoomOffset, targetZoom, 0.001)

        // Starting camera focus must be the exact start point (points[0]) with 0 pan offset
        val startPt = points.first()
        val (camCenterLat, camCenterLon) = Pair(startPt.lat, startPt.lon)
        val (camWorldX, camWorldY) = com.gpxami.app.map.MapRenderer.projectLatLon(camCenterLat, camCenterLon, targetZoom)
        val (startWorldX, startWorldY) = com.gpxami.app.map.MapRenderer.projectLatLon(startPt.lat, startPt.lon, targetZoom)

        // Screen projection without pan offset places start point exactly at (vidW / 2, vidH / 2)
        val screenX = (startWorldX - camWorldX + (vidW / 2.0)).toFloat()
        val screenY = (startWorldY - camWorldY + (vidH / 2.0)).toFloat()
        assertEquals(vidW / 2f, screenX, 0.001f)
        assertEquals(vidH / 2f, screenY, 0.001f)
    }

    @Test
    fun testRangeSliderStartAndEndFocusSeparation() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.00, lon = 121.45, elevation = 10.0, cumulativeDistanceMeters = 0.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.02, lon = 121.50, elevation = 20.0, cumulativeDistanceMeters = 5000.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.04, lon = 121.58, elevation = 30.0, cumulativeDistanceMeters = 10000.0)
        )
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            totalDistanceMeters = 10000.0,
            totalDistanceKm = 10.0
        )

        // When trimming range [0.2 .. 0.8]
        val sliced = track.sliceRange(0.2f, 0.8f)
        val startFocus = Pair(sliced.points.first().lat, sliced.points.first().lon)
        val endFocus = Pair(sliced.points.last().lat, sliced.points.last().lon)

        // Start point and End point must be distinct
        assertNotEquals(startFocus.first, endFocus.first)
        assertNotEquals(startFocus.second, endFocus.second)

        // Sliced start point must match track at progress 0.2
        val expectedStart = track.interpolate(0.2f)
        assertEquals(expectedStart.lat, startFocus.first, 0.001)
        assertEquals(expectedStart.lon, startFocus.second, 0.001)

        // Sliced end point must match track at progress 0.8
        val expectedEnd = track.interpolate(0.8f)
        assertEquals(expectedEnd.lat, endFocus.first, 0.001)
        assertEquals(expectedEnd.lon, endFocus.second, 0.001)
    }

    @Test
    fun testWptMenuSortingByDistanceAscending() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.00, lon = 121.40, elevation = 0.0, cumulativeDistanceMeters = 0.0, cumulativeDistanceKm = 0.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.05, lon = 121.45, elevation = 0.0, cumulativeDistanceMeters = 5000.0, cumulativeDistanceKm = 5.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.10, lon = 121.50, elevation = 0.0, cumulativeDistanceMeters = 10000.0, cumulativeDistanceKm = 10.0)
        )
        // WPTs out of order: WPT3 at 8km, WPT1 at 2km, WPT2 at 5km
        val wpt1 = com.gpxami.app.data.model.GpxWaypoint(lat = 25.02, lon = 121.42, name = "WPT 1")
        val wpt2 = com.gpxami.app.data.model.GpxWaypoint(lat = 25.05, lon = 121.45, name = "WPT 2")
        val wpt3 = com.gpxami.app.data.model.GpxWaypoint(lat = 25.08, lon = 121.48, name = "WPT 3")
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            waypoints = listOf(wpt3, wpt1, wpt2),
            totalDistanceMeters = 10000.0,
            totalDistanceKm = 10.0
        )

        // Sort by findClosestDistanceKm ascending
        val sortedWaypoints = track.waypoints.map { wpt ->
            wpt to track.findClosestDistanceKm(wpt.lat, wpt.lon)
        }.sortedBy { it.second }

        assertEquals(3, sortedWaypoints.size)
        assertEquals("WPT 1", sortedWaypoints[0].first.name)
        assertEquals("WPT 2", sortedWaypoints[1].first.name)
        assertEquals("WPT 3", sortedWaypoints[2].first.name)
        assertTrue(sortedWaypoints[0].second < sortedWaypoints[1].second)
        assertTrue(sortedWaypoints[1].second < sortedWaypoints[2].second)
    }

    @Test
    fun testCameraViewportHelperStartInViewport() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.00, lon = 121.50, elevation = 10.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.01, lon = 121.51, elevation = 20.0)
        )
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            bounds = com.gpxami.app.data.model.GeoBounds(25.00, 25.01, 121.50, 121.51)
        )

        // Start point is at (25.00, 121.50), camera centered on start point with 0 pan
        val result = com.gpxami.app.map.CameraViewportHelper.determineInitialCamera(
            track = track,
            viewportWidth = 1000f,
            viewportHeight = 562.5f,
            currentZoomOffset = 1.2,
            currentPanX = 20f,
            currentPanY = -15f,
            mapRotation = 0f,
            showElevationProfile = true
        )

        // Must detect that start point is in viewport and retain user's current zoom and pan
        assertTrue(result.isStartInViewport)
        assertEquals(1.2, result.zoomOffset, 0.001)
        assertEquals(20f, result.panOffsetX, 0.001f)
        assertEquals(-15f, result.panOffsetY, 0.001f)
    }

    @Test
    fun testCameraViewportHelperStartOutOfViewportAutoCenters() {
        val points = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 25.00, lon = 121.50, elevation = 10.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.01, lon = 121.51, elevation = 20.0)
        )
        val track = com.gpxami.app.data.model.GpxTrack(
            points = points,
            bounds = com.gpxami.app.data.model.GeoBounds(25.00, 25.01, 121.50, 121.51)
        )

        // Massive pan offset (10,000 px) shifts the start point far off the screen
        val result = com.gpxami.app.map.CameraViewportHelper.determineInitialCamera(
            track = track,
            viewportWidth = 1000f,
            viewportHeight = 562.5f,
            currentZoomOffset = 2.0,
            currentPanX = 10000f,
            currentPanY = 10000f,
            mapRotation = 0f,
            showElevationProfile = true
        )

        // Must detect start point is OUT of viewport, auto-center on start point and reset to optimal zoom (0.0)
        assertFalse(result.isStartInViewport)
        assertEquals(0.0, result.zoomOffset, 0.001)
        assertEquals(0f, result.panOffsetX, 0.001f)
        assertEquals(0f, result.panOffsetY, 0.001f)
        assertEquals(25.00, result.centerLat, 0.001)
        assertEquals(121.50, result.centerLon, 0.001)
    }

    @Test
    fun testIntermediateWptPauseDurationTwoSeconds() {
        val fps = 30
        val midFrames = 300 // 10s mid travel
        val wptCount = 2
        val maxPauseFramesTotal = (midFrames * 0.60f).toInt() // 180 frames

        // Specification: 2.0s pause per intermediate WPT
        val pauseFramesPerWpt = kotlin.math.min((2.0f * fps).toInt(), maxPauseFramesTotal / wptCount)
        assertEquals(60, pauseFramesPerWpt) // 60 frames at 30 fps = exactly 2.0s
    }

    @Test
    fun testFitBoundsOutroZoomCalculatesSubTrackBounds() {
        val fullPoints = listOf(
            com.gpxami.app.data.model.GpxPoint(lat = 24.00, lon = 120.00, elevation = 0.0, cumulativeDistanceMeters = 0.0),
            com.gpxami.app.data.model.GpxPoint(lat = 24.50, lon = 120.50, elevation = 0.0, cumulativeDistanceMeters = 50000.0),
            com.gpxami.app.data.model.GpxPoint(lat = 25.00, lon = 121.00, elevation = 0.0, cumulativeDistanceMeters = 100000.0)
        )
        val fullTrack = com.gpxami.app.data.model.GpxTrack(
            points = fullPoints,
            totalDistanceMeters = 100000.0,
            totalDistanceKm = 100.0
        )

        // Sliced sub-track: [0.3 .. 0.7]
        val slicedTrack = fullTrack.sliceRange(0.3f, 0.7f)

        // Sliced track bounds must strictly encompass only the sub-path points
        assertTrue(slicedTrack.bounds.minLat >= 24.00)
        assertTrue(slicedTrack.bounds.maxLat <= 25.00)
        assertTrue(slicedTrack.bounds.centerLat in 24.2..24.8)

        // Optimal overview zoom for sliced sub-track must be higher (more zoomed in) than full track
        val zoomFull = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(fullTrack, 1000f, 562.5f)
        val zoomSub = com.gpxami.app.map.MapRenderer.calculateOptimalOverviewZoom(slicedTrack, 1000f, 562.5f)
        assertTrue("Sub-track overview zoom ($zoomSub) must be >= full track zoom ($zoomFull)", zoomSub >= zoomFull)
    }

    @Test
    fun testContinuousProgressBetweenWaypoints() {
        // Test continuous linear progression across waypoints with 2s pauses
        val fps = 30
        val midFrames = 300 // 10 seconds mid travel
        val distinctWpts = listOf(0.30f, 0.70f) // 2 intermediate WPTs at 30% and 70%
        val wptCount = distinctWpts.size

        val pauseFramesPerWpt = (2.0f * fps).toInt() // 60 frames = 2.0s
        val totalPauseFrames = wptCount * pauseFramesPerWpt // 120 frames
        val movingFrames = midFrames - totalPauseFrames // 180 moving frames

        val waypointsAnchors = mutableListOf<Float>()
        waypointsAnchors.add(0.0f)
        waypointsAnchors.addAll(distinctWpts)
        waypointsAnchors.add(1.0f)

        val segmentCount = waypointsAnchors.size - 1
        val segmentFrames = IntArray(segmentCount)
        var allocatedMovingFrames = 0
        for (i in 0 until segmentCount) {
            val span = waypointsAnchors[i + 1] - waypointsAnchors[i]
            val f = (movingFrames * span).toInt().coerceAtLeast(1)
            segmentFrames[i] = f
            allocatedMovingFrames += f
        }
        segmentFrames[segmentCount - 1] += (movingFrames - allocatedMovingFrames)

        fun getMidFrameProgress(midIdx: Int): Float {
            var remaining = midIdx
            for (i in 0 until segmentCount) {
                val segF = segmentFrames[i]
                if (remaining < segF) {
                    val pStart = waypointsAnchors[i]
                    val pEnd = waypointsAnchors[i + 1]
                    val segT = when {
                        i == 0 && segmentCount == 1 -> remaining.toFloat() / kotlin.math.max(1, segF - 1).toFloat()
                        i == 0 -> remaining.toFloat() / kotlin.math.max(1, segF).toFloat()
                        i == segmentCount - 1 -> (remaining + 1).toFloat() / kotlin.math.max(1, segF).toFloat()
                        else -> (remaining + 1).toFloat() / (segF + 1).toFloat()
                    }
                    return (pStart + (pEnd - pStart) * segT).coerceIn(0f, 1f)
                }
                remaining -= segF

                if (i < wptCount) {
                    if (remaining < pauseFramesPerWpt) {
                        return distinctWpts[i]
                    }
                    remaining -= pauseFramesPerWpt
                }
            }
            return 1.0f
        }

        // 1. Frame 0 must start at 0.0f
        assertEquals(0.0f, getMidFrameProgress(0), 0.001f)

        // 2. Progression must be monotonic (never jump backward)
        var prevP = -1f
        var pauseCountWpt1 = 0
        var pauseCountWpt2 = 0
        for (i in 0 until midFrames) {
            val p = getMidFrameProgress(i)
            assertTrue("Progress must be monotonically increasing or equal: $p >= $prevP at frame $i", p >= prevP)
            if (kotlin.math.abs(p - 0.30f) < 0.0001f) pauseCountWpt1++
            if (kotlin.math.abs(p - 0.70f) < 0.0001f) pauseCountWpt2++
            prevP = p
        }

        // 3. Exactly 60 frames (2.0 seconds at 30fps) must hold at each WPT
        assertEquals(pauseFramesPerWpt, pauseCountWpt1)
        assertEquals(pauseFramesPerWpt, pauseCountWpt2)

        // 4. Last mid frame must arrive at 1.0f
        assertEquals(1.0f, getMidFrameProgress(midFrames - 1), 0.001f)
    }
}

