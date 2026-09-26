package com.gpxedt.app.util

import com.gpxedt.app.model.TrackPoint
import org.junit.Assert.*
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun testHaversineDistance() {
        // Taipei 101 to Taipei Main Station (~4.7 km)
        val dist = GeoUtils.haversineDistance(25.0339, 121.5645, 25.0478, 121.5170)
        assertTrue("Distance should be approx 5 km, was $dist", dist in 4500.0..5500.0)
    }

    @Test
    fun testDistanceToSegment_onSegment() {
        // Point exactly on the segment line
        val aLat = 25.0
        val aLon = 121.0
        val bLat = 25.0
        val bLon = 121.02 // ~2 km East

        // Test midpoint
        val pLat = 25.0
        val pLon = 121.01

        val dist = GeoUtils.distanceToSegment(pLat, pLon, aLat, aLon, bLat, bLon)
        assertEquals(0.0, dist, 0.5) // Less than 0.5 meter
    }

    @Test
    fun testDistanceToSegment_perpendicular() {
        val aLat = 25.0
        val aLon = 121.0
        val bLat = 25.0
        val bLon = 121.02

        // Point offset North by 0.001 deg (~111 meters)
        val pLat = 25.001
        val pLon = 121.01

        val dist = GeoUtils.distanceToSegment(pLat, pLon, aLat, aLon, bLat, bLon)
        assertTrue("Distance should be ~111m, was $dist", dist in 105.0..115.0)
    }

    @Test
    fun testIsPointOnRoute() {
        val route = listOf(
            TrackPoint(lat = 25.0000, lon = 121.0000),
            TrackPoint(lat = 25.0010, lon = 121.0010),
            TrackPoint(lat = 25.0020, lon = 121.0020)
        )

        // Point very close to route (approx 10 meters away)
        val nearLat = 25.00055
        val nearLon = 25.00050 // wait, longitude should be 121.0005
        val pointOnRouteLat = 25.00050
        val pointOnRouteLon = 121.00055

        val distOnRoute = GeoUtils.distanceToPolyline(pointOnRouteLat, pointOnRouteLon, route)
        assertTrue("Point should be within 100m (was $distOnRoute)", distOnRoute < 50.0)
        assertTrue(GeoUtils.isPointOnRoute(pointOnRouteLat, pointOnRouteLon, route, thresholdMeters = 100.0))

        // Point far away from route (~1km away)
        val farLat = 25.0100
        val farLon = 121.0000
        val distFar = GeoUtils.distanceToPolyline(farLat, farLon, route)
        assertTrue("Far point should exceed 100m (was $distFar)", distFar > 500.0)
        assertFalse(GeoUtils.isPointOnRoute(farLat, farLon, route, thresholdMeters = 100.0))
    }

    @Test
    fun testEmptyRoute() {
        val emptyRoute = emptyList<TrackPoint>()
        val dist = GeoUtils.distanceToPolyline(25.0, 121.0, emptyRoute)
        assertEquals(Double.MAX_VALUE, dist, 0.0)
        assertFalse(GeoUtils.isPointOnRoute(25.0, 121.0, emptyRoute))
    }
}
