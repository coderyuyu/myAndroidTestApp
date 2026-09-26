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
        val panOffsetY: Float = 0f
    )

    private class EncodingSession {
        var videoTrackIndex: Int = -1
        var muxerStarted: Boolean = false
    }

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
            val targetZoom = mapRenderer.calculateOptimalZoom(track, config.width.toFloat(), config.height.toFloat()) + 0.8 + config.zoomOffset

            // 1. Preload all visible map tiles along the route so off-screen encoding runs with 100% cache hits
            mapRenderer.preloadTilesForTrack(
                track = track,
                zoom = targetZoom,
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

            // 3. Render loop: Frame by frame with deterministic PTS timestamps
            for (frameIndex in 0 until totalFrames) {
                if (!coroutineContext.isActive) {
                    throw InterruptedException("Video export was cancelled by user")
                }

                val progress = if (totalFrames > 1) {
                    frameIndex.toFloat() / (totalFrames - 1).toFloat()
                } else 0f

                val interpolated = track.interpolate(progress)

                // Render Map Layer with user-selected rotation, zoom, mapStyle, and North compass rose
                mapRenderer.renderMap(
                    canvas = canvas,
                    width = config.width.toFloat(),
                    height = config.height.toFloat(),
                    track = track,
                    currentProgress = progress,
                    interpolatedPoint = interpolated,
                    autoFollowMarker = true,
                    customZoom = targetZoom,
                    rotationDegrees = config.rotationDegrees,
                    mapStyle = config.mapStyle,
                    drawCompassOverlay = true,
                    panOffsetX = config.panOffsetX,
                    panOffsetY = config.panOffsetY,
                    showWaypointLabels = config.showWaypointLabels
                )

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
}
