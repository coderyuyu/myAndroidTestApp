package com.dir2gpx.service

import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Parses Google Maps Directions URLs to extract route coordinates and waypoints.
 *
 * Supports multiple URL formats:
 * - Path-based: `google.com/maps/dir/lat1,lon1/lat2,lon2/...` or `google.com/maps/dir/Stop1/Stop2/...`
 * - Place-named paths: `google.com/maps/dir/Taipei/Taichung/Tainan` (via geocoding fallback)
 * - Query-based: `?origin=lat,lon&destination=lat,lon&waypoints=lat,lon|lat,lon`
 * - Data parameter patterns: `!1d<lon>!2d<lat>`, `!2d<lat>!1d<lon>`, `!3d<lat>!4d<lon>`
 * - Viewport filtering to avoid capturing `@lat,lon,zoom` as route points.
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
        val decodedUrl = safeDecodeUrl(url)

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

        // 4. Fallback: try to extract any coordinate pairs from the URL (excluding @viewport)
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
     * Parses coordinates from a Google Maps URL with asynchronous geocoding fallback
     * for URLs that contain place names (e.g. /maps/dir/Taipei/Taichung/Tainan).
     *
     * Ensures all destinations along the route (even > 5 stops) are preserved and resolved in order.
     */
    suspend fun parseWithGeocoding(url: String): List<RoutePoint> = withContext(Dispatchers.IO) {
        val decodedUrl = safeDecodeUrl(url)

        val pathStops = extractRawPathSegments(decodedUrl)
        val queryStops = extractRawQueryStops(decodedUrl)
        val namedStops = when {
            pathStops.size >= 2 -> pathStops
            queryStops.size >= 2 -> queryStops
            else -> emptyList()
        }

        // 1. Try direct coordinate parsing first (handles data= !3d!4d or !1d!2d, path coords, query coords)
        try {
            val directPoints = parse(url)
            if (directPoints.size >= 2) {
                // If names in path/query match the count of parsed coordinates, preserve the exact names!
                if (namedStops.size == directPoints.size) {
                    val namedPoints = directPoints.mapIndexed { i, pt ->
                        pt.copy(name = namedStops[i])
                    }
                    return@withContext classifyPoints(namedPoints)
                }
                // If directPoints has at least as many points as named stops, it captured everything
                if (namedStops.isEmpty() || directPoints.size >= namedStops.size) {
                    return@withContext directPoints
                }
            }
        } catch (_: Exception) {
            // Direct extraction failed, proceed to ordered resolution and geocoding
        }

        // 2. If explicit ordered stops exist from path or query, resolve them in order
        if (namedStops.size >= 2) {
            val resolvedPoints = mutableListOf<RoutePoint>()
            var needsGeocode = false

            for (stop in namedStops) {
                val directCoord = parseCoordinate(stop)
                if (directCoord != null) {
                    resolvedPoints.add(directCoord)
                } else {
                    needsGeocode = true
                    break
                }
            }

            if (!needsGeocode && resolvedPoints.size >= 2) {
                return@withContext classifyPoints(resolvedPoints)
            }

            // Need to geocode place names in the ordered sequence
            val fullyResolved = mutableListOf<RoutePoint>()
            for ((index, stop) in namedStops.withIndex()) {
                val direct = parseCoordinate(stop)
                if (direct != null) {
                    fullyResolved.add(direct)
                } else {
                    if (index > 0) {
                        delay(150) // Be polite to geocoding services between requests
                    }
                    val geocoded = geocodePlace(stop)
                    if (geocoded != null) {
                        fullyResolved.add(geocoded.copy(name = stop))
                    }
                }
            }

            if (fullyResolved.size >= 2) {
                return@withContext classifyPoints(fullyResolved)
            }
        }

        val placeNames = extractPlaceNames(decodedUrl)
        if (placeNames.size < 2) {
            throw IllegalArgumentException(
                "Could not extract origin and destination from URL: $url"
            )
        }

        val geocodedPoints = mutableListOf<RoutePoint>()
        for ((index, name) in placeNames.withIndex()) {
            if (index > 0) {
                delay(150)
            }
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
        val decoded = safeDecodeUrl(url)
        val places = mutableListOf<String>()

        val pathSegments = extractRawPathSegments(decoded)
        for (seg in pathSegments) {
            if (!COORD_PAIR_REGEX.matches(seg)) {
                places.add(seg)
            }
        }

        if (places.size < 2) {
            val queryStops = extractRawQueryStops(decoded)
            for (stop in queryStops) {
                if (!COORD_PAIR_REGEX.matches(stop)) {
                    places.add(stop)
                }
            }
        }

        return places
    }

    /**
     * Extracts raw path segments after `/dir/` without relying on `java.net.URI`,
     * preventing crashes on non-ASCII / Chinese characters.
     */
    private fun extractRawPathSegments(url: String): List<String> {
        val dirIndex = url.indexOf("/dir/")
        if (dirIndex == -1) return emptyList()

        val afterDir = url.substring(dirIndex + 5).substringBefore("?")
        return afterDir.split("/")
            .map { it.trim().replace("+", " ") }
            .filter { seg ->
                seg.isNotEmpty() &&
                    !seg.startsWith("@") &&
                    !seg.startsWith("data=") &&
                    !seg.startsWith("am=") &&
                    !seg.startsWith("entry=")
            }
    }

    /**
     * Extracts raw stops from query parameters (origin, waypoints, destination).
     */
    private fun extractRawQueryStops(url: String): List<String> {
        val queryString = url.substringAfter("?", "")
        if (queryString.isEmpty()) return emptyList()

        val params = queryString.split("&").associate { param ->
            val key = param.substringBefore("=")
            val value = param.substringAfter("=", "").replace("+", " ")
            key to value
        }

        val stops = mutableListOf<String>()
        params["origin"]?.takeIf { it.isNotBlank() }?.let { stops.add(it) }

        // Split waypoints by pipe (| or %7C)
        params["waypoints"]?.split("|", "%7C", "%7c")?.forEach { wp ->
            val clean = wp.removePrefix("via:").trim()
            if (clean.isNotBlank()) {
                stops.add(clean)
            }
        }

        params["destination"]?.takeIf { it.isNotBlank() }?.let { stops.add(it) }
        return stops
    }

    /**
     * Geocodes a place name string using Photon (primary, fast, multi-language)
     * with Nominatim fallback.
     */
    suspend fun geocodePlace(placeName: String): RoutePoint? = withContext(Dispatchers.IO) {
        val trimmed = placeName.trim()
        if (trimmed.isEmpty()) return@withContext null

        // Check if it's already a coordinate
        parseCoordinate(trimmed)?.let { return@withContext it }

        // 1. Try Photon geocoder (OpenStreetMap-based, high performance, lenient rate limits)
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val photonUrl = "https://photon.komoot.io/api/?q=$encodedQuery&limit=1"

            val request = Request.Builder()
                .url(photonUrl)
                .header("User-Agent", "Dir2GPX-Android/1.0 (https://github.com/dir2gpx)")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val root = JSONObject(body)
                        val features = root.optJSONArray("features")
                        if (features != null && features.length() > 0) {
                            val first = features.getJSONObject(0)
                            val geometry = first.optJSONObject("geometry")
                            val coordinates = geometry?.optJSONArray("coordinates")
                            if (coordinates != null && coordinates.length() >= 2) {
                                val lon = coordinates.getDouble(0)
                                val lat = coordinates.getDouble(1)
                                val props = first.optJSONObject("properties")
                                val name = props?.optString("name")?.ifEmpty { trimmed } ?: trimmed

                                return@withContext RoutePoint(
                                    latitude = lat,
                                    longitude = lon,
                                    name = name
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to Nominatim
        }

        // 2. Fallback to OpenStreetMap Nominatim
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val nominatimUrl = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1"

            val request = Request.Builder()
                .url(nominatimUrl)
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
        val segments = extractRawPathSegments(url)
        return segments.mapNotNull { segment ->
            parseCoordinate(segment)
        }
    }

    private fun parseQueryParameters(url: String): List<RoutePoint> {
        val stops = extractRawQueryStops(url)
        val points = stops.mapNotNull { parseCoordinate(it) }
        return if (points.size >= 2) points else emptyList()
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
        // Remove viewport @lat,lon,zoom before searching for coordinates
        val cleanUrl = VIEWPORT_REGEX.replace(url, "")
        val points = mutableListOf<RoutePoint>()

        LABELED_COORD_REGEX.findAll(cleanUrl).forEach { match ->
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

    private fun safeDecodeUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8")
        } catch (_: Exception) {
            url
        }
    }
}
