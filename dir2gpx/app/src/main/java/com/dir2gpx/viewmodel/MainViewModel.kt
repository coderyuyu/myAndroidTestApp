package com.dir2gpx.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dir2gpx.model.GpxData
import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import com.dir2gpx.model.UiState
import com.dir2gpx.service.CoordinateParser
import com.dir2gpx.service.GpxSerializer
import com.dir2gpx.service.HaversineCalculator
import com.dir2gpx.service.UrlExpanderService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.time.Duration
import java.time.Instant

/**
 * Main ViewModel orchestrating the URL → GPX conversion pipeline.
 *
 * Pipeline stages:
 * 1. Expand shortened URL (if needed)
 * 2. Parse coordinates from canonical URL
 * 3. Generate intermediate track points (straight-line between waypoints)
 * 4. Calculate Haversine distances
 * 5. Interpolate timestamps
 * 6. Serialize to GPX 1.1 XML
 * 7. Emit [UiState.Success] with [GpxData]
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Converts a Google Maps Directions URL into GPX data.
     *
     * @param url The Google Maps URL (short or canonical).
     * @param startTime Route departure time.
     * @param endTime Route arrival time.
     */
    fun convertUrl(url: String, startTime: Instant, endTime: Instant) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading

            try {
                // Stage 1: Expand shortened URL
                val canonicalUrl = if (isShortUrl(url)) {
                    UrlExpanderService.expand(url)
                } else {
                    url.trim()
                }

                // Stage 2: Parse waypoints from URL (with geocoding fallback)
                val parsedWaypoints = CoordinateParser.parseWithGeocoding(canonicalUrl)

                if (parsedWaypoints.size < 2) {
                    _uiState.value = UiState.Error(
                        "Need at least 2 coordinate points. Found: ${parsedWaypoints.size}. " +
                            "Please provide a valid Google Maps Directions URL."
                    )
                    return@launch
                }

                // Stage 3: Query OSRM routing API to find real road route between waypoints
                val osrmResult = com.dir2gpx.service.OsrmRoutingService.fetchRoute(parsedWaypoints).getOrNull()

                val (trackPoints, totalDistance) = if (osrmResult != null && osrmResult.trackPoints.isNotEmpty()) {
                    val distKm = if (osrmResult.distanceMeters > 0) {
                        osrmResult.distanceMeters / 1000.0
                    } else {
                        HaversineCalculator.totalDistanceKm(osrmResult.trackPoints)
                    }
                    Pair(osrmResult.trackPoints, distKm)
                } else {
                    // Fallback to dense interpolation if OSRM is unreachable
                    val fallbackPoints = generateTrackPoints(parsedWaypoints)
                    val fallbackDist = HaversineCalculator.totalDistanceKm(fallbackPoints)
                    Pair(fallbackPoints, fallbackDist)
                }

                // Stage 4: Interpolate timestamps for track points
                val timedTrackPoints = HaversineCalculator.interpolateTimestamps(
                    trackPoints, startTime, endTime
                )

                // Also interpolate timestamps for waypoints
                val timedWaypoints = interpolateWaypointTimestamps(
                    parsedWaypoints, timedTrackPoints, startTime, endTime
                )

                // Stage 6: Generate route name
                val routeName = buildRouteName(timedWaypoints)

                // Stage 7: Serialize to GPX XML
                val gpxXml = GpxSerializer.serialize(
                    waypoints = timedWaypoints,
                    trackPoints = timedTrackPoints,
                    routeName = routeName
                )

                // Stage 8: Emit success
                val duration = Duration.between(startTime, endTime)
                _uiState.value = UiState.Success(
                    GpxData(
                        waypoints = timedWaypoints,
                        trackPoints = timedTrackPoints,
                        name = routeName,
                        totalDistanceKm = totalDistance,
                        duration = duration,
                        gpxXml = gpxXml
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    e.message ?: "Unknown error occurred during conversion"
                )
            }
        }
    }

    /**
     * Writes the GPX XML to a SAF-provided output URI.
     */
    fun exportGpx(context: Context, outputUri: Uri) {
        val gpxData = (uiState.value as? UiState.Success)?.gpxData ?: return

        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(outputUri)?.use { stream: OutputStream ->
                    stream.write(gpxData.gpxXml.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to export GPX: ${e.message}")
            }
        }
    }

    /**
     * Shares the GPX file via Android share sheet using a FileProvider URI.
     */
    fun shareGpx(context: Context) {
        val gpxData = (uiState.value as? UiState.Success)?.gpxData ?: return

        try {
            // Write to cache directory for sharing
            val cacheDir = File(context.cacheDir, "gpx_share")
            cacheDir.mkdirs()
            val timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now())
            val gpxFile = File(cacheDir, "$timestamp.gpx")
            gpxFile.writeText(gpxData.gpxXml, Charsets.UTF_8)

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                gpxFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, gpxData.name)
                putExtra(Intent.EXTRA_TEXT, "GPX route: ${gpxData.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share GPX file")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            _uiState.value = UiState.Error("Failed to share GPX: ${e.message}")
        }
    }

    /**
     * Resets the UI state back to Idle and clears any data.
     */
    fun clearState() {
        _uiState.value = UiState.Idle
    }

    /**
     * Generates intermediate track points along straight-line segments between waypoints.
     * Adds density points every ~500m for smoother track visualization.
     */
    private fun generateTrackPoints(waypoints: List<RoutePoint>): List<RoutePoint> {
        if (waypoints.size < 2) return waypoints.map { it.copy(type = PointType.TRACK) }

        val points = mutableListOf<RoutePoint>()

        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]

            val segmentDistKm = HaversineCalculator.distanceKm(
                from.latitude, from.longitude,
                to.latitude, to.longitude
            )

            // Add the starting point of this segment
            points.add(from.copy(type = PointType.TRACK))

            // Add intermediate points every ~500m for smoother rendering
            val intervalKm = 0.5
            if (segmentDistKm > intervalKm) {
                val numIntermediate = (segmentDistKm / intervalKm).toInt()
                for (j in 1 until numIntermediate) {
                    val fraction = j.toDouble() / numIntermediate
                    val lat = from.latitude + (to.latitude - from.latitude) * fraction
                    val lon = from.longitude + (to.longitude - from.longitude) * fraction
                    points.add(
                        RoutePoint(
                            latitude = lat,
                            longitude = lon,
                            type = PointType.TRACK
                        )
                    )
                }
            }
        }

        // Add the final destination point
        points.add(waypoints.last().copy(type = PointType.TRACK))

        return points
    }

    /**
     * Assigns timestamps to waypoints based on their position along the track.
     */
    private fun interpolateWaypointTimestamps(
        waypoints: List<RoutePoint>,
        timedTrackPoints: List<RoutePoint>,
        startTime: Instant,
        endTime: Instant
    ): List<RoutePoint> {
        if (timedTrackPoints.isEmpty() || waypoints.isEmpty()) {
            return HaversineCalculator.interpolateTimestamps(waypoints, startTime, endTime)
        }

        var lastTrackIndex = 0
        return waypoints.mapIndexed { index, waypoint ->
            when (index) {
                0 -> waypoint.copy(timestamp = startTime)
                waypoints.lastIndex -> waypoint.copy(timestamp = endTime)
                else -> {
                    var bestIdx = lastTrackIndex
                    var bestDist = Double.MAX_VALUE
                    for (i in lastTrackIndex until timedTrackPoints.size) {
                        val tp = timedTrackPoints[i]
                        val dist = HaversineCalculator.distanceKm(
                            waypoint.latitude, waypoint.longitude,
                            tp.latitude, tp.longitude
                        )
                        if (dist < bestDist) {
                            bestDist = dist
                            bestIdx = i
                        }
                    }
                    lastTrackIndex = bestIdx
                    val matchedTime = timedTrackPoints[bestIdx].timestamp ?: startTime
                    waypoint.copy(timestamp = matchedTime)
                }
            }
        }
    }

    /**
     * Builds a human-readable route name from waypoint names.
     */
    private fun buildRouteName(waypoints: List<RoutePoint>): String {
        val origin = waypoints.firstOrNull()?.name ?: "Origin"
        val destination = waypoints.lastOrNull()?.name ?: "Destination"
        return "$origin → $destination"
    }

    /**
     * Checks if a URL is a shortened Google Maps link.
     */
    private fun isShortUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("goo.gl") ||
            lower.contains("maps.app") ||
            lower.contains("g.co") ||
            lower.contains("bit.ly") ||
            (!lower.contains("google.com/maps/dir") && lower.length < 80)
    }

    /**
     * Sanitizes a string for use as a filename.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(64)
            .ifEmpty { "route" }
    }
}
