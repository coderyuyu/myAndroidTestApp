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

    @Test
    fun testParseMoreThanFiveWaypointsPath() {
        // 7 destinations (origin + 5 intermediate stops + destination)
        val url = "https://www.google.com/maps/dir/25.0478,121.5170/24.9936,121.3010/24.8039,120.9647/24.5602,120.8160/24.1477,120.6736/24.0518,120.5161/22.9997,120.2270/@24.0,120.5,9z/data=!4m2!4m1!3e0"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 7 points", 7, points.size)
        assertEquals(PointType.ORIGIN, points[0].type)
        assertEquals(PointType.VIA, points[1].type)
        assertEquals(PointType.VIA, points[2].type)
        assertEquals(PointType.VIA, points[3].type)
        assertEquals(PointType.VIA, points[4].type)
        assertEquals(PointType.VIA, points[5].type)
        assertEquals(PointType.DESTINATION, points[6].type)
        assertEquals(25.0478, points[0].latitude, 0.0001)
        assertEquals(22.9997, points[6].latitude, 0.0001)
    }

    @Test
    fun testExtractPlaceNamesChineseMultiStop() {
        val url = "https://www.google.com/maps/dir/台北車站/桃園車站/新竹車站/苗栗車站/台中車站/彰化車站/台南車站"
        val places = CoordinateParser.extractPlaceNames(url)

        assertEquals("Should extract all 7 place names", 7, places.size)
        assertEquals("台北車站", places[0])
        assertEquals("桃園車站", places[1])
        assertEquals("新竹車站", places[2])
        assertEquals("苗栗車站", places[3])
        assertEquals("台中車站", places[4])
        assertEquals("彰化車站", places[5])
        assertEquals("台南車站", places[6])
    }

    @Test
    fun testParseMoreThanFiveWaypointsQuery() {
        val url = "https://www.google.com/maps/dir/?api=1&origin=25.0478,121.5170&destination=22.9997,120.2270&waypoints=24.9936,121.3010%7C24.8039,120.9647%7C24.5602,120.8160%7C24.1477,120.6736%7C24.0518,120.5161"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 7 points from query with waypoints", 7, points.size)
        assertEquals(PointType.ORIGIN, points[0].type)
        assertEquals(PointType.DESTINATION, points[6].type)
        assertEquals(25.0478, points[0].latitude, 0.0001)
        assertEquals(22.9997, points[6].latitude, 0.0001)
    }

    @Test
    fun testParseUserMapsUrlExpanded() {
        val url = "https://www.google.com/maps/dir/%E6%9E%97%E6%9C%AC%E6%BA%90%E5%9C%92%E9%82%B8/%E9%9D%92%E8%8D%89%E6%B9%96%E9%9D%88%E9%9A%B1%E5%AF%BA(%E6%96%B0%E7%AB%B9%E5%B8%82%E6%9D%B1%E5%8D%80)/300%E6%96%B0%E7%AB%B9%E5%B8%82%E9%A6%99%E5%B1%B1%E5%8D%80%E9%B9%BD%E6%B0%B4%E9%87%8C%E9%95%B7%E8%88%88%E8%A1%97184%E8%99%9F/%E9%AB%98%E9%90%B5%E6%96%B0%E7%AB%B9%E7%AB%99/%E6%A1%83%E5%9C%92%E6%A9%9F%E5%A0%B4%E6%9C%8D%E5%8B%99%E4%B8%AD%E5%BF%83(%E7%AC%AC%E4%B8%80%E8%88%AA%E5%BB%88%E5%87%BA%E5%A2%83)/%E6%9E%97%E6%9C%AC%E6%BA%90%E5%9C%92%E9%82%B8/data=!4m38!4m37!1m5!1m4!1s0x3442a80291b1b5f9:0xb891f90afecb5572!8m2!3d25.0110814!4d121.45460229999999!1m5!1m4!1s0x34684a0e5d0580c3:0x4c5932bc3cdb7430!8m2!3d24.7715306!4d120.9736736!1m5!1m4!1s0x34684ad801ebc8ad:0xf24795ac3c1c4681!8m2!3d24.753308!4d120.9073755!1m5!1m4!1s0x346837a9e8b79471:0xe77a80d572547d50!8m2!3d24.80704!4d121.04046!1m5!1m4!1s0x34429fc12a50bba1:0xa10bee1b4a537c1d!8m2!3d25.081850799999998!4d121.2380476!1m5!1m4!1s0x3442a80291b1b5f9:0xb891f90afecb5572!8m2!3d25.0110814!4d121.45460229999999!3e0"
        val points = CoordinateParser.parse(url)

        assertEquals("Should parse 6 points from data parameter", 6, points.size)
        assertEquals(25.0110814, points[0].latitude, 0.0001)
        assertEquals(121.4546023, points[0].longitude, 0.0001)
        assertEquals(24.7715306, points[1].latitude, 0.0001)
        assertEquals(120.9736736, points[1].longitude, 0.0001)
        assertEquals(24.753308, points[2].latitude, 0.0001)
        assertEquals(120.9073755, points[2].longitude, 0.0001)
        assertEquals(24.80704, points[3].latitude, 0.0001)
        assertEquals(121.04046, points[3].longitude, 0.0001)
        assertEquals(25.0818508, points[4].latitude, 0.0001)
        assertEquals(121.2380476, points[4].longitude, 0.0001)
        assertEquals(25.0110814, points[5].latitude, 0.0001)
        assertEquals(121.4546023, points[5].longitude, 0.0001)
    }
}
