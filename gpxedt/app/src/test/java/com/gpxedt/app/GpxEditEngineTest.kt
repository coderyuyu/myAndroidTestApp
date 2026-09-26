package com.gpxedt.app

import com.gpxedt.app.engine.GpxEditEngine
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.network.TimeInterpolator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class GpxEditEngineTest {

    private lateinit var sampleData: GpxData
    private lateinit var engine: GpxEditEngine

    @Before
    fun setUp() {
        val points = (0 until 10).map { i ->
            TrackPoint(
                lat = 25.0 + i * 0.001,
                lon = 121.5 + i * 0.001,
                ele = 100.0 + i * 10,
                time = Instant.parse("2026-09-20T10:00:00Z").plusSeconds((i * 60).toLong())
            )
        }
        sampleData = GpxData(
            name = "Test Route",
            trackPoints = points,
            waypoints = listOf(
                Waypoint(25.002, 121.502, "WP1")
            )
        )
        engine = GpxEditEngine(sampleData)
    }

    @Test
    fun testTrimStart() {
        // Trim start at index 3: points 0..2 are dropped, index 3 becomes new index 0
        val originalPoint3 = sampleData.trackPoints[3]
        val result = engine.trimStart(3)

        assertEquals(7, result.trackPoints.size)
        assertEquals(originalPoint3.lat, result.trackPoints[0].lat, 0.000001)
        assertTrue(engine.canUndo)

        // Undo
        val undone = engine.undo()
        assertNotNull(undone)
        assertEquals(10, undone!!.trackPoints.size)
        assertTrue(engine.canRedo)

        // Redo
        val redone = engine.redo()
        assertNotNull(redone)
        assertEquals(7, redone!!.trackPoints.size)
    }

    @Test
    fun testTrimEnd() {
        // Trim end at index 6: points 7..9 are dropped, index 6 is the last point
        val originalPoint6 = sampleData.trackPoints[6]
        val result = engine.trimEnd(6)

        assertEquals(7, result.trackPoints.size)
        assertEquals(originalPoint6.lat, result.trackPoints.last().lat, 0.000001)
        assertTrue(engine.canUndo)

        // Undo
        val undone = engine.undo()
        assertEquals(10, undone!!.trackPoints.size)
    }

    @Test
    fun testReplaceSegment() {
        // Start point = index 2, End point = index 7
        // Generated route: [ptA, route1, route2, ptB]
        val ptA = sampleData.trackPoints[2]
        val ptB = sampleData.trackPoints[7]

        val routePoints = listOf(
            ptA,
            TrackPoint(25.0035, 121.5035, 120.0, null),
            TrackPoint(25.0045, 121.5045, 130.0, null),
            ptB
        )

        val result = engine.replaceSegment(2, 7, routePoints)

        // Head: 0..2 (3 points)
        // Middle: route1, route2 (2 points)
        // Tail: 7..9 (3 points)
        // Total: 3 + 2 + 3 = 8 points
        assertEquals(8, result.trackPoints.size)

        assertEquals(sampleData.trackPoints[0].lat, result.trackPoints[0].lat, 0.000001)
        assertEquals(sampleData.trackPoints[2].lat, result.trackPoints[2].lat, 0.000001)
        assertEquals(routePoints[1].lat, result.trackPoints[3].lat, 0.000001)
        assertEquals(routePoints[2].lat, result.trackPoints[4].lat, 0.000001)
        assertEquals(sampleData.trackPoints[7].lat, result.trackPoints[5].lat, 0.000001)

        assertTrue(engine.canUndo)
    }

    @Test
    fun testTimeInterpolator() {
        val startPt = TrackPoint(25.0, 121.0, 10.0, Instant.parse("2026-09-20T10:00:00Z"))
        val endPt = TrackPoint(25.0, 121.01, 10.0, Instant.parse("2026-09-20T10:10:00Z")) // +10 mins

        val intermediatePoints = listOf(
            TrackPoint(25.0, 121.0, 10.0, null),
            TrackPoint(25.0, 121.005, 10.0, null), // middle
            TrackPoint(25.0, 121.01, 10.0, null)
        )

        val interpolated = TimeInterpolator.interpolateRouteTimestamps(startPt, endPt, intermediatePoints)

        assertEquals(3, interpolated.size)
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), interpolated[0].time)
        assertEquals(Instant.parse("2026-09-20T10:10:00Z"), interpolated[2].time)
        // Midpoint should be ~10:05:00Z
        assertNotNull(interpolated[1].time)
        assertTrue(interpolated[1].time!!.isAfter(interpolated[0].time))
        assertTrue(interpolated[1].time!!.isBefore(interpolated[2].time))
    }

    @Test
    fun testAddAndRemoveWaypoint() {
        val newWpt = Waypoint(25.008, 121.508, "Summit Peak", "Top of hill", "Summit")
        val withWpt = engine.addWaypoint(newWpt)

        assertEquals(2, withWpt.waypoints.size)
        assertEquals("Summit Peak", withWpt.waypoints[1].name)

        val withoutWpt = engine.removeWaypoint(newWpt)
        assertEquals(1, withoutWpt.waypoints.size)

        engine.undo()
        assertEquals(2, engine.currentData.waypoints.size)
    }

    @Test
    fun testUpdateWaypoint() {
        val originalWpt = sampleData.waypoints[0]
        val updatedWpt = originalWpt.copy(name = "Updated WP1", desc = "New Description")

        val result = engine.updateWaypoint(originalWpt, updatedWpt)
        assertEquals(1, result.waypoints.size)
        assertEquals("Updated WP1", result.waypoints[0].name)
        assertEquals("New Description", result.waypoints[0].desc)

        // Undo restores original waypoint
        engine.undo()
        assertEquals("WP1", engine.currentData.waypoints[0].name)
        assertNull(engine.currentData.waypoints[0].desc)
    }

    @Test
    fun testSpan() {
        val originalPt2 = sampleData.trackPoints[2]
        val originalPt6 = sampleData.trackPoints[6]

        val result = engine.span(2, 6)
        assertEquals(5, result.trackPoints.size)
        assertEquals(originalPt2.lat, result.trackPoints[0].lat, 0.000001)
        assertEquals(originalPt6.lat, result.trackPoints.last().lat, 0.000001)

        // Undo restores full track
        engine.undo()
        assertEquals(10, engine.currentData.trackPoints.size)
    }
}
