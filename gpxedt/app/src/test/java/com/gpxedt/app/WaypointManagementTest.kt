package com.gpxedt.app

import com.gpxedt.app.data.repository.GpxRepositoryImpl
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.WaypointSortOrder
import com.gpxedt.app.parser.GpxParser
import com.gpxedt.app.parser.GpxSerializer
import com.gpxedt.app.ui.waypoint.WaypointViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

class WaypointManagementTest {

    @Test
    fun testCanonicalFlagSymbolsAndNormalization() {
        assertEquals("Flag, Red", GpxWaypoint.SYM_FLAG_RED)
        assertEquals("Flag, Yellow", GpxWaypoint.SYM_FLAG_YELLOW)
        assertEquals("Flag, Green", GpxWaypoint.SYM_FLAG_GREEN)

        assertEquals(3, GpxWaypoint.STANDARD_FLAG_SYMBOLS.size)
        assertTrue(GpxWaypoint.STANDARD_FLAG_SYMBOLS.contains("Flag, Red"))
        assertTrue(GpxWaypoint.STANDARD_FLAG_SYMBOLS.contains("Flag, Yellow"))
        assertTrue(GpxWaypoint.STANDARD_FLAG_SYMBOLS.contains("Flag, Green"))

        // Exact match
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol("Flag, Red"))
        assertEquals("Flag, Yellow", GpxWaypoint.normalizeSymbol("Flag, Yellow"))
        assertEquals("Flag, Green", GpxWaypoint.normalizeSymbol("Flag, Green"))

