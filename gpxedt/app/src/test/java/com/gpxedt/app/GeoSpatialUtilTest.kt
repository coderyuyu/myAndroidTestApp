package com.gpxedt.app

import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.util.GeoSpatialUtil
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GeoSpatialUtilTest {

    @Test
    fun testHaversineDistance() {
        // Distance between (0, 0) and (0, 1) degree longitude on equator: ~111.32 km
        val dist = GeoSpatialUtil.haversineDistance(0.0, 0.0, 0.0, 1.0)
        assertEquals(111319.5, dist, 500.0)
    }

    @Test
    fun testPerpendicularProjectionOnSegment() {
        val t1 = Instant.parse("2026-10-01T10:00:00Z")
        val t2 = Instant.parse("2026-10-01T10:10:00Z")

        val pA = TrackPoint(lat = 25.0, lon = 121.50, ele = 100.0, time = t1)
        val pB = TrackPoint(lat = 25.0, lon = 121.60, ele = 200.0, time = t2)

        // Query point perpendicularly above the midpoint of segment AB
        val queryLat = 25.01
        val queryLon = 121.55

        val result = GeoSpatialUtil.projectPointToSegment(queryLat, queryLon, pA, pB, segmentIndex = 0)

        assertEquals(0, result.segmentIndex)
        assertEquals(1, result.insertionIndex)
        assertEquals(0.5, result.projectionRatio, 0.01)

        // Projected point should have lon ~ 121.55, lat ~ 25.0
        assertEquals(25.0, result.projectedPoint.lat, 0.0001)
        assertEquals(121.55, result.projectedPoint.lon, 0.0001)

        // Elevation and timestamp should be linearly interpolated
        assertNotNull(result.projectedPoint.ele)
        assertEquals(150.0, result.projectedPoint.ele!!, 0.5)

        assertNotNull(result.projectedPoint.time)
        val expectedTime = Instant.parse("2026-10-01T10:05:00Z")
        assertEquals(expectedTime.epochSecond, result.projectedPoint.time!!.epochSecond)

        // Distance should be roughly 0.01 deg lat ~ 1113 meters
        assertEquals(1113.0, result.distanceMeters, 20.0)
    }

    @Test
    fun testFindNearestSegmentInsertionIndex() {
        // Track with 4 points -> 3 segments (0, 1, 2)
        // P0 (25.0, 121.0) -> P1 (25.0, 121.2) -> P2 (25.0, 121.4) -> P3 (25.0, 121.6)
        val track = listOf(
            TrackPoint(25.0, 121.0),
            TrackPoint(25.0, 121.2),
            TrackPoint(25.0, 121.4),
            TrackPoint(25.0, 121.6)
        )

        // Query point right near segment 1 (between P1 and P2)
        val query = TrackPoint(25.001, 121.3)
        val nearest = GeoSpatialUtil.findNearestSegment(query, track)

        assertNotNull(nearest)
        assertEquals(1, nearest!!.segmentIndex)
        assertEquals(2, nearest.insertionIndex) // index + 1
    }

    @Test
    fun testProjectionRatioClampedBeyondEndpoints() {
        val pA = TrackPoint(25.0, 121.0)
        val pB = TrackPoint(25.0, 121.2)

        // Point far to the left of pA
        val leftResult = GeoSpatialUtil.projectPointToSegment(25.0, 120.8, pA, pB, 0)
        assertEquals(0.0, leftResult.projectionRatio, 0.0001)
        assertEquals(pA.lat, leftResult.projectedPoint.lat, 0.0001)
        assertEquals(pA.lon, leftResult.projectedPoint.lon, 0.0001)

        // Point far to the right of pB
        val rightResult = GeoSpatialUtil.projectPointToSegment(25.0, 121.5, pA, pB, 0)
        assertEquals(1.0, rightResult.projectionRatio, 0.0001)
        assertEquals(pB.lat, rightResult.projectedPoint.lat, 0.0001)
        assertEquals(pB.lon, rightResult.projectedPoint.lon, 0.0001)
    }

    @Test
    fun testInsertVertex() {
        val track = listOf(
            TrackPoint(25.0, 121.0),
            TrackPoint(25.0, 121.2)
        )
        val newPt = TrackPoint(25.05, 121.1)
        val inserted = GeoSpatialUtil.insertVertex(track, newPt, insertionIndex = 1)

        assertEquals(3, inserted.size)
        assertEquals(track[0], inserted[0])
        assertEquals(newPt, inserted[1])
        assertEquals(track[1], inserted[2])
    }

    @Test
    fun testMoveVertex() {
        val track = listOf(
            TrackPoint(25.0, 121.0, ele = 50.0),
            TrackPoint(25.1, 121.1, ele = 75.0),
            TrackPoint(25.2, 121.2, ele = 100.0)
        )

        val moved = GeoSpatialUtil.moveVertex(track, index = 1, newLat = 25.15, newLon = 121.15)
        assertEquals(3, moved.size)
        assertEquals(25.15, moved[1].lat, 0.00001)
        assertEquals(121.15, moved[1].lon, 0.00001)
        assertEquals(75.0, moved[1].ele!!, 0.00001) // Preserves elevation
    }

    @Test
    fun testDeleteVertexEnforces2PointMinimumCheck() {
        val track3 = listOf(
            TrackPoint(25.0, 121.0),
            TrackPoint(25.1, 121.1),
            TrackPoint(25.2, 121.2)
        )

        // Deleting when size is 3 succeeds
        val afterDelete = GeoSpatialUtil.deleteVertex(track3, index = 1)
        assertNotNull(afterDelete)
        assertEquals(2, afterDelete!!.size)
        assertEquals(track3[0], afterDelete[0])
        assertEquals(track3[2], afterDelete[1])

        // Deleting when size is 2 MUST return null (enforcing 2-point minimum guard)
        val track2 = listOf(
            TrackPoint(25.0, 121.0),
            TrackPoint(25.1, 121.1)
        )
        val illegalDelete = GeoSpatialUtil.deleteVertex(track2, index = 0)
        assertNull(illegalDelete)

        // Out of bounds index returns null
        assertNull(GeoSpatialUtil.deleteVertex(track3, index = 10))
    }

    @Test
    fun testPointToWaypointConversion() {
        val time = Instant.parse("2026-10-01T15:30:00Z")
        val pt = TrackPoint(lat = 25.033, lon = 121.565, ele = 88.5, time = time)

        val wpt = GeoSpatialUtil.pointToWaypoint(
            point = pt,
            name = "Peak View",
            desc = "Converted from trackpoint",
            sym = GpxWaypoint.SYM_FLAG_GREEN
        )

        assertEquals("Peak View", wpt.name)
        assertEquals(25.033, wpt.lat, 0.00001)
        assertEquals(121.565, wpt.lon, 0.00001)
        assertEquals(88.5, wpt.ele!!, 0.00001)
        assertEquals(time, wpt.time)
        assertEquals("Converted from trackpoint", wpt.desc)
        assertEquals(GpxWaypoint.SYM_FLAG_GREEN, wpt.sym)
    }

    @Test
    fun testFindClosestVertexIndex() {
        val track = listOf(
            TrackPoint(25.0, 121.0),
            TrackPoint(25.1, 121.1),
            TrackPoint(25.2, 121.2)
        )

        val closest = GeoSpatialUtil.findClosestVertexIndex(25.099, 121.101, track)
        assertEquals(1, closest)
    }
}
