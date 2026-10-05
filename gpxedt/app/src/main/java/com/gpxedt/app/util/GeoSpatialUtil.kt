package com.gpxedt.app.util

import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import java.time.Instant
import kotlin.math.*

/**
 * Result data class for nearest polyline segment projection.
 *
 * @property segmentIndex 0-based index of the segment start point (segment from trackPoints[segmentIndex] to trackPoints[segmentIndex + 1])
 * @property insertionIndex index where a new point should be inserted (segmentIndex + 1)
 * @property distanceMeters perpendicular / shortest distance in meters from the query point to the segment
 * @property projectedPoint coordinate and interpolated metadata (ele, time) projected onto the segment
 * @property projectionRatio normalized scalar t in [0.0, 1.0] along segment AB
 */
data class NearestSegmentResult(
    val segmentIndex: Int,
    val insertionIndex: Int,
    val distanceMeters: Double,
    val projectedPoint: TrackPoint,
    val projectionRatio: Double
)

/**
 * High-performance geospatial utility providing point-to-segment perpendicular distance calculation,
 * vertex projection, snap & insertion logic, and waypoint conversion.
 */
object GeoSpatialUtil {

    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val METERS_PER_DEG_LAT = 111319.5

    /**
     * Calculates the great-circle Haversine distance between two coordinates in meters.
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
     * Calculates perpendicular / shortest projection from point P to segment AB.
     */
    fun projectPointToSegment(
        pLat: Double, pLon: Double,
        aPoint: TrackPoint,
        bPoint: TrackPoint,
        segmentIndex: Int
    ): NearestSegmentResult {
        val aLat = aPoint.lat
        val aLon = aPoint.lon
        val bLat = bPoint.lat
        val bLon = bPoint.lon

        val midLat = Math.toRadians((aLat + bLat + pLat) / 3.0)
        val ky = METERS_PER_DEG_LAT
        val kx = METERS_PER_DEG_LAT * cos(midLat)

        // Metric coordinates relative to point A
        val bx = (bLon - aLon) * kx
        val by = (bLat - aLat) * ky

        val px = (pLon - aLon) * kx
        val py = (pLat - aLat) * ky

        val segmentLenSq = bx * bx + by * by
        val t = if (segmentLenSq < 1e-6) {
            0.0
        } else {
            ((px * bx + py * by) / segmentLenSq).coerceIn(0.0, 1.0)
        }

        val projX = t * bx
        val projY = t * by

        val dx = px - projX
        val dy = py - projY
        val distMeters = sqrt(dx * dx + dy * dy)

        val projLon = aLon + (if (abs(kx) > 1e-6) projX / kx else 0.0)
        val projLat = aLat + projY / ky

        // Interpolate elevation if available on both endpoints
        val interpolatedEle = when {
            aPoint.ele != null && bPoint.ele != null -> aPoint.ele + t * (bPoint.ele - aPoint.ele)
            aPoint.ele != null -> aPoint.ele
            else -> bPoint.ele
        }

        // Interpolate timestamp if available on both endpoints
        val interpolatedTime = when {
            aPoint.time != null && bPoint.time != null -> {
                val startMs = aPoint.time.toEpochMilli()
                val endMs = bPoint.time.toEpochMilli()
                val interpMs = startMs + ((endMs - startMs) * t).toLong()
                Instant.ofEpochMilli(interpMs)
            }
            aPoint.time != null -> aPoint.time
            else -> bPoint.time
        }

        val projectedTrackPoint = TrackPoint(
            lat = projLat,
            lon = projLon,
            ele = interpolatedEle,
            time = interpolatedTime
        )

        return NearestSegmentResult(
            segmentIndex = segmentIndex,
            insertionIndex = segmentIndex + 1,
            distanceMeters = distMeters,
            projectedPoint = projectedTrackPoint,
            projectionRatio = t
        )
    }

