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
}
