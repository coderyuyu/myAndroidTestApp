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
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
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
        val uiDensity: Float = 2.75f
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
        val panY: Float
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

            // Geographic map zoom & pan matching:
            // uiBaseZoom is the close-up follow zoom; uiOverviewZoom is the optimal zoom to fit entire route
            val uiOverviewZoom = mapRenderer.calculateOptimalZoom(track, effectiveUiW, effectiveUiH) + config.zoomOffset
            val uiBaseZoom = uiOverviewZoom + 0.8
            val targetZoom = uiBaseZoom + ln(scale.toDouble()) / ln(2.0)
            val targetOverviewZoom = uiOverviewZoom + ln(scale.toDouble()) / ln(2.0)

            val scaledPanX = config.panOffsetX * scale
            val scaledPanY = config.panOffsetY * scale

            // Timing allocation: 3.5s graceful zoom-out to overview if route duration is sufficient
            val totalDurationSec = config.durationSeconds.toFloat()
            val zoomOutDurationSec = if (totalDurationSec >= 8f) 3.5f else (totalDurationSec * 0.35f).coerceAtLeast(1.5f)
            val travelDurationSec = totalDurationSec - zoomOutDurationSec
            val travelFrames = (travelDurationSec * config.fps).toInt().coerceIn(1, totalFrames - 1)

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

            // Destination coordinate and track bounds center coordinate for smooth camera zoom out
            val endPt = track.points.last()
            val centerLat = track.bounds.centerLat
            val centerLon = track.bounds.centerLon

            // 3. Render loop: Frame by frame with deterministic PTS timestamps
            for (frameIndex in 0 until totalFrames) {
                if (!coroutineContext.isActive) {
                    throw InterruptedException("Video export was cancelled by user")
                }

                // Phase 1: Travel animation (0.0 to 1.0) with ease-in (accelerate at start) and ease-out (decelerate at end)
                // Phase 2: Route completed, 3-5s graceful ease-out zoom and pan to entire route overview
                val (progress, frameZoom, frameFocus, framePanX, framePanY) = if (frameIndex < travelFrames) {
                    val rawT = if (travelFrames > 1) frameIndex.toFloat() / (travelFrames - 1).toFloat() else 1.0f
                    // Smooth easeInOutCubic: accelerate gradually at start, smoothly decelerate at end
                    val p = if (rawT < 0.5f) {
                        4f * rawT * rawT * rawT
                    } else {
                        1f - (-2f * rawT + 2f).let { it * it * it } / 2f
                    }.coerceIn(0f, 1f)

                    val pt = track.interpolate(p)
                    val focus = Pair(pt.lat, pt.lon)
                    FrameCameraState(p, targetZoom, focus, scaledPanX, scaledPanY)
                } else {
                    val zoomOutFrames = totalFrames - travelFrames
                    val t = if (zoomOutFrames > 1) {
                        (frameIndex - travelFrames + 1).toFloat() / zoomOutFrames.toFloat()
                    } else 1.0f
                    // Smooth cubic ease-in-out curve
                    val ease = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
                    val currentZoom = targetZoom + (targetOverviewZoom - targetZoom) * ease
                    val curLat = endPt.lat + (centerLat - endPt.lat) * ease
                    val curLon = endPt.lon + (centerLon - endPt.lon) * ease
                    val curPanX = scaledPanX * (1f - ease)
                    val curPanY = scaledPanY * (1f - ease)
                    FrameCameraState(1.0f, currentZoom, Pair(curLat, curLon), curPanX, curPanY)
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
                    blackBorderWidth = scaledBlackBorder
                )

                // Render Top-Left Title Overlay in Video Frame matching UI scale (Requirement 4)
                if (config.videoTitle.isNotBlank()) {
                    renderVideoTitle(
                        canvas = canvas,
                        width = config.width.toFloat(),
                        height = config.height.toFloat(),
                        title = config.videoTitle,
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
     * Matches the UI title badge visual ratio 1-to-1.
     */
    private fun renderVideoTitle(
        canvas: Canvas,
        width: Float,
        height: Float,
        title: String,
        titleTextSize: Float,
        density: Float,
        scale: Float
    ) {
        val titleText = title.trim()
        val targetTextSizePx = titleTextSize * density * scale
        val margin = 8f * density * scale
        val padH = 10f * density * scale
        val padV = 6f * density * scale

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = targetTextSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val textBounds = Rect()
        textPaint.getTextBounds(titleText, 0, titleText.length, textBounds)

        // Ensure title fits within screen width comfortably
        val maxAvailableWidth = width - margin * 2.5f - padH * 2
        if (textBounds.width() > maxAvailableWidth && textBounds.width() > 0) {
            val scaleFactor = maxAvailableWidth / textBounds.width()
            textPaint.textSize = targetTextSizePx * scaleFactor
            textPaint.getTextBounds(titleText, 0, titleText.length, textBounds)
        }

        val badgeW = textBounds.width() + padH * 2
        val badgeH = textBounds.height() + padV * 2

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

        // Draw vertically centered text
        val textY = margin + padV - textBounds.top
        canvas.drawText(titleText, margin + padH, textY, textPaint)
    }
}
