package com.dir2gpx.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PolylineDecoderTest {

    @Test
    fun testDecodeStandardPolyline() {
        // Encoded polyline "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        // Corresponds to: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val points = PolylineDecoder.decode(encoded)

        assertEquals("Should decode 3 points", 3, points.size)

        assertTrue("First point lat ~38.5", abs(points[0].latitude - 38.5) < 0.001)
        assertTrue("First point lon ~-120.2", abs(points[0].longitude - (-120.2)) < 0.001)

        assertTrue("Second point lat ~40.7", abs(points[1].latitude - 40.7) < 0.001)
        assertTrue("Second point lon ~-120.95", abs(points[1].longitude - (-120.95)) < 0.001)

        assertTrue("Third point lat ~43.252", abs(points[2].latitude - 43.252) < 0.001)
        assertTrue("Third point lon ~-126.453", abs(points[2].longitude - (-126.453)) < 0.001)
    }

    @Test
    fun testDecodeEmptyString() {
        val points = PolylineDecoder.decode("")
        assertTrue("Empty string produces empty list", points.isEmpty())
    }

    @Test
    fun testDecodeSinglePoint() {
        // Encode (38.5, -120.2)
        val encoded = "_p~iF~ps|U"
        val points = PolylineDecoder.decode(encoded)
        assertEquals(1, points.size)
        assertTrue(abs(points[0].latitude - 38.5) < 0.001)
        assertTrue(abs(points[0].longitude - (-120.2)) < 0.001)
    }
}
