package com.gpxami.app

import com.gpxami.app.data.model.GeoBounds
import com.gpxami.app.data.model.GpxPoint
import com.gpxami.app.data.model.GpxTrack
import org.junit.Assert.*
import org.junit.Test

class TrackDataModelTest {

    @Test
    fun testHaversineDistanceCalculation() {
        // Distance between Taipei 101 (25.0339, 121.5645) and Taipei Main Station (25.0478, 121.5170) ~5 km
        val dist = GpxTrack.distanceMeters(25.0339, 121.5645, 25.0478, 121.5170)
        assertTrue("Distance should be around 5000m (+-500m)", dist in 4500.0..5500.0)
    }

    @Test
    fun testBearingCalculation() {
        // Due North
        val bearingNorth = GpxTrack.calculateBearing(25.0, 121.0, 26.0, 121.0)
        assertEquals(0.0, bearingNorth, 0.1)

        // Due East
        val bearingEast = GpxTrack.calculateBearing(25.0, 121.0, 25.0, 122.0)
        assertEquals(90.0, bearingEast, 1.0)

        // Due South
        val bearingSouth = GpxTrack.calculateBearing(26.0, 121.0, 25.0, 121.0)
        assertEquals(180.0, bearingSouth, 0.1)

        // Due West
        val bearingWest = GpxTrack.calculateBearing(25.0, 122.0, 25.0, 121.0)
        assertEquals(270.0, bearingWest, 1.0)
    }

    @Test
    fun testContinuousTrackInterpolation() {
        val p1 = GpxPoint(lat = 24.0, lon = 121.0, elevation = 1000.0, cumulativeDistanceMeters = 0.0, speedKmh = 20.0)
        val p2 = GpxPoint(lat = 24.1, lon = 121.0, elevation = 1500.0, cumulativeDistanceMeters = 10000.0, speedKmh = 30.0)
        val p3 = GpxPoint(lat = 24.2, lon = 121.0, elevation = 2000.0, cumulativeDistanceMeters = 20000.0, speedKmh = 40.0)

        val track = GpxTrack(
            name = "Test Route",
            points = listOf(p1, p2, p3),
            totalDistanceMeters = 20000.0,
            totalDistanceKm = 20.0,
            minElevation = 1000.0,
            maxElevation = 2000.0,
            bounds = GeoBounds(24.0, 24.2, 121.0, 121.0)
        )

        // Test start progress = 0.0
        val startPt = track.interpolate(0.0f)
        assertEquals(24.0, startPt.lat, 0.001)
        assertEquals(1000.0, startPt.elevation, 0.1)
        assertEquals(0.0, startPt.cumulativeDistanceKm, 0.01)

        // Test midpoint progress = 0.5 (should be at p2)
        val midPt = track.interpolate(0.5f)
        assertEquals(24.1, midPt.lat, 0.001)
        assertEquals(1500.0, midPt.elevation, 0.1)
        assertEquals(10.0, midPt.cumulativeDistanceKm, 0.1)
        assertEquals(30.0, midPt.speedKmh, 0.5)

        // Test quarter progress = 0.25 (halfway between p1 and p2)
        val qPt = track.interpolate(0.25f)
        assertEquals(24.05, qPt.lat, 0.001)
        assertEquals(1250.0, qPt.elevation, 0.5)
        assertEquals(5.0, qPt.cumulativeDistanceKm, 0.1)
        assertEquals(25.0, qPt.speedKmh, 0.5)

        // Test end progress = 1.0
        val endPt = track.interpolate(1.0f)
        assertEquals(24.2, endPt.lat, 0.001)
        assertEquals(2000.0, endPt.elevation, 0.1)
        assertEquals(20.0, endPt.cumulativeDistanceKm, 0.01)
    }

    @Test
    fun testEmptyTrackInterpolationFallback() {
        val emptyTrack = GpxTrack()
        val pt = emptyTrack.interpolate(0.5f)
        assertNotNull(pt)
        assertEquals(0.0, pt.lat, 0.001)
        assertEquals(0.0, pt.elevation, 0.001)
    }

    @Test
    fun testTrackSlicingRange() {
        val p1 = GpxPoint(lat = 24.0, lon = 121.0, elevation = 1000.0, time = 1000L, cumulativeDistanceMeters = 0.0, speedKmh = 20.0)
        val p2 = GpxPoint(lat = 24.1, lon = 121.0, elevation = 1500.0, time = 2000L, cumulativeDistanceMeters = 10000.0, speedKmh = 30.0)
        val p3 = GpxPoint(lat = 24.2, lon = 121.0, elevation = 2000.0, time = 3000L, cumulativeDistanceMeters = 20000.0, speedKmh = 40.0)

        val fullTrack = GpxTrack(
            name = "Test Route",
            points = listOf(p1, p2, p3),
            totalDistanceMeters = 20000.0,
            totalDistanceKm = 20.0,
            minElevation = 1000.0,
            maxElevation = 2000.0,
            startTime = 1000L,
            endTime = 3000L,
            totalDurationSeconds = 2L,
            bounds = GeoBounds(24.0, 24.2, 121.0, 121.0)
        )

        // Slice from 25% (5km) to 75% (15km)
        val sliced = fullTrack.sliceRange(0.25f, 0.75f)
        assertTrue(sliced.points.size >= 2)
        assertEquals(0.0, sliced.points.first().cumulativeDistanceMeters, 0.001)
        assertTrue("Sliced distance should be ~10km", sliced.totalDistanceMeters in 9500.0..10500.0)
        assertTrue("Min elevation should be >= 1000m", sliced.minElevation >= 1000.0)
        assertTrue("Max elevation should be <= 2000m", sliced.maxElevation <= 2000.0)
        assertNotNull(sliced.startTime)
        assertNotNull(sliced.endTime)
        assertTrue(sliced.endTime!! > sliced.startTime!!)
    }
}
