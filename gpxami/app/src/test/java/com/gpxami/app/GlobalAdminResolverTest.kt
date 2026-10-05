package com.gpxami.app

import android.location.Address
import com.gpxami.app.data.geocoder.GlobalAdminResolver
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.repository.CoordinateLruCache
import com.gpxami.app.model.AdminDivision
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Global verification test suite for [GlobalAdminResolver].
 *
 * Verifies:
 * 1. Coordinate Integrity & Lat/Lon Inversion Defense.
 * 2. Worldwide Landmark Cascades:
 *    - Taipei Station (25.0474, 121.5181) -> Level 1: 臺北市 / 臺灣, Level 2: 中正區.
 *    - Tokyo Station (35.6812, 139.7671) -> Level 1: 東京都, Level 2: 千代田区.
 *    - Times Square NY (40.7580, -73.9855) -> Level 1: New York, Level 2: New York County / Manhattan.
 *    - Remote Pacific Ocean (0.0, -160.0) -> Correctly identified as High Seas / Open Waters.
 * 3. High Seas classification strictly restricted to genuine open ocean, never onshore coordinates.
 * 4. Coordinate LRU cache eviction and thread-safety.
 * 5. Level 1 & Level 2 subtitle formatting with AdminDivisionConfig.
 */
class GlobalAdminResolverTest {

    private class MockAddress(
        private val cCode: String? = null,
        private val cName: String? = null,
        private val admin: String? = null,
        private val subAdmin: String? = null,
        private val local: String? = null,
        private val subLocal: String? = null
    ) : Address(Locale.US) {
        override fun getCountryCode(): String? = cCode
        override fun getCountryName(): String? = cName
        override fun getAdminArea(): String? = admin
        override fun getSubAdminArea(): String? = subAdmin
        override fun getLocality(): String? = local
        override fun getSubLocality(): String? = subLocal
    }

    @Test
    fun testCoordinateSanitizationAndInversionDefense() {
        // 1. Standard valid coordinates
        val valid = GlobalAdminResolver.sanitizeCoordinates(25.0474, 121.5181)
        assertNotNull(valid)
        assertEquals(25.0474, valid!!.first, 0.0001)
        assertEquals(121.5181, valid.second, 0.0001)

        // 2. Inverted coordinates (GeoJSON [lon, lat] passed to lat/lon)
        val inverted = GlobalAdminResolver.sanitizeCoordinates(121.5181, 25.0474)
        assertNotNull(inverted)
        // Automatically detected that 121.5181 > 90 and swapped to valid (lat=25.0474, lon=121.5181)
        assertEquals(25.0474, inverted!!.first, 0.0001)
        assertEquals(121.5181, inverted.second, 0.0001)

        // 3. Completely out of range coordinates
        val invalid = GlobalAdminResolver.sanitizeCoordinates(195.0, 250.0)
        assertNull(invalid)

        // 4. Clean coordinate formatting
        val formattedNorthEast = GlobalAdminResolver.formatCoordinates(25.047, 121.518)
        assertEquals("25.047°N, 121.518°E", formattedNorthEast)

        val formattedSouthWest = GlobalAdminResolver.formatCoordinates(-33.850, -151.210)
        assertEquals("33.850°S, 151.210°W", formattedSouthWest)
    }

