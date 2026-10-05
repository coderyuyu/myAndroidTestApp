package com.gpxami.app

import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxWaypoint
import com.gpxami.app.data.model.Wpt
import com.gpxami.app.data.model.WptRole
import com.gpxami.app.data.model.toWpt
import com.gpxami.app.data.repository.AdminDivisionResult
import com.gpxami.app.data.repository.GeocodingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class WaypointAdminLabelTest {

    @Test
    fun testWptRoleFlags() {
        val startWpt = Wpt(lat = 24.5, lon = 121.2, role = WptRole.START)
        assertTrue(startWpt.isStart)
        assertFalse(startWpt.isEnd)

        val endWpt = Wpt(lat = 24.6, lon = 121.3, role = WptRole.END)
        assertFalse(endWpt.isStart)
        assertTrue(endWpt.isEnd)

        val regularWpt = Wpt(lat = 24.55, lon = 121.25, role = WptRole.REGULAR)
        assertFalse(regularWpt.isStart)
        assertFalse(regularWpt.isEnd)
    }

    @Test
    fun testWptFormatDisplayLabelEndpoints() {
        // Start and End waypoints without custom name fallback to Traditional Chinese "起點" and "迄點"
        val startDefault = Wpt(lat = 24.4, lon = 121.0, role = WptRole.START)
        val endDefault = Wpt(lat = 24.3, lon = 121.1, role = WptRole.END)
        assertEquals("起點", startDefault.formatDisplayLabel())
        assertEquals("迄點", endDefault.formatDisplayLabel())

        // Start and End waypoints with custom WPT names display only label without prefix
        val startWithName = Wpt(lat = 24.4, lon = 121.0, name = "大霸尖山登山口", role = WptRole.START)
        val endWithName = Wpt(lat = 24.3, lon = 121.1, name = "慈母橋", role = WptRole.END)
        assertEquals("大霸尖山登山口", startWithName.formatDisplayLabel())
        assertEquals("慈母橋", endWithName.formatDisplayLabel())

        // Start and End waypoints where name already includes prefix - prefix is stripped
        val startWithPrefix = Wpt(lat = 24.4, lon = 121.0, name = "起點: 登山口", role = WptRole.START)
        val endWithPrefix = Wpt(lat = 24.3, lon = 121.1, name = "迄點: 慈母橋", role = WptRole.END)
        assertEquals("登山口", startWithPrefix.formatDisplayLabel())
        assertEquals("慈母橋", endWithPrefix.formatDisplayLabel())

        // Regular waypoints display WPT name or fallback to "航點"
        val regularWithName = Wpt(lat = 24.4, lon = 121.0, name = "九九山莊", role = WptRole.REGULAR)
        val regularDefault = Wpt(lat = 24.4, lon = 121.0, role = WptRole.REGULAR)
        assertEquals("九九山莊", regularWithName.formatDisplayLabel())
        assertEquals("航點", regularDefault.formatDisplayLabel())
    }

    @Test
    fun testFormatAdminLabelSubtitleSwitching() {
        val wpt = Wpt(
            lat = 24.4,
            lon = 121.0,
            role = WptRole.START,
            adminLevel1 = "臺灣",
            adminLevel2 = "苗栗縣"
        )

        // Switching between Level 1 and Level 1 + Level 2 for title subtitle
        assertEquals("臺灣", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("臺灣 · 苗栗縣", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_AND_2))

        val adminResult = AdminDivisionResult(level1 = "臺灣", level2 = "苗栗縣")
        assertEquals("臺灣", adminResult.format(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("臺灣 · 苗栗縣", adminResult.format(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testFormatAdminLabelDeduplication() {
        // If Level 1 and Level 2 are identical (e.g. City-state or duplicate tags)
        val wpt = Wpt(
            lat = 1.3521,
            lon = 103.8198,
            role = WptRole.START,
            adminLevel1 = "Singapore",
            adminLevel2 = "Singapore"
        )
        assertEquals("Singapore", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_AND_2))

        val adminResult = AdminDivisionResult("Singapore", "Singapore")
        assertEquals("Singapore", adminResult.format(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testFormatAdminLabelFallbackWhenLevel2Null() {
        val wpt = Wpt(
            lat = 24.0,
            lon = 121.0,
            role = WptRole.START,
            adminLevel1 = "臺灣",
            adminLevel2 = null
        )
        assertEquals("臺灣", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("臺灣", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testTraditionalChineseNormalization() {
        val wptSimplified = Wpt(
            lat = 24.0,
            lon = 121.0,
            name = "台湾阿里山登山口",
            role = WptRole.START,
            adminLevel1 = "台湾",
            adminLevel2 = "嘉义县"
        )
        assertEquals("臺灣阿里山登山口", wptSimplified.formatDisplayLabel())
        assertEquals("臺灣 · 嘉義縣", wptSimplified.formatAdminLabel(AdminDivisionConfig.LEVEL_1_AND_2))

        val resultSimplified = AdminDivisionResult("台湾", "嘉义县")
        assertEquals("臺灣 · 嘉義縣", resultSimplified.format(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testGpxWaypointToWptConversion() {
        val gpxWpt = GpxWaypoint(
            lat = 24.123,
            lon = 121.456,
            elevation = 1850.0,
            name = "水漾森林",
            desc = "Camp site"
        )
        val wpt = gpxWpt.toWpt(
            role = WptRole.START,
            adminLevel1 = "臺灣",
            adminLevel2 = "嘉義縣"
        )

        assertEquals(24.123, wpt.lat, 0.0001)
        assertEquals(121.456, wpt.lon, 0.0001)
        assertEquals(1850.0, wpt.elevation ?: 0.0, 0.1)
        assertEquals("水漾森林", wpt.name)
        assertEquals(WptRole.START, wpt.role)
        assertEquals("水漾森林", wpt.formatDisplayLabel())
        assertEquals("臺灣 · 嘉義縣", wpt.formatAdminLabel(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testLoopTrackCollisionDetection() {
        // Case A: Loop track where Start and End share the same coordinates
        val startScreenX = 500f
        val startScreenY = 400f
        val endScreenX = 500f
        val endScreenY = 400f
        val markerRadius = 12f

        val collisionDist = hypot(startScreenX - endScreenX, startScreenY - endScreenY)
        val threshold = markerRadius * 4f + 24f
        val isCollision = collisionDist < threshold

        assertTrue("Loop track with same coordinates should trigger collision offset", isCollision)

        // Case B: Point-to-point route with far separated endpoints
        val p2pEndX = 800f
        val p2pEndY = 900f
        val p2pDist = hypot(startScreenX - p2pEndX, startScreenY - p2pEndY)
        val isP2pCollision = p2pDist < threshold

        assertFalse("Point-to-point track should not trigger collision offset", isP2pCollision)
    }

    @Test
    fun testGeocodingRepositoryMockAndCache() = runBlocking {
        var networkCalls = 0
        val fakeRepo = object : GeocodingRepository {
            private val cache = mutableMapOf<String, AdminDivisionResult>()

            override suspend fun resolveAdminDivision(lat: Double, lon: Double): AdminDivisionResult {
                val key = "$lat,$lon"
                cache[key]?.let { return it }
                networkCalls++
                val result = AdminDivisionResult("臺灣", "南投縣")
                cache[key] = result
                return result
            }

            override fun resolveAdminDivisionSync(lat: Double, lon: Double): AdminDivisionResult {
                return cache["$lat,$lon"] ?: AdminDivisionResult("臺灣", null)
            }
        }

        // First call triggers network
        val res1 = fakeRepo.resolveAdminDivision(24.0, 121.0)
        assertEquals(1, networkCalls)
        assertEquals("臺灣", res1.level1)
        assertEquals("南投縣", res1.level2)

        // Second call with same coordinates uses cache
        val res2 = fakeRepo.resolveAdminDivision(24.0, 121.0)
        assertEquals(1, networkCalls)
        assertEquals("南投縣", res2.level2)

        // Sync returns cached value
        val syncRes = fakeRepo.resolveAdminDivisionSync(24.0, 121.0)
        assertEquals("南投縣", syncRes.level2)
    }
}