    /**
     * Finds the nearest segment in [trackPoints] to query point ([lat], [lon]),
     * returning the perpendicular projection and the insertion index `segmentIndex + 1`.
     * Returns null if track has fewer than 2 points.
     */
    fun findNearestSegment(
        lat: Double,
        lon: Double,
        trackPoints: List<TrackPoint>
    ): NearestSegmentResult? {
        if (trackPoints.size < 2) return null

        var bestResult: NearestSegmentResult? = null
        var minDistance = Double.MAX_VALUE

        for (i in 0 until trackPoints.size - 1) {
            val a = trackPoints[i]
            val b = trackPoints[i + 1]
            val result = projectPointToSegment(lat, lon, a, b, i)
            if (result.distanceMeters < minDistance) {
                minDistance = result.distanceMeters
                bestResult = result
            }
        }

        return bestResult
    }

    /**
     * Overload accepting a [TrackPoint] query point.
     */
    fun findNearestSegment(
        point: TrackPoint,
        trackPoints: List<TrackPoint>
    ): NearestSegmentResult? {
        return findNearestSegment(point.lat, point.lon, trackPoints)
    }

    /**
     * Finds the index of the closest vertex in [trackPoints] to query coordinates ([lat], [lon]).
     */
    fun findClosestVertexIndex(
        lat: Double,
        lon: Double,
        trackPoints: List<TrackPoint>
    ): Int? {
        if (trackPoints.isEmpty()) return null
        var closestIdx = -1
        var minDistance = Double.MAX_VALUE

        trackPoints.forEachIndexed { idx, pt ->
            val dist = haversineDistance(lat, lon, pt.lat, pt.lon)
            if (dist < minDistance) {
                minDistance = dist
                closestIdx = idx
            }
        }

        return if (closestIdx != -1) closestIdx else null
    }

    /**
     * Inserts a new [TrackPoint] into [trackPoints] at [insertionIndex].
     */
    fun insertVertex(
        trackPoints: List<TrackPoint>,
        newPoint: TrackPoint,
        insertionIndex: Int
    ): List<TrackPoint> {
        val clampedIndex = insertionIndex.coerceIn(0, trackPoints.size)
        val result = trackPoints.toMutableList()
        result.add(clampedIndex, newPoint)
        return result
    }

    /**
     * Updates coordinates and optional metadata of the vertex at [index].
     */
    fun moveVertex(
        trackPoints: List<TrackPoint>,
        index: Int,
        newLat: Double,
        newLon: Double,
        newEle: Double? = null,
        newTime: Instant? = null
    ): List<TrackPoint> {
        if (index !in trackPoints.indices) return trackPoints
        val old = trackPoints[index]
        val updated = old.copy(
            lat = newLat,
            lon = newLon,
            ele = newEle ?: old.ele,
            time = newTime ?: old.time
        )
        val result = trackPoints.toMutableList()
        result[index] = updated
        return result
    }

    /**
     * Deletes vertex at [index] while strictly enforcing the 2-point minimum check.
     * Returns null if deletion is not allowed (track has <= 2 points or invalid index).
     */
    fun deleteVertex(
        trackPoints: List<TrackPoint>,
        index: Int
    ): List<TrackPoint>? {
        if (trackPoints.size <= 2 || index !in trackPoints.indices) {
            return null
        }
        val result = trackPoints.toMutableList()
        result.removeAt(index)
        return result
    }

    /**
     * Converts a [TrackPoint] to a [GpxWaypoint], inheriting coordinates, elevation, and timestamp.
     */
    fun pointToWaypoint(
        point: TrackPoint,
        name: String = "WPT",
        desc: String? = null,
        sym: String = GpxWaypoint.SYM_FLAG_RED
    ): GpxWaypoint {
        return GpxWaypoint(
            name = name,
            lat = point.lat,
            lon = point.lon,
            ele = point.ele,
            time = point.time,
            desc = desc,
            sym = GpxWaypoint.normalizeSymbol(sym)
        )
    }
}
