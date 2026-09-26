package com.dir2gpx.service

import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Result data from an OSRM routing request.
 */
data class OsrmRouteResult(
    val trackPoints: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/**
 * Service to calculate real road network routes between waypoints using the
 * Open Source Routing Machine (OSRM) public routing API:
 * https://map.project-osrm.org/
 *
 * Endpoint format:
 * `https://router.project-osrm.org/route/v1/driving/{lon1},{lat1};{lon2},{lat2}?overview=full&geometries=polyline`
 *
 * Note: OSRM expects coordinates in `{longitude},{latitude}` order separated by commas,
 * and different waypoints separated by semicolons.
 */
object OsrmRoutingService {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"
    private const val USER_AGENT = "Dir2GPX-Android/1.0 (https://github.com/dir2gpx)"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Builds the OSRM route URL for the given list of waypoints.
     */
    fun buildUrl(waypoints: List<RoutePoint>): String {
        require(waypoints.size >= 2) { "At least 2 waypoints are required for routing" }

        val coordString = waypoints.joinToString(";") { pt ->
            String.format(Locale.US, "%.6f,%.6f", pt.longitude, pt.latitude)
        }

        return "$BASE_URL/$coordString?overview=full&geometries=polyline&steps=true"
    }

    /**
     * Parses the OSRM JSON response string into an [OsrmRouteResult].
     */
    fun parseResponse(jsonString: String): OsrmRouteResult {
        val root = JSONObject(jsonString)
        val code = root.optString("code", "")
        if (code != "Ok") {
            val message = root.optString("message", "Unknown OSRM error")
            throw IOException("OSRM routing returned code: $code ($message)")
        }

        val routesArray = root.optJSONArray("routes")
            ?: throw IOException("OSRM response missing 'routes' array")

        if (routesArray.length() == 0) {
            throw IOException("No route found by OSRM")
        }

        val primaryRoute = routesArray.getJSONObject(0)
        val geometry = primaryRoute.optString("geometry", "")
        if (geometry.isEmpty()) {
            throw IOException("OSRM primary route contains empty geometry")
        }

        val distanceMeters = primaryRoute.optDouble("distance", 0.0)
        val durationSeconds = primaryRoute.optDouble("duration", 0.0)

        // Decode the encoded polyline (standard 1e-5 precision Google polyline)
        val rawTrackPoints = PolylineDecoder.decode(geometry)
        val trackPoints = rawTrackPoints.map { pt ->
            pt.copy(type = PointType.TRACK)
        }

        return OsrmRouteResult(
            trackPoints = trackPoints,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds
        )
    }

    /**
     * Fetches a road route connecting all waypoints in sequential order.
     *
     * @param waypoints Ordered list of waypoints.
     * @return [Result] containing [OsrmRouteResult] on success, or exception on failure.
     */
    suspend fun fetchRoute(waypoints: List<RoutePoint>): Result<OsrmRouteResult> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) {
            return@withContext Result.failure(IllegalArgumentException("Need at least 2 waypoints for routing"))
        }

        try {
            val url = buildUrl(waypoints)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("OSRM HTTP error: ${response.code} ${response.message}")
                    )
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response body from OSRM"))

                val result = parseResponse(body)
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