        // Case-insensitive / partial match
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol("red"))
        assertEquals("Flag, Yellow", GpxWaypoint.normalizeSymbol("flag, yellow"))
        assertEquals("Flag, Green", GpxWaypoint.normalizeSymbol("GREEN"))

        // Fallback for null, empty or legacy symbols
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol(null))
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol(""))
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol("Summit"))
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol("Campground"))
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol("Danger Area"))
    }

    @Test
    fun testWaypointSortingManual() {
        val w1 = GpxWaypoint(24.0, 121.0, "Zeta", time = Instant.parse("2026-09-01T10:00:00Z"))
        val w2 = GpxWaypoint(24.1, 121.1, "Alpha", time = Instant.parse("2026-09-03T10:00:00Z"))
        val w3 = GpxWaypoint(24.2, 121.2, "Beta", time = Instant.parse("2026-09-02T10:00:00Z"))

        val original = listOf(w1, w2, w3)
        val sorted = WaypointViewModel.sortWaypointList(original, WaypointSortOrder.MANUAL)

        assertEquals(listOf(w1, w2, w3), sorted)
    }

    @Test
    fun testWaypointSortingByNameAsc() {
        val w1 = GpxWaypoint(24.0, 121.0, "zeta")
        val w2 = GpxWaypoint(24.1, 121.1, "Alpha")
        val w3 = GpxWaypoint(24.2, 121.2, "beta")
        val w4 = GpxWaypoint(24.3, 121.3, "ALPHA 2")

        val sorted = WaypointViewModel.sortWaypointList(listOf(w1, w2, w3, w4), WaypointSortOrder.NAME_ASC)

        assertEquals("Alpha", sorted[0].name)
        assertEquals("ALPHA 2", sorted[1].name)
        assertEquals("beta", sorted[2].name)
        assertEquals("zeta", sorted[3].name)
    }

    @Test
    fun testWaypointSortingByTimeDesc() {
        val wOld = GpxWaypoint(24.0, 121.0, "Old", time = Instant.parse("2026-09-01T08:00:00Z"))
        val wNew = GpxWaypoint(24.1, 121.1, "New", time = Instant.parse("2026-09-03T15:00:00Z"))
        val wMid = GpxWaypoint(24.2, 121.2, "Mid", time = Instant.parse("2026-09-02T12:00:00Z"))
        val wNoTime = GpxWaypoint(24.3, 121.3, "NoTime", time = null)

        val sorted = WaypointViewModel.sortWaypointList(listOf(wOld, wNoTime, wNew, wMid), WaypointSortOrder.TIME_DESC)

        assertEquals("New", sorted[0].name)
        assertEquals("Mid", sorted[1].name)
        assertEquals("Old", sorted[2].name)
        assertEquals("NoTime", sorted[3].name)
    }

    @Test
    fun testSortingBoundaryConditions() {
        // Empty list
        val empty = emptyList<GpxWaypoint>()
        val sortedEmpty = WaypointViewModel.sortWaypointList(empty, WaypointSortOrder.NAME_ASC)
        assertTrue(sortedEmpty.isEmpty())

        // Single-item list
        val single = listOf(GpxWaypoint(25.0, 121.0, "Solo"))
        val sortedSingle = WaypointViewModel.sortWaypointList(single, WaypointSortOrder.TIME_DESC)
        assertEquals(1, sortedSingle.size)
        assertEquals("Solo", sortedSingle[0].name)
    }

    @Test
    fun testWaypointViewModelReactiveSorting() = runBlocking {
        val vm = WaypointViewModel()

        val w1 = GpxWaypoint(25.0, 121.0, "Zeta")
        val w2 = GpxWaypoint(25.1, 121.1, "Alpha")

        vm.setWaypoints(listOf(w1, w2))
        vm.setSortOrder(WaypointSortOrder.NAME_ASC)

        // Verify sorted result reflects NAME_ASC
        val currentSorted = WaypointViewModel.sortWaypointList(vm.waypoints.value, vm.sortOrder.value)
        assertEquals("Alpha", currentSorted[0].name)
        assertEquals("Zeta", currentSorted[1].name)

        // Switch to MANUAL
        vm.setSortOrder(WaypointSortOrder.MANUAL)
        val manualSorted = WaypointViewModel.sortWaypointList(vm.waypoints.value, vm.sortOrder.value)
        assertEquals("Zeta", manualSorted[0].name)
        assertEquals("Alpha", manualSorted[1].name)
    }

    @Test
    fun testGpxSerializationWithFlagSymTags() {
        val waypoints = listOf(
            GpxWaypoint(25.033, 121.565, "Wpt Red", sym = "Flag, Red"),
            GpxWaypoint(25.034, 121.566, "Wpt Yellow", sym = "Flag, Yellow"),
            GpxWaypoint(25.035, 121.567, "Wpt Green", sym = "Flag, Green")
        )

        val gpxData = GpxData(name = "Symbol Export Test", waypoints = waypoints)
        val xml = GpxSerializer.serializeToString(gpxData)

        assertTrue("Must contain <sym>Flag, Red</sym>", xml.contains("<sym>Flag, Red</sym>"))
        assertTrue("Must contain <sym>Flag, Yellow</sym>", xml.contains("<sym>Flag, Yellow</sym>"))
        assertTrue("Must contain <sym>Flag, Green</sym>", xml.contains("<sym>Flag, Green</sym>"))

        // Re-parse and verify round-trip integrity
        val reparsed = GpxParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(3, reparsed.waypoints.size)
        assertEquals("Flag, Red", reparsed.waypoints[0].sym)
        assertEquals("Flag, Yellow", reparsed.waypoints[1].sym)
        assertEquals("Flag, Green", reparsed.waypoints[2].sym)
    }

    @Test
    fun testGpxRepositoryFallbackForLegacySymbols() = runBlocking {
        val legacyXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Legacy" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="24.5" lon="121.2">
                <name>Old Summit</name>
                <sym>Summit</sym>
              </wpt>
              <wpt lat="24.6" lon="121.3">
                <name>No Symbol Wpt</name>
              </wpt>
              <wpt lat="24.7" lon="121.4">
                <name>Valid Green</name>
                <sym>Flag, Green</sym>
              </wpt>
            </gpx>
        """.trimIndent()

        val repo = GpxRepositoryImpl()
        val parsed = repo.parseGpx(ByteArrayInputStream(legacyXml.toByteArray(Charsets.UTF_8)))

        assertEquals(3, parsed.waypoints.size)
        assertEquals("Old Summit", parsed.waypoints[0].name)
        // Missing symbol is given default
        assertEquals("Flag, Red", parsed.waypoints[1].sym)
        // Valid green preserved
        assertEquals("Flag, Green", parsed.waypoints[2].sym)

        // When normalizing for UI, legacy symbol gracefully normalizes to Flag, Red
        assertEquals("Flag, Red", GpxWaypoint.normalizeSymbol(parsed.waypoints[0].sym))
    }
}
