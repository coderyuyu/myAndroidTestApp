package com.gpxami.app.data.geocoding

import android.content.Context
import android.location.Address
import com.gpxami.app.data.geocoder.GlobalAdminResolver
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.repository.AdminDivisionResult
import com.gpxami.app.data.repository.GeocodingRepository
import com.gpxami.app.data.repository.GeocodingRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Resolves Administrative Division names (Level 1 and Level 2) for coordinates
 * backed by [GlobalAdminResolver] and [GeocodingRepository].
 *
 * Ensures:
 * 1. Zero false-positive "High Seas" classifications for onshore coordinates.
 * 2. High-performance LRU caching and pre-fetching for smooth playback and offline video export.
 * 3. Graceful fallback to formatted coordinates on timeouts or network failures.
 */
class AdminDivisionResolver(
    private val context: Context,
    private val repository: GeocodingRepository = GeocodingRepositoryImpl(context)
) {

    companion object {
        const val FALLBACK_INTERNATIONAL_WATERS = GlobalAdminResolver.HIGH_SEAS_LABEL

        /**
         * Parses an Address object according to global administrative division cascade rules.
         */
        fun parseAddress(address: Address?): String {
            val division = GlobalAdminResolver.extractAdminDivision(address, 0.0, 0.0)
            return division.level1Name ?: division.countryName ?: FALLBACK_INTERNATIONAL_WATERS
        }
    }

    /**
     * Resolves the administrative division asynchronously according to [config].
     * Caches the result to avoid repeated lookups.
     */
    suspend fun resolve(
        lat: Double,
        lon: Double,
        config: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY
    ): String = withContext(Dispatchers.IO) {
        val division = repository.resolve(lat, lon)
        division.format(config)
    }

    /**
     * Synchronous lookup for rendering loops (Playback & Video Export).
     * Strictly zero network delay. Uses in-memory LRU cache or territory boundary heuristics.
     */
    fun resolveSync(
        lat: Double,
        lon: Double,
        config: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY
    ): String {
        val division = repository.resolveSync(lat, lon)
        return division.format(config)
    }

    /**
     * Pre-fetches and caches all administrative divisions along the track
     * prior to video export or playback, ensuring frame rendering is never blocked.
     */
    suspend fun preloadForTrack(
        track: GpxTrack,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (track.points.isEmpty()) return@withContext

        val distinctCoords = mutableListOf<Pair<Double, Double>>()
        val seenKeys = mutableSetOf<String>()

        for (pt in track.points) {
            val key = String.format(Locale.US, "%.3f,%.3f", pt.lat, pt.lon)
            if (seenKeys.add(key)) {
                distinctCoords.add(Pair(pt.lat, pt.lon))
            }
        }

        val total = distinctCoords.size
        var loaded = 0

        for ((lat, lon) in distinctCoords) {
            repository.resolve(lat, lon)
            loaded++
            onProgress?.invoke(loaded, total)
            if (total > 1) {
                delay(30L)
            }
        }
    }
}
