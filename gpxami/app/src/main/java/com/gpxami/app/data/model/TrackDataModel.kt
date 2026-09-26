package com.gpxami.app.data.model

import kotlin.math.*

/**
 * Raw point parsed directly from GPX XML before calculations and cleaning.
 */
data class RawGpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Long? = null
)

/**
 * Fully processed GPX track point with cumulative metrics.
 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double,
    val time: Long? = null,
    val cumulativeDistanceMeters: Double = 0.0,
    val cumulativeDistanceKm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val gradientPercent: Double = 0.0
)

/**
 * Geographical bounding box for camera fitting and viewport calculations.
 */
data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
    val spanLat: Double get() = maxLat - minLat
    val spanLon: Double get() = maxLon - minLon
}

/**
 * Interpolated state along the track at an arbitrary continuous progress fraction (0.0 .. 1.0).
 */
data class InterpolatedPoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double,
    val cumulativeDistanceKm: Double,
    val speedKmh: Double,
    val bearingDegrees: Float,
    val gradientPercent: Double,
    val progress: Float,
    val pointIndex: Int
)

/**
 * Isolated Point of Interest / Waypoint parsed from GPX (<wpt>).
 * Displayed as a static landmark point on the map, not part of animated vehicle route.
 */
data class GpxWaypoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val name: String? = null,
    val desc: String? = null
)

/**
 * Complete processed GPX track containing all track points and summary statistics.
 */