    @Test
    fun testTaipeiStationOnshoreNeverHighSeas() {
        val lat = 25.0473969
        val lon = 121.5180950

        // Case A: Geocoder returns standard Taiwan address
        val mockAddress = MockAddress(
            cCode = "TW",
            cName = "臺灣",
            admin = "臺北市",
            subAdmin = "中正區",
            local = "中正區"
        )
        val resolved = GlobalAdminResolver.extractAdminDivision(mockAddress, lat, lon, Locale.TRADITIONAL_CHINESE)
        assertFalse("Taipei Station must NEVER be classified as High Seas", resolved.isHighSeas)
        assertEquals("臺北市", resolved.level1Name)
        assertEquals("中正區", resolved.level2Name)
        assertEquals("臺北市", resolved.format(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("臺北市 · 中正區", resolved.format(AdminDivisionConfig.LEVEL_1_AND_2))

        // Case B: Geocoder returns adminArea = "Taiwan", locality = "Taipei City", subLocality = "Zhongzheng"
        val mockAltAddress = MockAddress(
            cCode = "TW",
            cName = "Taiwan",
            admin = "Taiwan",
            subAdmin = null,
            local = "臺北市",
            subLocal = "中正區"
        )
        val resolvedAlt = GlobalAdminResolver.extractAdminDivision(mockAltAddress, lat, lon, Locale.TRADITIONAL_CHINESE)
        assertFalse(resolvedAlt.isHighSeas)
        assertEquals("臺北市", resolvedAlt.level1Name)
        assertEquals("中正區", resolvedAlt.level2Name)

        // Case C: Offline fallback / Geocoder returns null or timeout
        val fallback = GlobalAdminResolver.resolveFallback(lat, lon, Locale.TRADITIONAL_CHINESE)
        assertFalse("Taipei Station offline fallback must NEVER be High Seas", fallback.isHighSeas)
        assertEquals("臺北市", fallback.level1Name)
        assertEquals("中正區", fallback.level2Name)
        assertEquals("臺北市 · 中正區", fallback.format(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testTokyoStationResolvedCorrectly() {
        val lat = 35.6812
        val lon = 139.7671

        // Case A: Online Geocoder response
        val mockAddress = MockAddress(
            cCode = "JP",
            cName = "Japan",
            admin = "東京都",
            subAdmin = null,
            local = "千代田区"
        )
        val resolved = GlobalAdminResolver.extractAdminDivision(mockAddress, lat, lon, Locale.JAPAN)
        assertFalse(resolved.isHighSeas)
        assertEquals("東京都", resolved.level1Name)
        assertEquals("千代田区", resolved.level2Name)
        assertEquals("東京都", resolved.format(AdminDivisionConfig.LEVEL_1_ONLY, Locale.JAPAN))
        assertEquals("東京都 · 千代田区", resolved.format(AdminDivisionConfig.LEVEL_1_AND_2, Locale.JAPAN))
        // When localized for Traditional Chinese users
        assertEquals("東京都 · 千代田區", resolved.format(AdminDivisionConfig.LEVEL_1_AND_2, Locale.TRADITIONAL_CHINESE))

        // Case B: Offline fallback
        val fallback = GlobalAdminResolver.resolveFallback(lat, lon, Locale.JAPAN)
        assertFalse(fallback.isHighSeas)
        assertEquals("東京都", fallback.level1Name)
        assertEquals("千代田区", fallback.level2Name)
    }

    @Test
    fun testTimesSquareNewYorkResolvedCorrectly() {
        val lat = 40.7580
        val lon = -73.9855

        // Case A: Geocoder returns State and County / Borough
        val mockAddress = MockAddress(
            cCode = "US",
            cName = "United States",
            admin = "New York",
            subAdmin = "New York County",
            local = "New York"
        )
        val resolved = GlobalAdminResolver.extractAdminDivision(mockAddress, lat, lon, Locale.US)
        assertFalse(resolved.isHighSeas)
        assertEquals("New York", resolved.level1Name)
        // Level 2 avoids duplicating Level 1 ("New York") by selecting subAdmin ("New York County")
        assertEquals("New York County", resolved.level2Name)
        assertEquals("New York", resolved.format(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("New York · New York County", resolved.format(AdminDivisionConfig.LEVEL_1_AND_2))

        // Case B: Offline fallback
        val fallback = GlobalAdminResolver.resolveFallback(lat, lon, Locale.US)
        assertFalse(fallback.isHighSeas)
        assertEquals("New York", fallback.level1Name)
        assertEquals("New York County", fallback.level2Name)
    }

    @Test
    fun testRemotePacificOceanIdentifiedAsHighSeas() {
        val lat = 0.0
        val lon = -160.0

        // 1. High seas determination logic
        assertTrue("Remote Pacific (0.0, -160.0) is strictly open ocean", GlobalAdminResolver.isCoordinateInOpenOcean(lat, lon))

        // 2. Fallback resolution produces High Seas domain model
        val division = GlobalAdminResolver.resolveFallback(lat, lon)
        assertTrue(division.isHighSeas)
        assertEquals("公海 / 國際水域", division.level1Name)
        assertNull(division.level2Name)
        assertEquals("公海 / 國際水域", division.format(AdminDivisionConfig.LEVEL_1_ONLY))
        assertEquals("公海 / 國際水域", division.format(AdminDivisionConfig.LEVEL_1_AND_2))

        // 3. Geocoder returning completely empty address at remote ocean
        val emptyAddress = MockAddress()
        val extracted = GlobalAdminResolver.extractAdminDivision(emptyAddress, lat, lon)
        assertTrue(extracted.isHighSeas)
        assertEquals("公海 / 國際水域", extracted.format())
    }

    @Test
    fun testOnshoreWildernessCoordinatesFallbackNotHighSeas() {
        // Point in Sahara Desert or wilderness landmass (e.g. 24.5°N, 10.0°E in Algeria)
        val lat = 24.5
        val lon = 10.0

        assertFalse("Sahara desert is inland, never High Seas", GlobalAdminResolver.isCoordinateInOpenOcean(lat, lon))

        val division = GlobalAdminResolver.resolveFallback(lat, lon)
        assertFalse(division.isHighSeas)
        assertEquals("24.500°N, 10.000°E", division.level1Name)
        assertEquals("24.500°N, 10.000°E", division.format())
    }

    @Test
    fun testCityStatesCascade() {
        // Singapore
        val sgAddress = MockAddress(
            cCode = "SG",
            cName = "Singapore",
            admin = null,
            subAdmin = null,
            local = "Singapore"
        )
        val sg = GlobalAdminResolver.extractAdminDivision(sgAddress, 1.3521, 103.8198)
        assertFalse(sg.isHighSeas)
        assertEquals("Singapore", sg.level1Name)

        // Monaco
        val mcAddress = MockAddress(
            cCode = "MC",
            cName = "Monaco",
            admin = null,
            subAdmin = null,
            local = "Monaco"
        )
        val mc = GlobalAdminResolver.extractAdminDivision(mcAddress, 43.7384, 7.4246)
        assertFalse(mc.isHighSeas)
        assertEquals("Monaco", mc.level1Name)
    }

    @Test
    fun testLevel2CascadeAvoidsDuplicatingLevel1() {
        // When subAdminArea equals adminArea ("Paris"), cascade to locality ("1st Arrondissement")
        val address = MockAddress(
            cCode = "FR",
            cName = "France",
            admin = "Paris",
            subAdmin = "Paris",
            local = "1st Arrondissement"
        )
        val division = GlobalAdminResolver.extractAdminDivision(address, 48.8606, 2.3376)
        assertEquals("Paris", division.level1Name)
        assertEquals("1st Arrondissement", division.level2Name)
        assertEquals("Paris · 1st Arrondissement", division.format(AdminDivisionConfig.LEVEL_1_AND_2))
    }

    @Test
    fun testCoordinateLruCacheEvictionAndThreadSafety() {
        val cache = CoordinateLruCache(maxSize = 3)

        val div1 = AdminDivision(level1Name = "Loc1")
        val div2 = AdminDivision(level1Name = "Loc2")
        val div3 = AdminDivision(level1Name = "Loc3")
        val div4 = AdminDivision(level1Name = "Loc4")

        cache.put("1", div1)
        cache.put("2", div2)
        cache.put("3", div3)
        assertEquals(3, cache.size())

        // Access "1" to mark it recently used (order is now: 2, 3, 1)
        assertNotNull(cache.get("1"))

        // Insert "4", eldest entry "2" should be evicted
        cache.put("4", div4)
        assertEquals(3, cache.size())
        assertNull("Key '2' should be evicted by LRU policy", cache.get("2"))
        assertNotNull(cache.get("1"))
        assertNotNull(cache.get("3"))
        assertNotNull(cache.get("4"))
    }
}
