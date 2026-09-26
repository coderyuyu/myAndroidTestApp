package com.dir2gpx.service

import com.dir2gpx.model.PointType
import com.dir2gpx.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OsrmRoutingServiceTest {

    @Test
    fun testBuildUrlFormatting() {
        val waypoints = listOf(
            RoutePoint(latitude = 52.517037, longitude = 13.388860),
            RoutePoint(latitude = 52.529407, longitude = 13.397634)
        )

        val url = OsrmRoutingService.buildUrl(waypoints)

        assertTrue(
            "URL should start with OSRM driving endpoint",
            url.startsWith("https://router.project-osrm.org/route/v1/driving/")
        )
        // Lon,Lat ordering check (13.388860,52.517037;13.397634,52.529407)
        assertTrue(
            "URL must contain longitude,latitude coordinate pair",
            url.contains("13.388860,52.517037;13.397634,52.529407")
        )
        assertTrue(
            "URL should request full polyline overview",
            url.contains("overview=full&geometries=polyline")
        )
    }

    @Test
    fun testParseValidOsrmResponse() {
        // Valid mock OSRM response
        val mockJson = """
        {
            "code": "Ok",
            "routes": [
                {
                    "geometry": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                    "distance": 1888.5,
                    "duration": 260.4
                }
            ],
            "waypoints": []
        }
        """.trimIndent()

        val result = OsrmRoutingService.parseResponse(mockJson)

        assertNotNull(result)
        assertEquals("Distance matches", 1888.5, result.distanceMeters, 0.01)
        assertEquals("Duration matches", 260.4, result.durationSeconds, 0.01)
        assertEquals("Decodes 3 points from geometry", 3, result.trackPoints.size)
        assertEquals("Point type is TRACK", PointType.TRACK, result.trackPoints[0].type)
    }

    @Test(expected = IOException::class)
    fun testParseErrorResponseThrows() {
        val errorJson = """
        {
            "code": "NoRoute",
            "message": "Impossible route between points"
        }
        """.trimIndent()

        OsrmRoutingService.parseResponse(errorJson)
    }

    @Test(expected = IOException::class)
    fun testParseEmptyRoutesThrows() {
        val emptyRoutesJson = """
        {
            "code": "Ok",
            "routes": []
        }
        """.trimIndent()

        OsrmRoutingService.parseResponse(emptyRoutesJson)
    }
}
