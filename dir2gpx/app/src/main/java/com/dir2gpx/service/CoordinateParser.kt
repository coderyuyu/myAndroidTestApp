package com.dir2gpx.service

import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Parses Google Maps Directions URLs to extract route coordinates.
 *
 * Supports multiple URL formats:
 * - Path-based: `google.com/maps/dir/lat1,lon1/lat2,lon2/...`
 * - Place-named paths: `google.com/maps/dir/Berlin/Munich` (via geocoding fallback)
 * - Query-based: `?origin=lat,lon&destination=lat,lon&waypoints=lat,lon|lat,lon`
 * - Data parameter patterns: `!1d<lon>!2d<lat>`, `!2d<lat>!1d<lon>`, `!3d<lat>!4d<lon>`
 * - `@lat,lon,zoom` viewport markers
 */
object CoordinateParser {

    private val COORD_PAIR_REGEX = Regex("""^(-?\d+\.?\d*),\s*(-?\d+\.?\d*)$""")
    private val LABELED_COORD_REGEX = Regex("""(-?\d+\.?\d+),\s*(-?\d+\.?\d+)""")
    private val VIEWPORT_REGEX = Regex("""@(-?\d+\.?\d+),(-?\d+\.?\d+)""")

    // Matches !1d<lon>!2d<lat> or !2d<lat>!1d<lon> or !3d<lat>!4d<lon>
    private val DATA_1D_2D_REGEX = Regex("""!1d(-?\d+\.?\d+)!2d(-?\d+\.?\d+)""")
    private val DATA_2D_1D_REGEX = Regex("""!2d(-?\d+\.?\d+)!1d(-?\d+\.?\d+)""")
    private val DATA_3D_4D_REGEX = Regex("""!3d(-?\d+\.?\d+)!4d(-?\d+\.?\d+)""")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Synchronously parses explicit coordinates from a Google Maps URL.
     *
     * @param url The Google Maps URL.
     * @return A list of at least 2 [RoutePoint]s.
     * @throws IllegalArgumentException if fewer than 2 coordinates could be found.
     */
    fun parse(url: String): List<RoutePoint> {
        val decodedUrl = URLDecoder.decode(url, "UTF-8")

        // 1. Try path-based parsing first (/maps/dir/lat1,lon1/lat2,lon2)
        val pathPoints = parsePathSegments(decodedUrl)
        if (pathPoints.size >= 2) {
            return classifyPoints(pathPoints)
        }

        // 2. Try query parameter parsing (?origin=...&destination=...)
        val queryPoints = parseQueryParameters(decodedUrl)
        if (queryPoints.size >= 2) {
            return classifyPoints(queryPoints)
        }

        // 3. Try extracting from data parameters (!1d!2d or !3d!4d)
        val dataPoints = parseDataParameter(decodedUrl)
        if (dataPoints.size >= 2) {
            return classifyPoints(dataPoints)
        }

        // 4. Fallback: try to extract any coordinate pairs from the entire URL
        val fallbackPoints = extractAllCoordinates(decodedUrl)
        if (fallbackPoints.size >= 2) {
            return classifyPoints(fallbackPoints)
        }

        throw IllegalArgumentException(
            "Could not extract at least 2 coordinate points from URL. " +
                "Please provide a Google Maps Directions URL with coordinates " +
                "(e.g., google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863)."
        )
    }

    /**
     * Parses coordinates from a Google Maps URL with asynchronous OpenStreetMap
     * Nominatim geocoding fallback for URLs that contain place names (e.g. /maps/dir/Paris/Lyon).
     */
    suspend fun parseWithGeocoding(url: String): List<RoutePoint> = withContext(Dispatchers.IO) {
        // Try direct coordinate extraction first
        try {
            val directPoints = parse(url)
            if (directPoints.size >= 2) {
                return@withContext directPoints
            }
        } catch (_: Exception) {
            // Direct parsing didn't find enough points, proceed to named place extraction & geocoding
        }

        val decodedUrl = URLDecoder.decode(url, "UTF-8")
        val placeNames = extractPlaceNames(decodedUrl)

        if (placeNames.size < 2) {
            throw IllegalArgumentException(
                "Could not extract origin and destination from URL: $url"
            )
        }

        val geocodedPoints = mutableListOf<RoutePoint>()
        for (name in placeNames) {
            val point = geocodePlace(name)
            if (point != null) {
                geocodedPoints.add(point)
            }
        }

        if (geocodedPoints.size < 2) {
            throw IllegalArgumentException(
                "Failed to geocode route locations from: ${placeNames.joinToString(" → ")}"
            )
        }

        classifyPoints(geocodedPoints)
    }

    /**
     * Extracts named place strings from path segments or query parameters.
     */
    fun extractPlaceNames(url: String): List<String> {
        val uri = try {
            URI(url.replace(" ", "%20"))
        } catch (e: Exception) {
            null
        }

        val places = mutableListOf<String>()

        // 1. Path segments after /dir/
        val path = uri?.path ?: ""
        val dirIndex = path.indexOf("/dir/")
        if (dirIndex != -1) {
            val segments = path.substring(dirIndex + 5).split("/")
                .map { it.trim().replace("+", " ") }
                .filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") }

            for (seg in segments) {
                if (COORD_PAIR_REGEX.matches(seg)) {
                    continue // Already handled by coordinate parser
                }
                places.add(seg)
            }
        }

        // 2. Query parameters (origin, destination, waypoints)
        if (places.size < 2) {
            val query = url.substringAfter("?", "")
            val params = query.split("&").associate {
                val key = it.substringBefore("=")
                val value = it.substringAfter("=", "").replace("+", " ")
                key to value
            }

            params["origin"]?.takeIf { it.isNotBlank() }?.let { places.add(it) }
            params["waypoints"]?.split("|")?.forEach { wp ->
                val clean = wp.removePrefix("via:").trim()
                if (clean.isNotBlank()) places.add(clean)
            }
            params["destination"]?.takeIf { it.isNotBlank() }?.let { places.add(it) }
        }

        return places
    }

