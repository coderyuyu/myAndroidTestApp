package com.dir2gpx.service

import com.dir2gpx.model.PointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateParserTest {

    @Test
    fun testParsePathBasedCoordinates() {
        val url = "https://www.google.com/maps/dir/37.7749,-122.4194/37.3382,-121.8863"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 2 points", 2, points.size)
        assertEquals(37.7749, points[0].latitude, 0.0001)
        assertEquals(-122.4194, points[0].longitude, 0.0001)
        assertEquals(PointType.ORIGIN, points[0].type)
        assertEquals("Start", points[0].name)

        assertEquals(37.3382, points[1].latitude, 0.0001)
        assertEquals(-121.8863, points[1].longitude, 0.0001)
        assertEquals(PointType.DESTINATION, points[1].type)
        assertEquals("Destination", points[1].name)
    }

    @Test
    fun testParseWithWaypoints() {
        val url = "https://www.google.com/maps/dir/37.7749,-122.4194/37.5,-122.1/37.3382,-121.8863"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 3 points", 3, points.size)
        assertEquals(PointType.ORIGIN, points[0].type)
        assertEquals(PointType.VIA, points[1].type)
        assertEquals("Via 1", points[1].name)
        assertEquals(PointType.DESTINATION, points[2].type)
    }

    @Test
    fun testParseQueryParameters() {
        val url = "https://www.google.com/maps?origin=37.7749,-122.4194&destination=37.3382,-121.8863"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 2 points from query params", 2, points.size)
        assertEquals(37.7749, points[0].latitude, 0.0001)
        assertEquals(-122.4194, points[0].longitude, 0.0001)
        assertEquals(PointType.ORIGIN, points[0].type)
        assertEquals(PointType.DESTINATION, points[1].type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseInvalidUrlThrows() {
        CoordinateParser.parse("https://google.com/search?q=hello")
    }

    @Test
    fun testParseDataParameters() {
        val url = "https://www.google.com/maps/dir/data=!4m14!4m13!1m5!1m1!1s0x0:0x0!2m2!1d-122.4194!2d37.7749!1m5!1m1!1s0x0:0x0!2m2!1d-121.8863!2d37.3382"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 2 points from data parameters", 2, points.size)
        assertEquals(37.7749, points[0].latitude, 0.0001)
        assertEquals(-122.4194, points[0].longitude, 0.0001)
        assertEquals(37.3382, points[1].latitude, 0.0001)
        assertEquals(-121.8863, points[1].longitude, 0.0001)
    }

    @Test
    fun testExtractPlaceNames() {
        val url = "https://www.google.com/maps/dir/San+Francisco,+CA/San+Jose,+CA/@37.55,-122.1,10z"
        val places = CoordinateParser.extractPlaceNames(url)

        assertEquals(2, places.size)
        assertEquals("San Francisco, CA", places[0])
        assertEquals("San Jose, CA", places[1])
    }
}
