package com.gpxami.app.data.repository

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.gpxami.app.data.geocoder.GlobalAdminResolver
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.toTraditionalChinese
import com.gpxami.app.model.AdminDivision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Result model representing resolved first-level and second-level administrative divisions.
 * Retained for backwards compatibility across existing components.
 */
data class AdminDivisionResult(
    val level1: String?,
    val level2: String?
) {
    /**
     * Formats the administrative division result according to the specified [AdminDivisionConfig].
     * - LEVEL_1_ONLY: e.g. "臺北市" / "臺灣"
     * - LEVEL_1_AND_2: e.g. "臺北市 · 中正區"
     */
    fun format(config: AdminDivisionConfig): String {
        val raw = when (config) {
            AdminDivisionConfig.LEVEL_1_ONLY -> {
                level1 ?: GlobalAdminResolver.UNKNOWN_REGION_LABEL
            }
            AdminDivisionConfig.LEVEL_1_AND_2 -> {
                when {
                    !level1.isNullOrBlank() && !level2.isNullOrBlank() -> {
                        if (level1.equals(level2, ignoreCase = true)) {
                            level1
                        } else {
                            "$level1 · $level2"
                        }
                    }
                    !level1.isNullOrBlank() -> level1
                    !level2.isNullOrBlank() -> level2
                    else -> GlobalAdminResolver.UNKNOWN_REGION_LABEL
                }
            }
        }
        return toTraditionalChinese(raw)
    }
}

/**
 * Thread-safe LRU Cache for caching resolved administrative divisions along GPS routes.
 */
class CoordinateLruCache(private val maxSize: Int = 1000) {
    private val map = object : LinkedHashMap<String, AdminDivision>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AdminDivision>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: String): AdminDivision? = map[key]

    @Synchronized
    fun put(key: String, value: AdminDivision) {
        map[key] = value
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}

/**
 * Repository interface for reverse geocoding coordinates into Level 1 and Level 2 administrative divisions.
 */
interface GeocodingRepository {
    /**
     * Resolves the standardized administrative division asynchronously on a background dispatcher.
     */
    suspend fun resolve(lat: Double, lon: Double): AdminDivision

    /**
     * Resolves the standardized administrative division synchronously from LRU memory cache,
     * or gracefully falls back without blocking.
     */
    fun resolveSync(lat: Double, lon: Double): AdminDivision

    /**
     * Backward-compatible resolution returning [AdminDivisionResult].
     */
    suspend fun resolveAdminDivision(lat: Double, lon: Double): AdminDivisionResult

    /**
     * Backward-compatible synchronous resolution returning [AdminDivisionResult].
     */
    fun resolveAdminDivisionSync(lat: Double, lon: Double): AdminDivisionResult
}

/**
 * Production implementation of [GeocodingRepository] using [GlobalAdminResolver],
 * Android [Geocoder], thread-safe LRU caching, coordinate integrity validation,
 * and resilient offline/boundary fallbacks.
 */
class GeocodingRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    cacheCapacity: Int = 1000
) : GeocodingRepository {

    private val cache = CoordinateLruCache(cacheCapacity)

    private fun cacheKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

    override suspend fun resolve(lat: Double, lon: Double): AdminDivision = withContext(ioDispatcher) {
        val sanitized = GlobalAdminResolver.sanitizeCoordinates(lat, lon) ?: Pair(lat, lon)
        val key = cacheKey(sanitized.first, sanitized.second)
        cache.get(key)?.let { return@withContext it }

        val result = fetchFromGeocoder(sanitized.first, sanitized.second)
        cache.put(key, result)
        result
    }

    override fun resolveSync(lat: Double, lon: Double): AdminDivision {
        val sanitized = GlobalAdminResolver.sanitizeCoordinates(lat, lon) ?: Pair(lat, lon)
        val key = cacheKey(sanitized.first, sanitized.second)
        return cache.get(key) ?: GlobalAdminResolver.resolveFallback(sanitized.first, sanitized.second)
    }

    override suspend fun resolveAdminDivision(lat: Double, lon: Double): AdminDivisionResult {
        return resolve(lat, lon).toAdminDivisionResult()
    }

    override fun resolveAdminDivisionSync(lat: Double, lon: Double): AdminDivisionResult {
        return resolveSync(lat, lon).toAdminDivisionResult()
    }

    private fun fetchFromGeocoder(lat: Double, lon: Double): AdminDivision {
        val targetLocale = if (Locale.getDefault().language == "zh") Locale.TRADITIONAL_CHINESE else Locale.getDefault()
        try {
            if (!Geocoder.isPresent()) {
                return GlobalAdminResolver.resolveFallback(lat, lon, targetLocale)
            }

            val geocoder = Geocoder(context, targetLocale)
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = geocoder.getFromLocation(lat, lon, 1)

            if (addresses.isNullOrEmpty()) {
                return GlobalAdminResolver.resolveFallback(lat, lon, targetLocale)
            }

            return GlobalAdminResolver.extractAdminDivision(addresses[0], lat, lon, targetLocale)
        } catch (_: Exception) {
            // Gracefully catch network / service / offline exceptions and evaluate boundary fallback
            return GlobalAdminResolver.resolveFallback(lat, lon, targetLocale)
        }
    }
}
