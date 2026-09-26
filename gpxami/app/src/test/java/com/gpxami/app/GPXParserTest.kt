package com.gpxami.app

import com.gpxami.app.data.model.RawGpxPoint
import com.gpxami.app.data.parser.GPXParser
import org.junit.Assert.*
import org.junit.Test

class GPXParserTest {

    @Test
    fun testProcessTrackPointsWithMissingElevationInterpolation() {
        val rawPoints = listOf(
            RawGpxPoint(lat = 24.00, lon = 121.00, ele = 100.0, time = 1000L),
            RawGpxPoint(lat = 24.01, lon = 121.00, ele = null, time = 2000L),  // Should be interpolated to 200.0
            RawGpxPoint(lat = 24.02, lon = 121.00, ele = 300.0, time = 3000L)
        )

        val track = GPXParser.processTrackPoints("Elevation Test", rawPoints)
        assertEquals(3, track.points.size)
        assertEquals(100.0, track.minElevation, 0.1)
        assertEquals(300.0, track.maxElevation, 0.1)
        assertTrue("Total distance should be > 0", track.totalDistanceMeters > 0)
    }

    @Test
    fun testProcessTrackPointsWithMissingTimestamps() {
        val rawPoints = listOf(
            RawGpxPoint(lat = 24.00, lon = 121.00, ele = 500.0, time = null),
            RawGpxPoint(lat = 24.01, lon = 121.00, ele = 520.0, time = null),
            RawGpxPoint(lat = 24.02, lon = 121.00, ele = 540.0, time = null)
        )

        val track = GPXParser.processTrackPoints("Timestamp Test", rawPoints)
        assertEquals(3, track.points.size)

        // All points should now have synthesized monotonically increasing timestamps
        assertNotNull(track.points[0].time)
        assertNotNull(track.points[1].time)
        assertNotNull(track.points[2].time)
        assertTrue(track.points[1].time!! > track.points[0].time!!)
        assertTrue(track.points[2].time!! > track.points[1].time!!)
    }

    @Test
    fun testEmptyRawPointsHandling() {
        val track = GPXParser.processTrackPoints("Empty", emptyList())
        assertEquals(0, track.points.size)
        assertEquals(0.0, track.totalDistanceMeters, 0.001)
    }

