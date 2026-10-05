package com.gpxami.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.Paint.Cap
import android.graphics.Paint.Join
import android.graphics.Paint.Style
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import com.gpxami.app.data.geocoding.AdminDivisionResolver
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.data.model.Wpt
import com.gpxami.app.data.model.WptVisibilityMode
import com.gpxami.app.map.CameraViewportHelper
import com.gpxami.app.map.MapRenderer
import com.gpxami.app.map.MapStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ln
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Off-screen hardware video encoder pipeline using Android MediaCodec + OpenGL ES CodecInputSurface + MediaMuxer.
 * Encodes a 16:9 animation (1080p/720p at 30/60 fps) of the map and the synchronized
 * bottom 1/5 elevation overlay directly into an MP4 video saved to MediaStore.
 */
class VideoEncoder(
    private val context: Context
) {
    data class ExportConfig(
        val width: Int = 1920,
        val height: Int = 1080, // Strict 16:9 aspect ratio
        val fps: Int = 30,
        val durationSeconds: Int = 15,
        val bitrate: Int = 8_000_000,
        val showElevationProfile: Boolean = true,
        val showWaypointLabels: Boolean = true,
        val zoomOffset: Double = 0.0,
        val rotationDegrees: Float = 0f,
        val mapStyle: MapStyle = MapStyle.OPEN_STREET_MAP,
        val panOffsetX: Float = 0f,
        val panOffsetY: Float = 0f,
        val videoTitle: String = "GPX Route",
        val titleTextSize: Float = 36f,
        val wptLabelTextSize: Float = 20f,
        val markerRadius: Float = 12f,
        val markerColor: Int = 0xFF06B6D4.toInt(),
        val trackWidth: Float = 5.0f,
        val trackColor: Int = 0xFF00F2FE.toInt(),
        val uiViewportWidth: Float = 0f,
        val uiViewportHeight: Float = 0f,
        val uiDensity: Float = 2.75f,
        val wptPauseDurationSec: Float = 2.0f,
        val wptVisibilityMode: WptVisibilityMode = WptVisibilityMode.ALWAYS_SHOW,
        val showAdminDivisionSubtitle: Boolean = false,
        val startWpt: Wpt? = null,
        val endWpt: Wpt? = null,
        val adminDivisionConfig: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY
    )

    private class EncodingSession {
        var videoTrackIndex: Int = -1
        var muxerStarted: Boolean = false
    }

    private data class FrameCameraState(
        val progress: Float,
        val zoom: Double,
        val focus: Pair<Double, Double>,
        val panX: Float,
        val panY: Float,
        val wptAlphaMap: Map<Int, Float>? = null
    )

    private data class MidFrameState(
        val progress: Float,
        val pausedWptIndex: Int? = null,
        val pauseFrameIdx: Int = 0,
        val totalPauseFrames: Int = 0
    )

    /**
     * Executes off-screen video encoding asynchronously.
     * Reports progress via [onProgress] callback (0.0f .. 1.0f).
     */
    suspend fun exportTrackVideo(
        track: GpxTrack,
        config: ExportConfig,
        onProgress: (progress: Float, currentFrame: Int, totalFrames: Int) -> Unit
    ): Result<Uri> = withContext(Dispatchers.Default) {
        if (track.points.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Track has no points to export"))
        }

        val tempFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val mapRenderer = MapRenderer(context.cacheDir)
        val adminDivisionResolver = AdminDivisionResolver(context)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var codecInputSurface: CodecInputSurface? = null
        var offscreenBitmap: Bitmap? = null

        val session = EncodingSession()

        try {
            val totalFrames = config.durationSeconds * config.fps

            // Compute scaling factors between UI viewport and export video resolution
            val effectiveUiW = if (config.uiViewportWidth > 0f) config.uiViewportWidth else config.width.toFloat()
            val effectiveUiH = if (config.uiViewportHeight > 0f) config.uiViewportHeight else config.height.toFloat()
            val scale = config.height.toFloat() / effectiveUiH

            // [核心修正]: 透過共用函式判定有效起點是否位於當前可視範圍內
            val initialCam = CameraViewportHelper.determineInitialCamera(
                track = track,
                viewportWidth = effectiveUiW,
                viewportHeight = effectiveUiH,
                currentZoomOffset = config.zoomOffset,
                currentPanX = config.panOffsetX,
                currentPanY = config.panOffsetY,
                mapRotation = config.rotationDegrees,
                showElevationProfile = config.showElevationProfile
            )

            val effectiveZoomOffset = if (initialCam.isStartInViewport) initialCam.zoomOffset else 0.0
            val effectivePanX = if (initialCam.isStartInViewport) initialCam.panOffsetX * scale else 0f
            val effectivePanY = if (initialCam.isStartInViewport) initialCam.panOffsetY * scale else 0f

            // Geographic map zoom & pan matching:
            // uiOverviewZoom is the optimal zoom to fit entire route considering rotation and elevation overlay
            val uiOverviewZoom = mapRenderer.calculateOptimalOverviewZoom(
                track = track,
                viewportWidth = effectiveUiW,
                viewportHeight = effectiveUiH,
                rotationDegrees = config.rotationDegrees,
                showElevationProfile = config.showElevationProfile
            )
            // User operated zoom scale in UI preview container (or optimal 0.0 if start was off-screen)
            val uiBaseZoom = uiOverviewZoom + 1.0 + effectiveZoomOffset
            val targetZoom = uiBaseZoom + ln(scale.toDouble()) / ln(2.0)
            val targetOverviewZoom = uiOverviewZoom + ln(scale.toDouble()) / ln(2.0)

            // Timing allocation:
            // 1. 2秒做為漸近開始 (Intro: 2.0s)
            // 2. 2秒做為漸近結束 (Outro: 2.0s)
            // 3. 每個 WPT 停留 N 秒鐘 (Each intermediate WPT: config.wptPauseDurationSec pause)
            val introFrames = (2.0f * config.fps).toInt().coerceAtMost(totalFrames / 4)
            val outroFrames = (2.0f * config.fps).toInt().coerceAtMost(totalFrames / 4)
            val midFrames = (totalFrames - introFrames - outroFrames).coerceAtLeast(1)

            // Identify intermediate waypoints along the route (between 2% and 98% distance)
            val wptsOnTrack = track.waypoints
                .map { track.findClosestProgress(it.lat, it.lon) }
                .filter { it in 0.02f..0.98f }
                .sorted()

            val distinctWpts = mutableListOf<Float>()
            for (wp in wptsOnTrack) {
                if (distinctWpts.none { kotlin.math.abs(it - wp) < 0.015f }) {
                    distinctWpts.add(wp)
                }
            }

            val wptProgressList = track.waypoints.mapIndexed { i, wpt ->
                Pair(i, track.findClosestProgress(wpt.lat, wpt.lon))
            }

            val wptCount = distinctWpts.size
            val maxPauseFramesTotal = (midFrames * 0.70f).toInt()
            val requestedPauseFrames = (config.wptPauseDurationSec * config.fps).toInt()
            val pauseFramesPerWpt = if (wptCount > 0 && config.wptPauseDurationSec > 0f) {
                min(requestedPauseFrames, maxPauseFramesTotal / wptCount)
            } else 0
            val totalPauseFrames = wptCount * pauseFramesPerWpt
            val movingFrames = (midFrames - totalPauseFrames).coerceAtLeast(1)

            val waypointsAnchors = mutableListOf<Float>()
            waypointsAnchors.add(0.0f)
            waypointsAnchors.addAll(distinctWpts)
            waypointsAnchors.add(1.0f)

            val segmentCount = waypointsAnchors.size - 1
            val segmentFrames = IntArray(segmentCount)
            var allocatedMovingFrames = 0
            for (i in 0 until segmentCount) {
                val span = (waypointsAnchors[i + 1] - waypointsAnchors[i]).coerceAtLeast(0.0001f)
                val f = (movingFrames * span).toInt().coerceAtLeast(1)
                segmentFrames[i] = f
                allocatedMovingFrames += f
            }
            segmentFrames[segmentCount - 1] += (movingFrames - allocatedMovingFrames)

            fun getMidFrameState(midIdx: Int): MidFrameState {
                var remaining = midIdx
                for (i in 0 until segmentCount) {
                    val segF = segmentFrames[i]
                    if (remaining < segF) {
                        // Continuous uniform progress along the track points (strictly linear, no jumps)
                        val pStart = waypointsAnchors[i]
                        val pEnd = waypointsAnchors[i + 1]
                        val segT = when {
                            i == 0 && segmentCount == 1 -> remaining.toFloat() / max(1, segF - 1).toFloat()
                            i == 0 -> remaining.toFloat() / max(1, segF).toFloat()
                            i == segmentCount - 1 -> (remaining + 1).toFloat() / max(1, segF).toFloat()
                            else -> (remaining + 1).toFloat() / (segF + 1).toFloat()
                        }
                        return MidFrameState(progress = (pStart + (pEnd - pStart) * segT).coerceIn(0f, 1f))
                    }
                    remaining -= segF

                    if (i < wptCount && pauseFramesPerWpt > 0) {
                        if (remaining < pauseFramesPerWpt) {
                            return MidFrameState(
                                progress = distinctWpts[i],
                                pausedWptIndex = i,
                                pauseFrameIdx = remaining,
                                totalPauseFrames = pauseFramesPerWpt
                            )
                        }
                        remaining -= pauseFramesPerWpt
                    }
                }
                return MidFrameState(progress = 1.0f)
            }

            // Element sizes (WPT, marker, track width, black border) scaled proportionally to match UI
            val scaledWptSize = config.wptLabelTextSize * config.uiDensity * scale
            val scaledMarkerRadius = config.markerRadius * config.uiDensity * scale
            val scaledTrackWidth = config.trackWidth * config.uiDensity * scale
            val scaledBlackBorder = MapRenderer.BLACK_BORDER_WIDTH * config.uiDensity * scale

            // 1. Preload all visible map tiles along the route for both follow zoom and overview zoom
            mapRenderer.preloadTilesForTrack(
                track = track,
                zoom = targetZoom,
                width = config.width.toFloat(),
                height = config.height.toFloat(),
                mapStyle = config.mapStyle
            )
            mapRenderer.preloadTilesForTrack(
                track = track,
                zoom = targetOverviewZoom,
                width = config.width.toFloat(),
                height = config.height.toFloat(),
                mapStyle = config.mapStyle
            )

            // Pre-fetch administrative divisions along the route so frame rendering is never blocked
            if (config.showAdminDivisionSubtitle) {
                adminDivisionResolver.preloadForTrack(track)
            }

            // 2. Configure MediaCodec video encoder
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
            val format = MediaFormat.createVideoFormat(mimeType, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()

            // Initialize OpenGL ES surface to provide deterministic PTS timestamps to MediaCodec
            codecInputSurface = CodecInputSurface(inputSurface)
            encoder.start()

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()

            offscreenBitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(offscreenBitmap)

            // Start coordinate, destination coordinate and track bounds center coordinate for smooth camera motion
            val startPt = track.points.first()
            val endPt = track.points.last()
            val centerLat = track.bounds.centerLat
            val centerLon = track.bounds.centerLon

            // 3. Render loop: Frame by frame with deterministic PTS timestamps
            for (frameIndex in 0 until totalFrames) {
                if (!coroutineContext.isActive) {
                    throw InterruptedException("Video export was cancelled by user")
                }

                // [影片產出需求]: 影片需以使用者操作地圖的最後比例開始 (若在可視範圍內) 或最適比例 (若不在範圍內)
                // Phase 1: 2.0s 漸近開始 (起點依判定置中/視角開始)
                // Phase 2: Route travel with pause at each WPT (鏡頭跟隨行進標記)
                // Phase 3: 2.0s 漸近結束 (Outro with graceful zoom-out and pan to entire route overview - Fit Bounds)
                val (progress, frameZoom, frameFocus, framePanX, framePanY, wptAlphaMap) = when {
                    frameIndex < introFrames -> {
                        // 2.0s 漸近開始: 起點固定在起點座標，維持設定視角
                        FrameCameraState(0.0f, targetZoom, Pair(startPt.lat, startPt.lon), effectivePanX, effectivePanY, emptyMap())
                    }
                    frameIndex < totalFrames - outroFrames -> {
                        // Travel + WPT pauses
                        val midIndex = frameIndex - introFrames
                        val state = getMidFrameState(midIndex)
                        val p = state.progress
                        val pt = track.interpolate(p)
                        val focus = Pair(pt.lat, pt.lon)

                        val alphaMap = if (config.wptVisibilityMode == WptVisibilityMode.ONLY_DURING_PAUSE) {
                            if (state.pausedWptIndex != null && state.totalPauseFrames > 0) {
                                val pauseFrameIdx = state.pauseFrameIdx
                                val pauseTotal = state.totalPauseFrames
                                val fadeFrames = min((0.35f * config.fps).toInt(), pauseTotal / 2)
                                val alpha = when {
                                    fadeFrames <= 0 -> 1.0f
                                    pauseFrameIdx < fadeFrames -> (pauseFrameIdx.toFloat() / fadeFrames.toFloat()).coerceIn(0f, 1f)
                                    pauseFrameIdx >= pauseTotal - fadeFrames -> ((pauseTotal - 1 - pauseFrameIdx).toFloat() / fadeFrames.toFloat()).coerceIn(0f, 1f)
                                    else -> 1.0f
                                }
                                val stopProg = distinctWpts[state.pausedWptIndex]
                                val matchingIndices = wptProgressList.filter { abs(it.second - stopProg) < 0.015f }.map { it.first }
                                matchingIndices.associateWith { alpha }
                            } else if (config.wptPauseDurationSec == 0f) {
                                // Edge Case (Pause Duration = 0s): smooth fade-in/fade-out as marker passes WPT
                                val map = mutableMapOf<Int, Float>()
                                for ((idx, wptP) in wptProgressList) {
                                    val dist = abs(p - wptP)
                                    val window = 0.012f
                                    if (dist < window) {
                                        val a = (1.0f - dist / window).coerceIn(0f, 1f)
                                        if (a > 0.005f) map[idx] = a
                                    }
                                }
                                map
                            } else {
                                emptyMap()
                            }
                        } else {
                            null // ALWAYS_SHOW: renderMap handles null as 100% opacity
                        }

                        FrameCameraState(p, targetZoom, focus, effectivePanX, effectivePanY, alphaMap)
                    }
                    else -> {
                        // 2.0s 漸近結束: graceful ease-out zoom and pan to entire route overview (Fit Bounds)
                        val outroIdx = frameIndex - (totalFrames - outroFrames)
                        val t = (outroIdx + 1).toFloat() / outroFrames.toFloat()
                        val ease = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
                        val currentZoom = targetZoom + (targetOverviewZoom - targetZoom) * ease
                        val curLat = endPt.lat + (centerLat - endPt.lat) * ease
                        val curLon = endPt.lon + (centerLon - endPt.lon) * ease
                        val curPanX = effectivePanX * (1f - ease)
                        val curPanY = effectivePanY * (1f - ease)
                        FrameCameraState(1.0f, currentZoom, Pair(curLat, curLon), curPanX, curPanY, emptyMap())
                    }
                }

                val interpolated = track.interpolate(progress)

                // Render Map Layer with user-selected rotation, zoom, mapStyle, and North compass rose
                mapRenderer.renderMap(
                    canvas = canvas,
                    width = config.width.toFloat(),
                    height = config.height.toFloat(),
                    track = track,
                    currentProgress = progress,
                    interpolatedPoint = interpolated,
                    autoFollowMarker = false,
                    customZoom = frameZoom,
                    focusPoint = frameFocus,
                    rotationDegrees = config.rotationDegrees,
                    mapStyle = config.mapStyle,
                    drawCompassOverlay = true,
                    panOffsetX = framePanX,
                    panOffsetY = framePanY,
                    showWaypointLabels = config.showWaypointLabels,
                    wptLabelTextSize = scaledWptSize,
                    markerRadius = scaledMarkerRadius,
                    markerColor = config.markerColor,
                    trackWidth = scaledTrackWidth,
                    trackColor = config.trackColor,
                    blackBorderWidth = scaledBlackBorder,
                    wptVisibilityMode = config.wptVisibilityMode,
                    wptAlphaMap = wptAlphaMap,
                    startWpt = config.startWpt,
                    endWpt = config.endWpt,
                    adminDivisionConfig = config.adminDivisionConfig
                )

                // Render Top-Left Title & Administrative Division Subtitle Overlay
                if (config.videoTitle.isNotBlank()) {
                    val subtitle = if (config.showAdminDivisionSubtitle) {
                        adminDivisionResolver.resolveSync(interpolated.lat, interpolated.lon, config.adminDivisionConfig)
                    } else null

                    renderVideoTitle(
                        canvas = canvas,
                        width = config.width.toFloat(),
                        height = config.height.toFloat(),
                        title = config.videoTitle,
                        subtitle = subtitle,
                        titleTextSize = config.titleTextSize,
                        density = config.uiDensity,
                        scale = scale
                    )
                }

                // Render Bottom 1/5 Elevation Profile Overlay (if enabled)
                if (config.showElevationProfile) {
                    renderBottomElevationOverlay(
                        canvas = canvas,
                        width = config.width.toFloat(),
                        height = config.height.toFloat(),
                        track = track,
                        progress = progress,
                        interpolated = interpolated
                    )
                }

                // Compute exact presentation timestamp (PTS) in nanoseconds
                val ptsNs = (frameIndex.toLong() * 1_000_000_000L) / config.fps

                // Submit frame to MediaCodec via OpenGL surface
                codecInputSurface.drawFrame(offscreenBitmap, ptsNs)

                // Drain encoder output buffers and write to muxer
                drainEncoder(encoder, muxer, bufferInfo, false, session)

                // Report progress to caller
                val overallProgress = (frameIndex + 1).toFloat() / totalFrames
                onProgress(overallProgress, frameIndex + 1, totalFrames)
            }

            // 4. Signal End of Input Stream and Drain Remaining Packets
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, bufferInfo, true, session)

            // 5. Stop and release encoder & muxer
            encoder.stop()
            encoder.release()
            encoder = null

            if (session.muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            codecInputSurface.release()
            codecInputSurface = null

            inputSurface.release()
            inputSurface = null

            offscreenBitmap.recycle()
            offscreenBitmap = null

            // 6. Save output MP4 file to Android MediaStore
            val mediaStoreUri = saveVideoToMediaStore(context, tempFile)
            tempFile.delete()

            Result.success(mediaStoreUri)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) { }
            try {
                muxer?.release()
            } catch (_: Exception) { }
            try {
                codecInputSurface?.release()
            } catch (_: Exception) { }
            try {
                inputSurface?.release()
            } catch (_: Exception) { }
            try {
                offscreenBitmap?.recycle()
            } catch (_: Exception) { }
        }
    }

    /**
     * Drains encoded video data from MediaCodec into MediaMuxer.
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        session: EncodingSession
    ) {
        val timeoutUs = 10_000L

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (session.muxerStarted) {
                        throw RuntimeException("Video format changed twice during encoding")
                    }
                    val newFormat = encoder.outputFormat
                    session.videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    session.muxerStarted = true
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex) ?: continue

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && session.muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(session.videoTrackIndex, encodedData, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Renders the synchronized elevation profile chart strictly into the bottom 1/5 (20% height)
     * of the off-screen video canvas frame.
     */
    private fun renderBottomElevationOverlay(
        canvas: Canvas,
        width: Float,
        height: Float,
        track: GpxTrack,
        progress: Float,
        interpolated: InterpolatedPoint
    ) {
        val overlayHeight = height * 0.20f // Exactly bottom 20%
        val overlayTop = height - overlayHeight

        // 1. Semi-transparent dark glass gradient backdrop
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, overlayTop, 0f, height,
                intArrayOf(Color.argb(225, 11, 17, 32), Color.argb(252, 2, 6, 23)),
                null, Shader.TileMode.CLAMP
            )
            style = Style.FILL
        }
        canvas.drawRect(0f, overlayTop, width, height, bgPaint)

        // 2. Top accent neon line
        val topAccentPaint = Paint().apply {
            shader = LinearGradient(
                0f, overlayTop, width, overlayTop,
                intArrayOf(Color.argb(0, 0, 242, 254), Color.argb(220, 0, 242, 254), Color.argb(0, 0, 242, 254)),
                null, Shader.TileMode.CLAMP
            )
            strokeWidth = 3f
            style = Style.STROKE
        }
        canvas.drawLine(0f, overlayTop, width, overlayTop, topAccentPaint)

        val padStart = 110f
        val padEnd = 30f
        val padTop = overlayTop + 24f
        val padBottom = 34f

        val chartWidth = width - padStart - padEnd
        val chartHeight = (height - padBottom) - padTop
        if (chartWidth <= 0 || chartHeight <= 0) return

        val minEle = track.minElevation
        val maxEle = max(minEle + 10.0, track.maxElevation)
        val eleRange = maxEle - minEle
        val displayMinEle = (minEle - eleRange * 0.05).coerceAtLeast(0.0)
        val displayMaxEle = maxEle + eleRange * 0.05
        val displayEleRange = max(1.0, displayMaxEle - displayMinEle)
        val totalDistMeters = max(1.0, track.totalDistanceMeters)

        fun getX(distM: Double): Float = (padStart + (distM / totalDistMeters) * chartWidth).toFloat()
        fun getY(ele: Double): Float {
            val ratio = ((ele - displayMinEle) / displayEleRange).coerceIn(0.0, 1.0)
            return (padTop + chartHeight * (1.0 - ratio)).toFloat()
        }

        // 3. Grid Lines & Labels
        val textPaint = Paint().apply {
            color = Color.argb(160, 148, 163, 184)
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            isAntiAlias = true
        }
        val gridLinePaint = Paint().apply {
            color = Color.argb(35, 148, 163, 184)
            strokeWidth = 1.5f
            style = Style.STROKE
        }

        for (g in 0..2) {
            val ratio = g.toFloat() / 2f
            val eleVal = displayMinEle + (displayMaxEle - displayMinEle) * (1.0 - ratio)
            val y = padTop + chartHeight * ratio
            canvas.drawLine(padStart, y, width - padEnd, y, gridLinePaint)
            canvas.drawText("${eleVal.roundToInt()}m", 15f, y + 8f, textPaint)
        }

        // 4. Full Elevation Curves & Filled Area
        val cursorX = padStart + chartWidth * progress.coerceIn(0f, 1f)
        val bottomY = padTop + chartHeight

        val traversedPath = Path()
        val upcomingPath = Path()
        val fullStrokePath = Path()
        val traversedStrokePath = Path()

        val firstP = track.points[0]
        val firstX = getX(firstP.cumulativeDistanceMeters)
        val firstY = getY(firstP.elevation)

        fullStrokePath.moveTo(firstX, firstY)
        traversedPath.moveTo(firstX, bottomY)
        traversedPath.lineTo(firstX, firstY)
        traversedStrokePath.moveTo(firstX, firstY)

        for (p in track.points) {
            val px = getX(p.cumulativeDistanceMeters)
            val py = getY(p.elevation)
            fullStrokePath.lineTo(px, py)

            if (px <= cursorX) {
                traversedPath.lineTo(px, py)
                traversedStrokePath.lineTo(px, py)
            }
        }

        val cursorY = getY(interpolated.elevation)
        traversedPath.lineTo(cursorX, cursorY)
        traversedPath.lineTo(cursorX, bottomY)
        traversedPath.close()
        traversedStrokePath.lineTo(cursorX, cursorY)

        // Traversed Area Shader
        val traversedAreaPaint = Paint().apply {
            shader = LinearGradient(
                0f, padTop, 0f, bottomY,
                intArrayOf(Color.argb(120, 0, 242, 254), Color.argb(40, 6, 182, 212), Color.argb(5, 16, 185, 129)),
                null, Shader.TileMode.CLAMP
            )
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(traversedPath, traversedAreaPaint)

        // Upcoming Area Shader
        upcomingPath.moveTo(cursorX, bottomY)
        upcomingPath.lineTo(cursorX, cursorY)
        for (p in track.points) {
            val px = getX(p.cumulativeDistanceMeters)
            val py = getY(p.elevation)
            if (px > cursorX) upcomingPath.lineTo(px, py)
        }
        val lastP = track.points.last()
        upcomingPath.lineTo(getX(lastP.cumulativeDistanceMeters), bottomY)
        upcomingPath.close()

        val upcomingAreaPaint = Paint().apply {
            shader = LinearGradient(
                0f, padTop, 0f, bottomY,
                intArrayOf(Color.argb(40, 71, 85, 105), Color.argb(8, 51, 65, 85)),
                null, Shader.TileMode.CLAMP
            )
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(upcomingPath, upcomingAreaPaint)

        // Stroke Lines
        val fullStrokePaint = Paint().apply {
            color = Color.argb(90, 100, 116, 139)
            strokeWidth = 3f
            style = Style.STROKE
            strokeCap = Cap.ROUND
            strokeJoin = Join.ROUND
            isAntiAlias = true
        }
        canvas.drawPath(fullStrokePath, fullStrokePaint)

        val traversedGlowPaint = Paint().apply {
            color = Color.argb(90, 0, 242, 254)
            strokeWidth = 8f
            style = Style.STROKE
            strokeCap = Cap.ROUND
            strokeJoin = Join.ROUND
            isAntiAlias = true
        }
        canvas.drawPath(traversedStrokePath, traversedGlowPaint)

        val traversedCorePaint = Paint().apply {
            color = Color.rgb(0, 242, 254)
            strokeWidth = 4f
            style = Style.STROKE
            strokeCap = Cap.ROUND
            strokeJoin = Join.ROUND
            isAntiAlias = true
        }
        canvas.drawPath(traversedStrokePath, traversedCorePaint)

        // 5. Distance ticks along bottom
        val totalKm = track.totalDistanceKm
        for (k in 0..4) {
            val frac = k.toFloat() / 4f
            val kmVal = totalKm * frac
            val x = padStart + chartWidth * frac
            val txt = String.format(Locale.US, "%.1f km", kmVal)
            val txtW = textPaint.measureText(txt)
            canvas.drawText(txt, x - txtW / 2f, height - 8f, textPaint)
        }

        // 6. Synchronized Cursor Line & Glowing Dot
        val cursorLinePaint = Paint().apply {
            color = Color.argb(200, 0, 242, 254)
            strokeWidth = 2.5f
            style = Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(cursorX, padTop, cursorX, bottomY, cursorLinePaint)

        val haloPaint = Paint().apply {
            color = Color.argb(100, 0, 242, 254)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cursorX, cursorY, 14f, haloPaint)

        val dotRingPaint = Paint().apply {
            color = Color.rgb(6, 182, 212)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cursorX, cursorY, 8f, dotRingPaint)

        val dotCorePaint = Paint().apply {
            color = Color.WHITE
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cursorX, cursorY, 4f, dotCorePaint)

        // 7. Telemetry HUD Chip (Top-right of overlay)
        drawTelemetryChip(canvas, width - padEnd - 420f, overlayTop + 10f, interpolated)
    }

    /**
     * Draws telemetry HUD chip on the video frame.
     */
    private fun drawTelemetryChip(canvas: Canvas, x: Float, y: Float, pt: InterpolatedPoint) {
        val chipW = 410f
        val chipH = 40f

        val bgPaint = Paint().apply {
            color = Color.argb(190, 15, 23, 42)
            style = Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(x, y, x + chipW, y + chipH), 8f, 8f, bgPaint)

        val borderPaint = Paint().apply {
            color = Color.argb(60, 56, 189, 248)
            style = Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(x, y, x + chipW, y + chipH), 8f, 8f, borderPaint)

        val textPaint = Paint().apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            isAntiAlias = true
        }

        // Elevation
        textPaint.color = Color.rgb(0, 242, 254)
        canvas.drawText(String.format(Locale.US, "▲ %dm", pt.elevation.roundToInt()), x + 15f, y + 27f, textPaint)

        // Distance
        textPaint.color = Color.WHITE
        canvas.drawText(String.format(Locale.US, "%.1fkm", pt.cumulativeDistanceKm), x + 125f, y + 27f, textPaint)

        // Speed
        textPaint.color = Color.rgb(16, 185, 129)
        canvas.drawText(String.format(Locale.US, "%.1fkm/h", pt.speedKmh), x + 230f, y + 27f, textPaint)

        // Gradient
        textPaint.color = Color.rgb(245, 158, 11)
        val sign = if (pt.gradientPercent >= 0) "+" else ""
        canvas.drawText(String.format(Locale.US, "%s%.1f%%", sign, pt.gradientPercent), x + 340f, y + 27f, textPaint)
    }

    /**
     * Saves the output video file into Android's public MediaStore.Video collection.
     */
    private fun saveVideoToMediaStore(context: Context, videoFile: File): Uri {
        val contentResolver = context.contentResolver
        val filename = "GPXAmi_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GPXAmi")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore video entry")

        contentResolver.openOutputStream(videoUri)?.use { out ->
            FileInputStream(videoFile).use { input ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("Failed to write video data to MediaStore")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(videoUri, values, null, null)
        }

        return videoUri
    }

    /**
     * Renders a high-contrast title badge strictly in the top-left corner of the exported video frame.
     * Matches the UI title badge visual ratio 1-to-1, including administrative division subtitle (80% font size).
     */
    private fun renderVideoTitle(
        canvas: Canvas,
        width: Float,
        height: Float,
        title: String,
        subtitle: String? = null,
        titleTextSize: Float,
        density: Float,
        scale: Float
    ) {
        val titleText = title.trim()
        val subtitleText = subtitle?.trim()?.takeIf { it.isNotEmpty() }

        val targetTitleSizePx = titleTextSize * density * scale
        val targetSubtitleSizePx = targetTitleSizePx * 0.8f // Strict 80% of main Title font size

        val margin = 8f * density * scale
        val padH = 10f * density * scale
        val padV = 6f * density * scale
        val gap = 4f * density * scale

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = targetTitleSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.rgb(6, 182, 212) // Neon Cyan #06B6D4 matching preview badge
            textSize = targetSubtitleSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val titleBounds = Rect()
        titlePaint.getTextBounds(titleText, 0, titleText.length, titleBounds)

        val subtitleBounds = Rect()
        if (subtitleText != null) {
            subtitlePaint.getTextBounds(subtitleText, 0, subtitleText.length, subtitleBounds)
        }

        // Ensure title and subtitle fit within screen width comfortably
        val maxAvailableWidth = width - margin * 2.5f - padH * 2
        if (titleBounds.width() > maxAvailableWidth && titleBounds.width() > 0) {
            val scaleFactor = maxAvailableWidth / titleBounds.width()
            titlePaint.textSize = targetTitleSizePx * scaleFactor
            titlePaint.getTextBounds(titleText, 0, titleText.length, titleBounds)
        }
        if (subtitleText != null && subtitleBounds.width() > maxAvailableWidth && subtitleBounds.width() > 0) {
            val scaleFactor = maxAvailableWidth / subtitleBounds.width()
            subtitlePaint.textSize = targetSubtitleSizePx * scaleFactor
            subtitlePaint.getTextBounds(subtitleText, 0, subtitleText.length, subtitleBounds)
        }

        val maxTextWidth = max(titleBounds.width(), if (subtitleText != null) subtitleBounds.width() else 0)
        val badgeW = maxTextWidth + padH * 2
        val badgeH = titleBounds.height() + (if (subtitleText != null) subtitleBounds.height() + gap else 0f) + padV * 2

        val badgeRect = RectF(margin, margin, margin + badgeW, margin + badgeH)

        val bgPaint = Paint().apply {
            color = Color.argb(215, 15, 23, 42) // Slate 900 semi-transparent backdrop
            style = Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.argb(120, 56, 189, 248) // Subtle cyan/sky border
            style = Style.STROKE
            strokeWidth = max(2f, 1f * density * scale)
            isAntiAlias = true
        }

        val cornerRadius = 8f * density * scale
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, borderPaint)

        // Draw vertically stacked text: Title first, then Subtitle directly below it
        val titleY = margin + padV - titleBounds.top
        canvas.drawText(titleText, margin + padH, titleY, titlePaint)

        if (subtitleText != null) {
            val subtitleY = titleY + gap - subtitleBounds.top + titleBounds.bottom
            canvas.drawText(subtitleText, margin + padH, subtitleY, subtitlePaint)
        }
    }
}
