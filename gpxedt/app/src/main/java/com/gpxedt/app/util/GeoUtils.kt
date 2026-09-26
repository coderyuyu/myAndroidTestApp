package com.gpxedt.app.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.gpxedt.app.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

object GeoUtils {

    const val DEFAULT_ROUTE_PROXIMITY_THRESHOLD_METERS = 100.0
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the great-circle distance between two coordinates in meters using the Haversine formula.
     */
    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates the minimum distance in meters from point (lat, lon) to a GPX route polyline.
     * Returns Double.MAX_VALUE if trackPoints is empty.
     */
    fun distanceToPolyline(lat: Double, lon: Double, trackPoints: List<TrackPoint>): Double {
        if (trackPoints.isEmpty()) return Double.MAX_VALUE
        if (trackPoints.size == 1) {
            val pt = trackPoints[0]
            return haversineDistance(lat, lon, pt.lat, pt.lon)
        }

        var minDistance = Double.MAX_VALUE

        for (i in 0 until trackPoints.size - 1) {
            val a = trackPoints[i]
            val b = trackPoints[i + 1]
            val dist = distanceToSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (dist < minDistance) {
                minDistance = dist
            }
        }

        return minDistance
    }

    /**
     * Calculates the shortest distance in meters from point P(pLat, pLon) to segment AB.
     */
    fun distanceToSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val midLat = Math.toRadians((aLat + bLat + pLat) / 3.0)
        val ky = 111319.5
        val kx = 111319.5 * cos(midLat)

        // Coordinates in local flat-earth metric space relative to A
        val px = (pLon - aLon) * kx
        val py = (pLat - aLat) * ky

        val bx = (bLon - aLon) * kx
        val by = (bLat - aLat) * ky

        val segmentLenSq = bx * bx + by * by
        if (segmentLenSq < 1e-4) {
            // A and B are essentially the same point
            return sqrt(px * px + py * py)
        }

        // Project P onto segment AB: t in [0, 1]
        val t = (px * bx + py * by) / segmentLenSq
        val clampedT = t.coerceIn(0.0, 1.0)

        val closestX = clampedT * bx
        val closestY = clampedT * by

        val dx = px - closestX
        val dy = py - closestY

        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Checks if a point is within the proximity threshold of a route.
     */
    fun isPointOnRoute(
        lat: Double,
        lon: Double,
        trackPoints: List<TrackPoint>,
        thresholdMeters: Double = DEFAULT_ROUTE_PROXIMITY_THRESHOLD_METERS
    ): Boolean {
        if (trackPoints.isEmpty()) return false
        val dist = distanceToPolyline(lat, lon, trackPoints)
        return dist <= thresholdMeters
    }

    /**
     * Reverse geocodes coordinates to a readable place name using Android Geocoder.
     * Executes asynchronously on Dispatchers.IO.
     */
    suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext null
                val geocoder = Geocoder(context, Locale.getDefault())

                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                formatAddressName(addresses?.firstOrNull())
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Formats an Address object into a concise, recognizable place name.
     */
    private fun formatAddressName(address: Address?): String? {
        if (address == null) return null

        // 1. Feature name (e.g. scenic spot, peak, building name)
        val feature = address.featureName?.trim()
        val thoroughfare = address.thoroughfare?.trim()
        val subLocality = address.subLocality?.trim()
        val locality = address.locality?.trim()

        // If feature name is specific and not just street number
        if (!feature.isNullOrBlank() && feature != thoroughfare && feature.any { !it.isDigit() }) {
            val area = subLocality ?: locality
            return if (!area.isNullOrBlank() && !feature.contains(area)) {
                "$feature ($area)"
            } else {
                feature
            }
        }

        // 2. Street with locality
        if (!thoroughfare.isNullOrBlank()) {
            val prefix = subLocality ?: locality
            return if (!prefix.isNullOrBlank()) "$prefix $thoroughfare" else thoroughfare
        }

        // 3. Locality or subAdminArea
        if (!subLocality.isNullOrBlank()) return subLocality
        if (!locality.isNullOrBlank()) return locality

        // 4. First address line as fallback
        val line = address.getAddressLine(0)
        if (!line.isNullOrBlank()) {
            return line
        }

        return null
    }
}
