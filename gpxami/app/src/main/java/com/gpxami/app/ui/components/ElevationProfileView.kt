package com.gpxami.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.ui.theme.*
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * High-performance Jetpack Compose Canvas component for rendering the synchronized
 * elevation profile overlay strictly in the bottom 1/5 of the 16:9 view container.
 */
@Composable
fun ElevationProfileView(
    track: GpxTrack,
    currentProgress: Float,
    interpolatedPoint: InterpolatedPoint?,
    modifier: Modifier = Modifier,
    onScrub: ((Float) -> Unit)? = null
) {
    // Pulse animation for the glowing cursor dot
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xCC0B1120), // 80% opacity deep navy/slate
                        Color(0xFA020617)  // 98% opacity black
                    )
                )
            )
    ) {
        // Main Elevation Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(track.points.size) {
                    if (onScrub != null) {
                        detectTapGestures { offset ->
                            val padStart = with(density) { 56.dp.toPx() }
                            val padEnd = with(density) { 16.dp.toPx() }
                            val chartW = size.width - padStart - padEnd
                            if (chartW > 0) {
                                val prog = ((offset.x - padStart) / chartW).coerceIn(0f, 1f)
                                onScrub(prog)
                            }
                        }
                    }
                }
                .pointerInput(track.points.size) {
                    if (onScrub != null) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val padStart = with(density) { 56.dp.toPx() }
                            val padEnd = with(density) { 16.dp.toPx() }
                            val chartW = size.width - padStart - padEnd
                            if (chartW > 0) {
                                val prog = ((change.position.x - padStart) / chartW).coerceIn(0f, 1f)
                                onScrub(prog)
                            }
                        }
                    }
                }
        ) {
            if (track.points.isEmpty()) return@Canvas

            val padStart = 56.dp.toPx()
            val padEnd = 16.dp.toPx()
            val padTop = 16.dp.toPx()
            val padBottom = 22.dp.toPx()

            val chartWidth = size.width - padStart - padEnd
            val chartHeight = size.height - padTop - padBottom
            if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

            // Calculate elevation range with a 5% margin
            val minEle = track.minElevation
            val maxEle = max(minEle + 10.0, track.maxElevation)
            val eleRange = maxEle - minEle
            val displayMinEle = (minEle - eleRange * 0.05).coerceAtLeast(0.0)
            val displayMaxEle = maxEle + eleRange * 0.05
            val displayEleRange = max(1.0, displayMaxEle - displayMinEle)

            val totalDistMeters = max(1.0, track.totalDistanceMeters)

            // Coordinate mapping functions
            fun getXForDistance(distMeters: Double): Float {
                return (padStart + (distMeters / totalDistMeters) * chartWidth).toFloat()
            }

            fun getYForElevation(ele: Double): Float {
                val ratio = ((ele - displayMinEle) / displayEleRange).coerceIn(0.0, 1.0)
                return (padTop + chartHeight * (1.0 - ratio)).toFloat()
            }

            // 1. Draw top border glowing accent
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0x0038BDF8),
                        Color(0x8038BDF8),
                        Color(0xFF00F2FE),
                        Color(0x8038BDF8),
                        Color(0x0038BDF8)
                    )
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.5.dp.toPx()
            )

            // 2. Draw Horizontal Elevation Grid Lines & Text Labels
            val gridPaint = Paint().apply {
                color = android.graphics.Color.argb(160, 148, 163, 184) // Slate 400
                textSize = 9.sp.toPx()
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                isAntiAlias = true
            }

            val gridSteps = 3
            for (g in 0 until gridSteps) {
                val ratio = g.toFloat() / (gridSteps - 1)
                val eleVal = displayMinEle + (displayMaxEle - displayMinEle) * (1.0 - ratio)
                val y = padTop + chartHeight * ratio

                // Grid dashed line
                drawLine(
                    color = Color(0x2694A3B8),
                    start = Offset(padStart, y),
                    end = Offset(size.width - padEnd, y),
                    strokeWidth = 1.dp.toPx()
                )

                // Label
                drawIntoCanvas { canvas ->
                    val text = "${eleVal.roundToInt()}m"
                    canvas.nativeCanvas.drawText(text, 6.dp.toPx(), y + 4.dp.toPx(), gridPaint)
                }
            }

            // 3. Build SVG Paths for the Elevation Curve
            val fullCurvePath = Path()
            val traversedAreaPath = Path()
            val upcomingAreaPath = Path()

            val clampedProgress = currentProgress.coerceIn(0f, 1f)
            val cursorX = padStart + chartWidth * clampedProgress
            val bottomY = padTop + chartHeight

            val firstP = track.points.first()
            val firstX = getXForDistance(firstP.cumulativeDistanceMeters)
            val firstY = getYForElevation(firstP.elevation)

            fullCurvePath.moveTo(firstX, firstY)

            for (i in 1 until track.points.size) {
                val p = track.points[i]
                val px = getXForDistance(p.cumulativeDistanceMeters)
                val py = getYForElevation(p.elevation)
                fullCurvePath.lineTo(px, py)
            }

            // 4. Fill Gradient Area Under Curve
            // Traversed Area Path
            traversedAreaPath.moveTo(firstX, bottomY)
            traversedAreaPath.lineTo(firstX, firstY)

            var splitElevation = firstP.elevation
            for (p in track.points) {
                val px = getXForDistance(p.cumulativeDistanceMeters)
                val py = getYForElevation(p.elevation)
                if (px <= cursorX) {
                    traversedAreaPath.lineTo(px, py)
                    splitElevation = p.elevation
                } else {
                    break
                }
            }

            // Exact interpolated Y at current cursor
            val currentEle = interpolatedPoint?.elevation ?: splitElevation
            val cursorY = getYForElevation(currentEle)

            traversedAreaPath.lineTo(cursorX, cursorY)
            traversedAreaPath.lineTo(cursorX, bottomY)
            traversedAreaPath.close()

            // Draw Traversed Filled Area (Vibrant Cyan / Emerald)
            drawPath(
                path = traversedAreaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x6600F2FE), // Neon cyan 40%
                        Color(0x3306B6D4), // Cyan 20%
                        Color(0x0510B981)  // Emerald 2%
                    ),
                    startY = padTop,
                    endY = bottomY
                )
            )

            // Upcoming Area Path
            upcomingAreaPath.moveTo(cursorX, bottomY)
            upcomingAreaPath.lineTo(cursorX, cursorY)
            var reachedCursor = false
            for (p in track.points) {
                val px = getXForDistance(p.cumulativeDistanceMeters)
                val py = getYForElevation(p.elevation)
                if (px > cursorX) {
                    upcomingAreaPath.lineTo(px, py)
                    reachedCursor = true
                }
            }
            val lastP = track.points.last()
            val lastX = getXForDistance(lastP.cumulativeDistanceMeters)
            upcomingAreaPath.lineTo(lastX, bottomY)
            upcomingAreaPath.close()

            // Draw Upcoming Filled Area (Muted Slate / Gray)
            drawPath(
                path = upcomingAreaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x26475569), // 15% slate
                        Color(0x05334155)  // 2% slate
                    ),
                    startY = padTop,
                    endY = bottomY
                )
            )

            // 5. Draw Full Stroke Lines
            // Upcoming muted line
            drawPath(
                path = fullCurvePath,
                color = Color(0x6664748B),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // Traversed highlighted glowing line
            val traversedStrokePath = Path()
            traversedStrokePath.moveTo(firstX, firstY)
            for (p in track.points) {
                val px = getXForDistance(p.cumulativeDistanceMeters)
                val py = getYForElevation(p.elevation)
                if (px <= cursorX) {
                    traversedStrokePath.lineTo(px, py)
                } else {
                    break
                }
            }
            traversedStrokePath.lineTo(cursorX, cursorY)

            // Glow pass
            drawPath(
                path = traversedStrokePath,
                color = Color(0x5500F2FE),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // Crisp foreground pass
            drawPath(
                path = traversedStrokePath,
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF06B6D4), Color(0xFF00F2FE))
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 6. X-axis Distance Markers
            val distPaint = Paint().apply {
                color = android.graphics.Color.argb(140, 148, 163, 184)
                textSize = 9.sp.toPx()
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                isAntiAlias = true
            }

            val totalKm = track.totalDistanceKm
            val xTickSteps = 4
            for (k in 0..xTickSteps) {
                val frac = k.toFloat() / xTickSteps
                val kmVal = totalKm * frac
                val x = padStart + chartWidth * frac
                drawIntoCanvas { canvas ->
                    val text = String.format(Locale.US, "%.1f km", kmVal)
                    val textW = distPaint.measureText(text)
                    val drawX = (x - textW / 2).coerceIn(padStart, size.width - padEnd - textW)
                    canvas.nativeCanvas.drawText(text, drawX, size.height - 6.dp.toPx(), distPaint)
                }
            }

            // 6.5 Draw Static Green Waypoint Dots on Elevation Profile
            for (wpt in track.waypoints) {
                val closestPt = track.points.minByOrNull { pt ->
                    val dLat = pt.lat - wpt.lat
                    val dLon = pt.lon - wpt.lon
                    dLat * dLat + dLon * dLon
                } ?: continue

                val wx = getXForDistance(closestPt.cumulativeDistanceMeters)
                val wy = getYForElevation(closestPt.elevation)

                // Soft glowing green outer halo
                drawCircle(
                    color = Color(0x6610B981),
                    radius = 3.5.dp.toPx(),
                    center = Offset(wx, wy)
                )
                // White outer ring
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = Offset(wx, wy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8.dp.toPx())
                )
                // Vibrant green center dot
                drawCircle(
                    color = Color(0xFF10B981),
                    radius = 1.8.dp.toPx(),
                    center = Offset(wx, wy)
                )
                // Inner white center accent
                drawCircle(
                    color = Color.White,
                    radius = 0.8.dp.toPx(),
                    center = Offset(wx, wy)
                )
            }

            // 7. Synchronized Real-Time Indicator (Vertical Cursor + Glowing Marker Dot)
            // Vertical cursor line
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x0000F2FE), Color(0xFF00F2FE), Color(0x3300F2FE)),
                    startY = padTop,
                    endY = bottomY
                ),
                start = Offset(cursorX, padTop),
                end = Offset(cursorX, bottomY),
                strokeWidth = 1.5.dp.toPx()
            )

            // Outer pulse halo around marker dot
            drawCircle(
                color = Color(0x4400F2FE),
                radius = 7.dp.toPx() * pulseScale,
                center = Offset(cursorX, cursorY)
            )

            // Medium ring
            drawCircle(
                color = Color(0xFF06B6D4),
                radius = 4.5.dp.toPx(),
                center = Offset(cursorX, cursorY)
            )

            // Center solid bright white/cyan core
            drawCircle(
                color = Color.White,
                radius = 2.2.dp.toPx(),
                center = Offset(cursorX, cursorY)
            )
        }

        // 8. Compact Real-Time Telemetry HUD (Floating glass chip)
        interpolatedPoint?.let { pt ->
            TelemetryHud(
                point = pt,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 6.dp)
            )
        }
    }
}

/**
 * Modern floating telemetry badge displaying current altitude, distance, speed, and slope.
 */
@Composable
private fun TelemetryHud(
    point: InterpolatedPoint,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = Color(0xB30F172A),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Elevation
        Text(
            text = String.format(Locale.US, "▲ %d m", point.elevation.roundToInt()),
            color = CyanNeon,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        // Cumulative Distance
        Text(
            text = String.format(Locale.US, "%.1f km", point.cumulativeDistanceKm),
            color = TextPrimary,
            fontSize = 11.sp
        )

        // Speed
        Text(
            text = String.format(Locale.US, "%.1f km/h", point.speedKmh),
            color = EmeraldAccent,
            fontSize = 11.sp
        )

        // Gradient / Slope
        val sign = if (point.gradientPercent >= 0) "+" else ""
        Text(
            text = String.format(Locale.US, "%s%.1f%%", sign, point.gradientPercent),
            color = if (point.gradientPercent > 6.0) AmberAccent else TextSecondary,
            fontSize = 10.sp
        )
    }
}
