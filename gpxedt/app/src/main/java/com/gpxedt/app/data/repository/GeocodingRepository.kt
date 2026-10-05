package com.gpxedt.app.data.repository

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Repository interface for reverse geocoding coordinates into human-readable place names.
 */
interface GeocodingRepository {
    suspend fun reverseGeocode(lat: Double, lon: Double): String?
}

class GeocodingRepositoryImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 4000L
) : GeocodingRepository {

    private val cache = ConcurrentHashMap<String, String>()

    private fun cacheKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.4f,%.4f", lat, lon)

    override suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        val key = cacheKey(lat, lon)
        cache[key]?.let { return it }

        return withTimeoutOrNull(timeoutMs) {
            withContext(ioDispatcher) {
                try {
                    if (!Geocoder.isPresent()) return@withContext null

                    val geocoder = Geocoder(context, Locale.getDefault())
                    val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        suspendCancellableCoroutine { continuation ->
                            try {
                                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                                    override fun onGeocode(addresses: MutableList<Address>) {
                                        continuation.resume(addresses.firstOrNull())
                                    }
                                    override fun onError(errorMessage: String?) {
                                        continuation.resume(null)
                                    }
                                })
                            } catch (_: Exception) {
                                continuation.resume(null)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                    }

                    val name = formatBestPlaceName(address)
                    if (name != null) {
                        cache[key] = name
                    }
                    name
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        /**
         * Formats an [Address] into a concise, recognizable landmark or area name.
         * Priority: Specific Feature Name > Thoroughfare + Locality > Locality > First Address Line.
         */
        fun formatBestPlaceName(address: Address?): String? {
            if (address == null) return null

            val feature = address.featureName?.trim()
            val thoroughfare = address.thoroughfare?.trim()
            val subLocality = address.subLocality?.trim()
            val locality = address.locality?.trim()
            val subAdminArea = address.subAdminArea?.trim()

            // 1. Specific Feature Name (e.g. "七星山主峰", "板橋火車站", "Taipei 101")
            // Make sure it's not merely a house/street number (like "100" or "12-1")
            if (!feature.isNullOrBlank() && feature != thoroughfare && feature.any { !it.isDigit() && it != '-' }) {
                val area = subLocality ?: locality ?: subAdminArea
                return if (!area.isNullOrBlank() && !feature.contains(area)) {
                    "$feature ($area)"
                } else {
                    feature
                }
            }

            // 2. Street with locality (e.g. "信義區 信義路五段")
            if (!thoroughfare.isNullOrBlank()) {
                val prefix = subLocality ?: locality
                return if (!prefix.isNullOrBlank()) "$prefix $thoroughfare" else thoroughfare
            }

            // 3. Locality or subLocality (e.g. "信義區", "板橋區")
            if (!subLocality.isNullOrBlank()) return subLocality
            if (!locality.isNullOrBlank()) return locality
            if (!subAdminArea.isNullOrBlank()) return subAdminArea

            // 4. First address line as fallback
            val line = address.getAddressLine(0)
            if (!line.isNullOrBlank()) {
                return line
            }

            return null
        }
    }
}
