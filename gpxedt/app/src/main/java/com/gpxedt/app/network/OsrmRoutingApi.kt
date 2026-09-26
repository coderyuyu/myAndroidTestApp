package com.gpxedt.app.network

import com.gpxedt.app.model.RoutingProfile
import com.gpxedt.app.model.TrackPoint
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Locale
import java.util.concurrent.TimeUnit

interface OsrmRoutingApi {

    /**
     * OSRM route service:
     * GET /route/v1/{profile}/{coordinates}?overview=full&geometries=geojson
     * coordinates format: {lon1},{lat1};{lon2},{lat2}
     */
    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Path(value = "coordinates", encoded = true) coordinates: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "geojson",
        @Query("steps") steps: Boolean = false
    ): OsrmRouteResponse

    companion object {
        const val DEFAULT_OSRM_URL = "https://router.project-osrm.org/"

        fun create(baseUrl: String = DEFAULT_OSRM_URL): OsrmRoutingApi {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            val json = Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }

            val contentType = "application/json".toMediaType()

            val retrofit = Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()

            return retrofit.create(OsrmRoutingApi::class.java)
        }
    }
}

class RoutingRepository(
    private var serverUrl: String = OsrmRoutingApi.DEFAULT_OSRM_URL
) {
    private var api: OsrmRoutingApi = OsrmRoutingApi.create(serverUrl)

    fun updateServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() && trimmed != serverUrl) {
            serverUrl = trimmed
            api = OsrmRoutingApi.create(serverUrl)
        }
    }

    fun getServerUrl(): String = serverUrl

    /**
     * Calls OSRM Route API between startPoint and endPoint.
     * Returns a list of TrackPoints representing the newly routed segment.
     */
    suspend fun fetchRouteSegment(
        startPoint: TrackPoint,
        endPoint: TrackPoint,
        profile: RoutingProfile
    ): List<TrackPoint> {
        // OSRM coordinates: lon1,lat1;lon2,lat2
        val coordsStr = String.format(
            Locale.US,
            "%.6f,%.6f;%.6f,%.6f",
            startPoint.lon, startPoint.lat,
            endPoint.lon, endPoint.lat
        )

        val response = api.getRoute(
            profile = profile.apiValue,
            coordinates = coordsStr,
            overview = "full",
            geometries = "geojson"
        )

        if (response.code != "Ok") {
            val msg = response.message ?: response.code
            throw IllegalStateException("OSRM error ($msg)")
        }

        val primaryRoute = response.routes.firstOrNull()
            ?: throw IllegalStateException("No routes returned by OSRM")

        val rawCoordinates = primaryRoute.geometry.coordinates
        if (rawCoordinates.isEmpty()) {
            throw IllegalStateException("Route coordinates are empty")
        }

        // Convert [lon, lat] pairs to TrackPoint
        val routePoints = rawCoordinates.map { coord ->
            val lon = coord[0]
            val lat = coord[1]
            TrackPoint(lat = lat, lon = lon, ele = null, time = null)
        }

        // Interpolate timestamps based on start and end points
        return TimeInterpolator.interpolateRouteTimestamps(startPoint, endPoint, routePoints)
    }
}
