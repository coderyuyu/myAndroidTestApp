package com.gpxedt.app.util

import org.junit.Assert.*
import org.junit.Test

class PhotoExifReaderTest {

    @Test
    fun testParseGpsCoordinate_dmsFormat() {
        // "25/1, 14/1, 2345/100" with ref "N" -> 25 + 14/60 + 23.45/3600 = 25.239847
        val lat = PhotoExifReader.parseGpsCoordinate("25/1, 14/1, 2345/100", "N")
        assertNotNull(lat)
        assertEquals(25.239847, lat!!, 0.0001)

        // "121/1, 30/1, 4567/100" with ref "E" -> 121 + 30/60 + 45.67/3600 = 121.512686
        val lon = PhotoExifReader.parseGpsCoordinate("121/1, 30/1, 4567/100", "E")
        assertNotNull(lon)
        assertEquals(121.512686, lon!!, 0.0001)
    }

    @Test
    fun testParseGpsCoordinate_southernAndWesternHemisphere() {
        // South latitude should be negative
        val sLat = PhotoExifReader.parseGpsCoordinate("33/1, 51/1, 0/1", "S")
        assertNotNull(sLat)
        assertTrue(sLat!! < 0)
        assertEquals(-33.85, sLat, 0.01)

        // West longitude should be negative
        val wLon = PhotoExifReader.parseGpsCoordinate("151/1, 12/1, 0/1", "W")
        assertNotNull(wLon)
        assertTrue(wLon!! < 0)
        assertEquals(-151.2, wLon, 0.01)
    }

    @Test
    fun testParseGpsCoordinate_decimalFormat() {
        val dec = PhotoExifReader.parseGpsCoordinate("25.123456", "N")
        assertNotNull(dec)
        assertEquals(25.123456, dec!!, 0.000001)
    }

    @Test
    fun testParseGpsCoordinate_invalid() {
        assertNull(PhotoExifReader.parseGpsCoordinate("", "N"))
        assertNull(PhotoExifReader.parseGpsCoordinate(null, "N"))
        assertNull(PhotoExifReader.parseGpsCoordinate("invalid/data", "N"))
    }
}
