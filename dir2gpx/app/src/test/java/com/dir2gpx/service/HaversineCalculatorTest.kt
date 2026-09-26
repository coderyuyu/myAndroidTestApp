package com.dir2gpx.service

import com.dir2gpx.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HaversineCalculatorTest {

    @Test
    fun testEquatorialDistance() {
        // 1 degree longitude at equator is ~111.195 km
        val dist = HaversineCalculator.distanceKm(0.0, 0.0, 0.0, 1.0)
        assertTrue("Distance should be around 111.2 km", dist in 111.0..112.0)
    }

    @Test
    fun testSamePointDistanceIsZero() {
        val dist = HaversineCalculator.distanceKm(37.7749, -122.4194, 37.7749, -122.4194)
        assertEquals(0.0, dist, 0.0001)
    }

    @Test
    fun testTotalDistanceKm() {
        val points = listOf(
            RoutePoint(latitude = 0.0, longitude = 0.0),
            RoutePoint(latitude = 0.0, longitude = 1.0),
            RoutePoint(latitude = 0.0, longitude = 2.0)
        )
        val total = HaversineCalculator.totalDistanceKm(points)
        assertTrue("Total distance should be ~222.4 km", total in 222.0..224.0)
    }

    @Test
    fun testInterpolateTimestampsEquidistant() {
        val startTime = Instant.parse("2026-09-19T10:00:00Z")
        val endTime = Instant.parse("2026-09-19T12:00:00Z") // 2 hours (7200 sec)

        val points = listOf(
            RoutePoint(latitude = 0.0, longitude = 0.0),
            RoutePoint(latitude = 0.0, longitude = 1.0),
            RoutePoint(latitude = 0.0, longitude = 2.0)
        )

        val interpolated = HaversineCalculator.interpolateTimestamps(points, startTime, endTime)

        assertEquals(3, interpolated.size)
        assertEquals("Start timestamp matches", startTime, interpolated[0].timestamp)
        assertEquals("End timestamp matches", endTime, interpolated[2].timestamp)

        // Middle point timestamp should be exactly halfway (11:00:00Z)
        val middleTime = interpolated[1].timestamp
        val expectedMiddle = Instant.parse("2026-09-19T11:00:00Z")
        assertEquals("Middle timestamp is halfway", expectedMiddle.epochSecond, middleTime?.epochSecond ?: 0L)
    }

    @Test
    fun testBearingNorth() {
        // Due north from 0,0 to 1,0
        val bearing = HaversineCalculator.bearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, bearing, 0.01)
    }

    @Test
    fun testBearingEast() {
        // Due east from 0,0 to 0,1
        val bearing = HaversineCalculator.bearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, bearing, 0.01)
    }
}