    @Test
    fun testParseStreamWithBomAndNamespaces() = kotlinx.coroutines.runBlocking {
        // GPX with UTF-8 BOM (0xEF, 0xBB, 0xBF) and XML namespace prefixes
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx:gpx version="1.1" xmlns:gpx="http://www.topografix.com/GPX/1/1">
              <gpx:trk>
                <gpx:name>BOM &amp; Namespaces Route</gpx:name>
                <gpx:trkseg>
                  <gpx:trkpt lat="25.033" lon="121.565">
                    <gpx:ele>100.5</gpx:ele>
                    <gpx:time>2023-01-01T10:00:00Z</gpx:time>
                  </gpx:trkpt>
                  <gpx:trkpt lat="25.034" lon="121.566">
                    <gpx:ele>105.0</gpx:ele>
                    <gpx:time>2023-01-01T10:01:00Z</gpx:time>
                  </gpx:trkpt>
                </gpx:trkseg>
              </gpx:trk>
            </gpx:gpx>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val fullBytes = bom + xml
        val result = GPXParser.parse(fullBytes.inputStream())
        if (result.isFailure) {
            result.exceptionOrNull()?.printStackTrace()
        }
        assertTrue("Parser failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val track = result.getOrThrow()
        assertEquals("BOM & Namespaces Route", track.name)
        assertEquals(2, track.points.size)
        assertEquals(25.033, track.points[0].lat, 0.0001)
        assertEquals(121.565, track.points[0].lon, 0.0001)
        assertEquals(100.5, track.points[0].elevation, 0.01)
    }

    @Test
    fun testParseStreamWithCommaDecimals() = kotlinx.coroutines.runBlocking {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>European Comma Coordinates</name>
                <trkseg>
                  <trkpt lat="48,8584" lon="2,2945">
                    <ele>35,5</ele>
                    <time>2023-05-15T08:30:00+02:00</time>
                  </trkpt>
                  <trkpt lat="48,8585" lon="2,2946">
                    <ele>36,0</ele>
                    <time>2023-05-15T08:31:00+02:00</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue("Parser should succeed with comma decimal coordinates", result.isSuccess)
        val track = result.getOrThrow()
        assertEquals(2, track.points.size)
        assertEquals(48.8584, track.points[0].lat, 0.0001)
        assertEquals(2.2945, track.points[0].lon, 0.0001)
        assertEquals(35.5, track.points[0].elevation, 0.01)
    }

    @Test
    fun testGpxWithBothRouteAndTrackIgnoresRoughRoute() = kotlinx.coroutines.runBlocking {
        // GPX containing both coarse <rte> (rough turn points) and detailed <trk> (GPS track).
        // The parser must pick the detailed track and NOT concatenate the rough route.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <metadata>
                <name>Trip Overview</name>
              </metadata>
              <rte>
                <name>Rough Planned Route</name>
                <rtept lat="24.0" lon="121.0"><ele>100.0</ele></rtept>
                <rtept lat="24.5" lon="121.5"><ele>500.0</ele></rtept>
              </rte>
              <trk>
                <name>Detailed GPS Track</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"><ele>100.0</ele><time>2023-01-01T10:00:00Z</time></trkpt>
                  <trkpt lat="24.1" lon="121.1"><ele>150.0</ele><time>2023-01-01T10:05:00Z</time></trkpt>
                  <trkpt lat="24.2" lon="121.2"><ele>220.0</ele><time>2023-01-01T10:10:00Z</time></trkpt>
                  <trkpt lat="24.3" lon="121.3"><ele>310.0</ele><time>2023-01-01T10:15:00Z</time></trkpt>
                  <trkpt lat="24.4" lon="121.4"><ele>420.0</ele><time>2023-01-01T10:20:00Z</time></trkpt>
                  <trkpt lat="24.5" lon="121.5"><ele>500.0</ele><time>2023-01-01T10:25:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue("Parsing should succeed", result.isSuccess)
        val track = result.getOrThrow()

        // Should ONLY have the 6 detailed track points, NOT 8 points (2 rough + 6 detailed)
        assertEquals(6, track.points.size)
        assertEquals("Detailed GPS Track", track.name)
        assertEquals(24.0, track.points.first().lat, 0.001)
        assertEquals(24.5, track.points.last().lat, 0.001)
    }

    @Test
    fun testGpxWithWaypointsAndTrackIgnoresWaypointsForPath() = kotlinx.coroutines.runBlocking {
        // Waypoints (<wpt>) are landmarks/POIs and should NOT be added into the track line.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <wpt lat="25.00" lon="121.50">
                <name>Campground Landmark</name>
              </wpt>
              <wpt lat="25.10" lon="121.60">
                <name>Mountain Peak</name>
              </wpt>
              <trk>
                <name>Scenic Mountain Track</name>
                <trkseg>
                  <trkpt lat="24.00" lon="121.00"><ele>100.0</ele><time>2023-01-01T10:00:00Z</time></trkpt>
                  <trkpt lat="24.01" lon="121.01"><ele>120.0</ele><time>2023-01-01T10:01:00Z</time></trkpt>
                  <trkpt lat="24.02" lon="121.02"><ele>140.0</ele><time>2023-01-01T10:02:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue(result.isSuccess)
        val track = result.getOrThrow()

        // Waypoints must NOT be prepended to the track
        assertEquals(3, track.points.size)
        assertEquals(24.00, track.points[0].lat, 0.001)

        // Waypoints must be preserved in track.waypoints
        assertEquals(2, track.waypoints.size)
        assertEquals("Campground Landmark", track.waypoints[0].name)
        assertEquals(25.00, track.waypoints[0].lat, 0.001)
        assertEquals("Mountain Peak", track.waypoints[1].name)
        assertEquals(25.10, track.waypoints[1].lat, 0.001)
    }

    @Test
    fun testGpxWithMultipleDuplicateTracksPicksDetailed() = kotlinx.coroutines.runBlocking {
        // When a file contains two tracks covering the same route (e.g. rough overview + detailed recording),
        // it must pick the detailed track instead of looping twice.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>Rough Overview Track</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"><ele>100.0</ele></trkpt>
                  <trkpt lat="24.5" lon="121.5"><ele>500.0</ele></trkpt>
                </trkseg>
              </trk>
              <trk>
                <name>Detailed Accurate Track</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"><ele>100.0</ele><time>2023-01-01T10:00:00Z</time></trkpt>
                  <trkpt lat="24.1" lon="121.1"><ele>180.0</ele><time>2023-01-01T10:05:00Z</time></trkpt>
                  <trkpt lat="24.2" lon="121.2"><ele>260.0</ele><time>2023-01-01T10:10:00Z</time></trkpt>
                  <trkpt lat="24.3" lon="121.3"><ele>340.0</ele><time>2023-01-01T10:15:00Z</time></trkpt>
                  <trkpt lat="24.4" lon="121.4"><ele>420.0</ele><time>2023-01-01T10:20:00Z</time></trkpt>
                  <trkpt lat="24.5" lon="121.5"><ele>500.0</ele><time>2023-01-01T10:25:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue(result.isSuccess)
        val track = result.getOrThrow()

        // Must pick the 6-point detailed track, not combine into 8 points
        assertEquals(6, track.points.size)
        assertEquals("Detailed Accurate Track", track.name)
    }

    @Test
    fun testGpxWithRouteOnlyFailsBecauseOnlyTrkIsAnimated() = kotlinx.coroutines.runBlocking {
        // When a file contains ONLY <rte> (no <trk>), it must NOT be animated.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <rte>
                <name>Pure Navigation Route</name>
                <rtept lat="24.0" lon="121.0"><ele>100.0</ele></rtept>
                <rtept lat="24.1" lon="121.1"><ele>150.0</ele></rtept>
                <rtept lat="24.2" lon="121.2"><ele>200.0</ele></rtept>
              </rte>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("<trk>") == true)
    }

    @Test
    fun testGpxWithSequentialTracksMergesCorrectly() = kotlinx.coroutines.runBlocking {
        // Multi-stage tracks (e.g. Day 1, Day 2) where end of stage 1 connects to stage 2 should be merged.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <name>Stage 1</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"><ele>100.0</ele><time>2023-01-01T10:00:00Z</time></trkpt>
                  <trkpt lat="24.1" lon="121.1"><ele>150.0</ele><time>2023-01-01T11:00:00Z</time></trkpt>
                </trkseg>
              </trk>
              <trk>
                <name>Stage 2</name>
                <trkseg>
                  <trkpt lat="24.1" lon="121.1"><ele>150.0</ele><time>2023-01-02T10:00:00Z</time></trkpt>
                  <trkpt lat="24.2" lon="121.2"><ele>200.0</ele><time>2023-01-02T11:00:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val result = GPXParser.parse(xml.byteInputStream(Charsets.UTF_8))
        assertTrue(result.isSuccess)
        val track = result.getOrThrow()

        // Sequential stages are merged together
        assertEquals(4, track.points.size)
    }
}