data class GpxTrack(
    val name: String = "Unnamed Route",
    val points: List<GpxPoint> = emptyList(),
    val waypoints: List<GpxWaypoint> = emptyList(),
    val totalDistanceMeters: Double = 0.0,
    val totalDistanceKm: Double = 0.0,
    val minElevation: Double = 0.0,
    val maxElevation: Double = 0.0,
    val totalAscent: Double = 0.0,
    val totalDescent: Double = 0.0,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val totalDurationSeconds: Long = 0,
    val bounds: GeoBounds = GeoBounds(0.0, 0.0, 0.0, 0.0)
) {
    val hasElevation: Boolean get() = points.isNotEmpty() && (maxElevation - minElevation > 1.0)

    /**
     * Interpolates the vehicle's position, bearing, elevation, and telemetry at any fractional progress [0.0f .. 1.0f].
     */
    fun interpolate(progress: Float): InterpolatedPoint {
        if (points.isEmpty()) {
            return InterpolatedPoint(0.0, 0.0, 0.0, 0.0, 0.0, 0f, 0.0, progress, 0)
        }
        if (points.size == 1) {
            val p = points[0]
            return InterpolatedPoint(p.lat, p.lon, p.elevation, 0.0, 0.0, 0f, 0.0, progress, 0)
        }

        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        val targetDistance = clampedProgress * totalDistanceMeters

        // Binary search for segment where targetDistance falls
        var low = 0
        var high = points.size - 2
        var segmentIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (points[mid].cumulativeDistanceMeters <= targetDistance) {
                segmentIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        segmentIndex = segmentIndex.coerceIn(0, points.size - 2)
        val p1 = points[segmentIndex]
        val p2 = points[segmentIndex + 1]

        val segDist = p2.cumulativeDistanceMeters - p1.cumulativeDistanceMeters
        val segFraction = if (segDist > 0.001) {
            ((targetDistance - p1.cumulativeDistanceMeters) / segDist).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val lat = p1.lat + (p2.lat - p1.lat) * segFraction
        val lon = p1.lon + (p2.lon - p1.lon) * segFraction
        val ele = p1.elevation + (p2.elevation - p1.elevation) * segFraction
        val distKm = (p1.cumulativeDistanceMeters + segDist * segFraction) / 1000.0
        val speed = p1.speedKmh + (p2.speedKmh - p1.speedKmh) * segFraction
        val gradient = p1.gradientPercent + (p2.gradientPercent - p1.gradientPercent) * segFraction

        // Calculate directional bearing (heading) towards the next point
        val bearing = calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon).toFloat()

        return InterpolatedPoint(
            lat = lat,
            lon = lon,
            elevation = ele,
            cumulativeDistanceKm = distKm,
            speedKmh = speed,
            bearingDegrees = bearing,
            gradientPercent = gradient,
            progress = clampedProgress,
            pointIndex = segmentIndex
        )
    }

    /**
     * Slices the track to a sub-track between startFraction and endFraction (0.0 .. 1.0),
     * recalculating all cumulative distances, elevation profile, timestamps, and bounds.
     */
    fun sliceRange(startFraction: Float, endFraction: Float): GpxTrack {
        if (points.isEmpty()) return this
        val startF = startFraction.coerceIn(0.0f, 1.0f)
        val endF = endFraction.coerceIn(startF, 1.0f)
        if (endF - startF < 0.001f && points.size > 1) {
            return this
        }

        val startInterp = interpolate(startF)
        val endInterp = interpolate(endF)

        val startDist = startF * totalDistanceMeters
        val endDist = endF * totalDistanceMeters
        val slicedTotalDist = endDist - startDist

        val startTimeEstimated = if (startTime != null && endTime != null && endTime > startTime) {
            startTime + ((endTime - startTime) * startF).toLong()
        } else points.firstOrNull()?.time

        val endTimeEstimated = if (startTime != null && endTime != null && endTime > startTime) {
            startTime + ((endTime - startTime) * endF).toLong()
        } else points.lastOrNull()?.time

        val intermediatePoints = points.filter {
            it.cumulativeDistanceMeters > startDist && it.cumulativeDistanceMeters < endDist
        }

        val rawSlicedPoints = mutableListOf<GpxPoint>()
        rawSlicedPoints.add(
            GpxPoint(
                lat = startInterp.lat,
                lon = startInterp.lon,
                elevation = startInterp.elevation,
                time = startTimeEstimated,
                cumulativeDistanceMeters = 0.0,
                cumulativeDistanceKm = 0.0,
                speedKmh = startInterp.speedKmh,
                gradientPercent = startInterp.gradientPercent
            )
        )
        for (pt in intermediatePoints) {
            val dist = (pt.cumulativeDistanceMeters - startDist).coerceAtLeast(0.0)
            rawSlicedPoints.add(
                pt.copy(
                    cumulativeDistanceMeters = dist,
                    cumulativeDistanceKm = dist / 1000.0
                )
            )
        }
        rawSlicedPoints.add(
            GpxPoint(
                lat = endInterp.lat,
                lon = endInterp.lon,
                elevation = endInterp.elevation,
                time = endTimeEstimated,
                cumulativeDistanceMeters = slicedTotalDist,
                cumulativeDistanceKm = slicedTotalDist / 1000.0,
                speedKmh = endInterp.speedKmh,
                gradientPercent = endInterp.gradientPercent
            )
        )

        var minEle = Double.MAX_VALUE
        var maxEle = -Double.MAX_VALUE
        var totalAscent = 0.0
        var totalDescent = 0.0
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (i in rawSlicedPoints.indices) {
            val curr = rawSlicedPoints[i]
            val ele = curr.elevation

            minEle = min(minEle, ele)
            maxEle = max(maxEle, ele)
            minLat = min(minLat, curr.lat)
            maxLat = max(maxLat, curr.lat)
            minLon = min(minLon, curr.lon)
            maxLon = max(maxLon, curr.lon)

            if (i > 0) {
                val prev = rawSlicedPoints[i - 1]
                val deltaEle = ele - prev.elevation
                if (deltaEle > 0) totalAscent += deltaEle else totalDescent += -deltaEle
            }
        }

        for (wpt in waypoints) {
            minLat = kotlin.math.min(minLat, wpt.lat)
            maxLat = kotlin.math.max(maxLat, wpt.lat)
            minLon = kotlin.math.min(minLon, wpt.lon)
            maxLon = kotlin.math.max(maxLon, wpt.lon)
        }

        val durationSeconds = if (startTimeEstimated != null && endTimeEstimated != null && endTimeEstimated > startTimeEstimated) {
            (endTimeEstimated - startTimeEstimated) / 1000L
        } else {
            max(1L, (slicedTotalDist / (20.0 / 3.6)).toLong())
        }

        return GpxTrack(
            name = name,
            points = rawSlicedPoints,
            waypoints = waypoints,
            totalDistanceMeters = slicedTotalDist,
            totalDistanceKm = slicedTotalDist / 1000.0,
            minElevation = if (minEle == Double.MAX_VALUE) 0.0 else minEle,
            maxElevation = if (maxEle == -Double.MAX_VALUE) 0.0 else maxEle,
            totalAscent = totalAscent,
            totalDescent = totalDescent,
            startTime = startTimeEstimated,
            endTime = endTimeEstimated,
            totalDurationSeconds = max(1L, durationSeconds),
            bounds = GeoBounds(
                minLat = if (minLat == Double.MAX_VALUE) 0.0 else minLat,
                maxLat = if (maxLat == -Double.MAX_VALUE) 0.0 else maxLat,
                minLon = if (minLon == Double.MAX_VALUE) 0.0 else minLon,
                maxLon = if (maxLon == -Double.MAX_VALUE) 0.0 else maxLon
            )
        )
    }

    companion object {
        /**
         * Calculates initial bearing (azimuth) in degrees from (lat1, lon1) to (lat2, lon2).
         */
        fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaLambda = Math.toRadians(lon2 - lon1)

            val y = sin(deltaLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
            val bearing = Math.toDegrees(atan2(y, x))
            return (bearing + 360.0) % 360.0
        }

        /**
         * Haversine distance in meters between two lat/lon pairs.
         */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0).pow(2.0) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2.0).pow(2.0)
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return r * c
        }
    }
}
