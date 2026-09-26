package com.gpxedt.app

import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.parser.GpxParser
import com.gpxedt.app.parser.GpxSerializer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant

class GpxParserSerializerTest {

    @Test
    fun testParseGpxXml() {
        val sampleXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Sample Trail</name>
              </metadata>
              <wpt lat="25.0330" lon="121.5654">
                <name>Taipei 101</name>
                <desc>Landmark</desc>
                <sym>Flag</sym>
                <ele>35.0</ele>
              </wpt>
              <trk>
                <name>Hiking Route</name>
                <trkseg>
                  <trkpt lat="25.0300" lon="121.5600">
                    <ele>20.5</ele>
                    <time>2026-09-20T08:00:00Z</time>
                  </trkpt>
                  <trkpt lat="25.0310" lon="121.5620">
                    <ele>25.0</ele>
                    <time>2026-09-20T08:05:00Z</time>
                  </trkpt>
                  <trkpt lat="25.0320" lon="121.5640">
                    <ele>30.2</ele>
                    <time>2026-09-20T08:10:00Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxParser.parse(ByteArrayInputStream(sampleXml.toByteArray(Charsets.UTF_8)))

        assertNotNull(parsed)
        assertEquals("Hiking Route", parsed.name)
        assertEquals(3, parsed.trackPoints.size)
        assertEquals(1, parsed.waypoints.size)

        val pt0 = parsed.trackPoints[0]
        assertEquals(25.0300, pt0.lat, 0.0001)
        assertEquals(121.5600, pt0.lon, 0.0001)
        assertEquals(20.5, pt0.ele!!, 0.01)
        assertEquals(Instant.parse("2026-09-20T08:00:00Z"), pt0.time)

        val wpt = parsed.waypoints[0]
        assertEquals("Taipei 101", wpt.name)
        assertEquals("Landmark", wpt.desc)
        assertEquals("Flag", wpt.sym)
        assertEquals(25.0330, wpt.lat, 0.0001)
        assertEquals(121.5654, wpt.lon, 0.0001)
    }

    @Test
    fun testSerializationRoundTrip() {
        val originalData = GpxData(
            name = "RoundTrip Test",
            trackPoints = listOf(
                TrackPoint(24.1234, 120.5678, 100.0, Instant.parse("2026-09-20T10:00:00Z")),
                TrackPoint(24.1245, 120.5689, 105.5, Instant.parse("2026-09-20T10:02:00Z")),
                TrackPoint(24.1256, 120.5700, 110.0, Instant.parse("2026-09-20T10:04:00Z"))
            ),
            waypoints = listOf(
                Waypoint(24.1250, 120.5690, "Rest Stop", "Water station", "Camp")
            )
        )

        val xmlString = GpxSerializer.serializeToString(originalData)
        assertTrue(xmlString.contains("<trkpt"))
        assertTrue(xmlString.contains("<wpt"))
        assertTrue(xmlString.contains("Rest Stop"))

        val reParsed = GpxParser.parse(ByteArrayInputStream(xmlString.toByteArray(Charsets.UTF_8)))

        assertEquals(originalData.trackPoints.size, reParsed.trackPoints.size)
        assertEquals(originalData.waypoints.size, reParsed.waypoints.size)
        assertEquals(originalData.waypoints[0].name, reParsed.waypoints[0].name)
        assertEquals(originalData.waypoints[0].desc, reParsed.waypoints[0].desc)
        assertEquals(originalData.waypoints[0].sym, reParsed.waypoints[0].sym)

        for (i in originalData.trackPoints.indices) {
            val orig = originalData.trackPoints[i]
            val re = reParsed.trackPoints[i]
            assertEquals(orig.lat, re.lat, 0.00001)
            assertEquals(orig.lon, re.lon, 0.00001)
            assertEquals(orig.ele!!, re.ele!!, 0.01)
            assertEquals(orig.time, re.time)
        }
    }
}
