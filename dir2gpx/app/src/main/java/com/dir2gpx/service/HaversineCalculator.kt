package com.dir2gpx.service

import com.dir2gpx.model.RoutePoint
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geospatial math utilities using the Haversine formula for great-circle
 * distance calculations on WGS-84 coordinates.
 */
object HaversineCalculator {

    /** Mean Earth radius in kilometers (WGS-84 volumetric). */
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calculates the great-circle distance between two points in kilometers.
     *
     * @param lat1 Latitude of point 1 (degrees).
     * @param lon1 Longitude of point 1 (degrees).
     * @param lat2 Latitude of point 2 (degrees).
     * @param lon2 Longitude of point 2 (degrees).
     * @return Distance in kilometers.
     */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) *
            sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Calculates the cumulative distances along an ordered sequence of points.
     *
     * @param points Ordered list of route points.
     * @return A list of cumulative distances (in km) from the first point.
     *         The first element is always 0.0.
     */
    fun cumulativeDistances(points: List<RoutePoint>): List<Double> {
        if (points.isEmpty()) return emptyList()

        val distances = mutableListOf(0.0)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val segmentDist = distanceKm(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )
            distances.add(distances.last() + segmentDist)
        }
        return distances
    }

    /**
     * Interpolates realistic UTC timestamps along the route based on cumulative distance.
     *
     * Formula: Time_i = StartTime + (CumulativeDistance_i / TotalDistance) × (EndTime − StartTime)
     *
     * @param points Ordered list of route points.
     * @param startTime Route departure time.
     * @param endTime Route arrival time.
     * @return A new list of [RoutePoint] with interpolated timestamps.
     */
    fun interpolateTimestamps(
        points: List<RoutePoint>,
        startTime: Instant,
        endTime: Instant
    ): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return listOf(points[0].copy(timestamp = startTime))

        val cumDist = cumulativeDistances(points)
        val totalDist = cumDist.last()
        val totalDurationMillis = endTime.toEpochMilli() - startTime.toEpochMilli()

        return points.mapIndexed { index, point ->
            val fraction = if (totalDist > 0) cumDist[index] / totalDist else 0.0
            val interpolatedMillis = startTime.toEpochMilli() +
                (fraction * totalDurationMillis).toLong()
            point.copy(timestamp = Instant.ofEpochMilli(interpolatedMillis))
        }
    }

    /**
     * Calculates the total distance of a route in kilometers.
     *
     * @param points Ordered list of route points.
     * @return Total route distance in kilometers.
     */
    fun totalDistanceKm(points: List<RoutePoint>): Double {
        val cumDist = cumulativeDistances(points)
        return cumDist.lastOrNull() ?: 0.0
    }

    /**
     * Calculates the initial bearing from point 1 to point 2.
     *
     * @return Bearing in degrees (0-360).
     */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) -
            sin(rLat1) * cos(rLat2) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }
}