    /**
     * Geocodes a place name string using OpenStreetMap's Nominatim search API.
     */
    suspend fun geocodePlace(placeName: String): RoutePoint? = withContext(Dispatchers.IO) {
        val trimmed = placeName.trim()
        if (trimmed.isEmpty()) return@withContext null

        // Check if it's already a coordinate
        COORD_PAIR_REGEX.find(trimmed)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null) {
                return@withContext RoutePoint(latitude = lat, longitude = lon, name = trimmed)
            }
        }

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Dir2GPX-Android/1.0 (https://github.com/dir2gpx)")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null

                val array = JSONArray(body)
                if (array.length() == 0) return@withContext null

                val first = array.getJSONObject(0)
                val lat = first.optString("lat").toDoubleOrNull() ?: return@withContext null
                val lon = first.optString("lon").toDoubleOrNull() ?: return@withContext null
                val displayName = first.optString("name").ifEmpty { trimmed }

                RoutePoint(
                    latitude = lat,
                    longitude = lon,
                    name = displayName
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePathSegments(url: String): List<RoutePoint> {
        val uri = try {
            URI(url.replace(" ", "%20"))
        } catch (e: Exception) {
            return emptyList()
        }

        val path = uri.path ?: return emptyList()
        val dirIndex = path.indexOf("/dir/")
        if (dirIndex == -1) return emptyList()

        val afterDir = path.substring(dirIndex + 5)
        val segments = afterDir.split("/")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("data=") }

        return segments.mapNotNull { segment ->
            parseCoordinate(segment)
        }
    }

    private fun parseQueryParameters(url: String): List<RoutePoint> {
        val queryString = url.substringAfter("?", "")
        if (queryString.isEmpty()) return emptyList()

        val params = queryString.split("&").associate { param ->
            val (key, value) = if (param.contains("=")) {
                param.substringBefore("=") to param.substringAfter("=")
            } else {
                param to ""
            }
            key to value
        }

        val points = mutableListOf<RoutePoint>()
        params["origin"]?.let { parseCoordinate(it) }?.also { points.add(it) }
        params["waypoints"]?.split("|")?.forEach { wp ->
            parseCoordinate(wp.removePrefix("via:"))?.also { points.add(it) }
        }
        params["destination"]?.let { parseCoordinate(it) }?.also { points.add(it) }

        return points
    }

    private fun parseDataParameter(url: String): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()

        // 1. !1d<lon>!2d<lat>
        DATA_1D_2D_REGEX.findAll(url).forEach { match ->
            val lon = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lat = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(RoutePoint(latitude = lat, longitude = lon))
            }
        }
        if (points.size >= 2) return points

        // 2. !2d<lat>!1d<lon>
        DATA_2D_1D_REGEX.findAll(url).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lon = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(RoutePoint(latitude = lat, longitude = lon))
            }
        }
        if (points.size >= 2) return points

        // 3. !3d<lat>!4d<lon>
        DATA_3D_4D_REGEX.findAll(url).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lon = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                points.add(RoutePoint(latitude = lat, longitude = lon))
            }
        }

        return points
    }

    private fun extractAllCoordinates(url: String): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()

        LABELED_COORD_REGEX.findAll(url).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lon = match.groupValues[2].toDoubleOrNull() ?: return@forEach

            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                val point = RoutePoint(latitude = lat, longitude = lon)
                if (points.none { existing ->
                        kotlin.math.abs(existing.latitude - point.latitude) < 0.0001 &&
                            kotlin.math.abs(existing.longitude - point.longitude) < 0.0001
                    }) {
                    points.add(point)
                }
            }
        }

        return points
    }

    private fun parseCoordinate(input: String): RoutePoint? {
        val trimmed = input.trim()

        COORD_PAIR_REGEX.find(trimmed)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                return RoutePoint(latitude = lat, longitude = lon)
            }
        }

        LABELED_COORD_REGEX.find(trimmed)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                val name = trimmed.substringBefore(match.value).trimEnd(',', ' ', '+')
                    .replace('+', ' ')
                return RoutePoint(latitude = lat, longitude = lon, name = name)
            }
        }

        return null
    }

    private fun classifyPoints(rawPoints: List<RoutePoint>): List<RoutePoint> {
        if (rawPoints.isEmpty()) return emptyList()
        if (rawPoints.size == 1) {
            return listOf(rawPoints[0].copy(name = "Waypoint", type = PointType.ORIGIN))
        }

        return rawPoints.mapIndexed { index, point ->
            when (index) {
                0 -> point.copy(
                    name = point.name.ifEmpty { "Start" },
                    type = PointType.ORIGIN
                )
                rawPoints.lastIndex -> point.copy(
                    name = point.name.ifEmpty { "Destination" },
                    type = PointType.DESTINATION
                )
                else -> point.copy(
                    name = point.name.ifEmpty { "Via $index" },
                    type = PointType.VIA
                )
            }
        }
    }
}
