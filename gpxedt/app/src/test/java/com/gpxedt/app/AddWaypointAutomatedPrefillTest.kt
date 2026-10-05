package com.gpxedt.app

import com.gpxedt.app.data.repository.GeocodingRepository
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.parser.GpxSerializer
import com.gpxedt.app.ui.waypoint.AddWaypointViewModel
import com.gpxedt.app.util.GpxDateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * Unit tests verifying automated information fill for Add Waypoint:
 * 1. Timestamp synchronization (GPX TrackPoint time inheritance & fallback).
 * 2. Asynchronous reverse geocoding prefill and loading state.
 * 3. Non-overwriting behavior when user inputs custom name (dirty state).
 * 4. Graceful degradation on network / geocoding failures.
 * 5. Strict ISO-8601 UTC formatting for GPX serialization.
 */
class AddWaypointAutomatedPrefillTest {

    private class FakeGeocodingRepository(
        private val result: String? = null,
        private val delayMs: Long = 0L,
        private val throwError: Boolean = false
    ) : GeocodingRepository {
        override suspend fun reverseGeocode(lat: Double, lon: Double): String? {
            if (delayMs > 0) delay(delayMs)
            if (throwError) throw IOException("Simulated network failure")
            return result
        }
    }

    @Test
    fun testIso8601UtcTimestampFormat() {
        val testInstant = Instant.parse("2026-10-03T05:56:00Z")
        val formatted = GpxDateTimeFormatter.formatIsoUtc(testInstant)
        assertEquals("2026-10-03T05:56:00Z", formatted)

        // Verify GPX Serializer produces valid <time> node in UTC
        val wpt = GpxWaypoint(
            lat = 25.1764,
            lon = 121.5543,
            name = "七星山主峰",
            time = testInstant,
            ele = 1120.0
        )
        val gpxData = GpxData(waypoints = listOf(wpt))
        val xml = GpxSerializer.serializeToString(gpxData)

        assertTrue(xml.contains("<wpt lat=\"25.1764000\" lon=\"121.5543000\">"))
        assertTrue(xml.contains("<ele>1120.00</ele>"))
        assertTrue(xml.contains("<time>2026-10-03T05:56:00Z</time>"))
        assertTrue(xml.contains("<name>七星山主峰</name>"))
    }

    @Test
    fun testTrackPointTimeInheritedWhenAvailable() = runBlocking {
        val trackTime = Instant.parse("2026-07-20T14:30:15Z")
        val trackPoint = TrackPoint(
            lat = 25.0330,
            lon = 121.5654,
            ele = 35.0,
            time = trackTime
        )

        val viewModel = AddWaypointViewModel(
            repository = FakeGeocodingRepository("Taipei 101"),
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel.initialize(
            lat = trackPoint.lat,
            lon = trackPoint.lon,
            inheritedTrackPoint = trackPoint
        )

        assertEquals(trackTime, viewModel.selectedTime.first())
        assertTrue(viewModel.isTimeInheritedFromTrack.first())
        assertEquals(35.0, viewModel.elevationState.first())
    }

    @Test
    fun testTimestampFallbackToCurrentTimeWhenTrackPointHasNoTime() = runBlocking {
        val before = Instant.now().minusSeconds(1)
        val trackPointWithoutTime = TrackPoint(
            lat = 25.0330,
            lon = 121.5654,
            ele = null,
            time = null
        )

        val viewModel = AddWaypointViewModel(
            repository = FakeGeocodingRepository("Taipei 101"),
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel.initialize(
            lat = trackPointWithoutTime.lat,
            lon = trackPointWithoutTime.lon,
            inheritedTrackPoint = trackPointWithoutTime
        )

        val selected = viewModel.selectedTime.first()
        val after = Instant.now().plusSeconds(1)

        assertFalse(viewModel.isTimeInheritedFromTrack.first())
        assertTrue(selected.isAfter(before) || selected == before)
        assertTrue(selected.isBefore(after) || selected == after)
    }

    @Test
    fun testTimestampFallbackForBlankMapClick() = runBlocking {
        val before = Instant.now().minusSeconds(1)
        val viewModel = AddWaypointViewModel(
            repository = FakeGeocodingRepository("Blank Spot"),
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel.initialize(
            lat = 24.5,
            lon = 121.5,
            inheritedTrackPoint = null,
            inheritedTime = null
        )

        val selected = viewModel.selectedTime.first()
        val after = Instant.now().plusSeconds(1)

        assertFalse(viewModel.isTimeInheritedFromTrack.first())
        assertTrue(selected.isAfter(before) || selected == before)
        assertTrue(selected.isBefore(after) || selected == after)
    }

    @Test
    fun testReverseGeocodingPrefillsNameSuccessfully() = runBlocking {
        val fakeRepo = FakeGeocodingRepository(result = "七星山主峰 (北投區)")
        val viewModel = AddWaypointViewModel(
            repository = fakeRepo,
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel.initialize(
            lat = 25.1764,
            lon = 121.5543
        )

        assertEquals("七星山主峰 (北投區)", viewModel.nameState.first())
        assertFalse(viewModel.isGeocodingLoading.first())
        assertFalse(viewModel.isNameError.first())
    }

    @Test
    fun testUserManualInputNotOverwrittenByReverseGeocoding() = runBlocking {
        val fakeRepo = FakeGeocodingRepository(result = "板橋火車站", delayMs = 50L)
        val viewModel = AddWaypointViewModel(
            repository = fakeRepo,
            ioDispatcher = Dispatchers.Default
        )

        // Initialize with coordinates
        viewModel.initialize(
            lat = 25.0135,
            lon = 121.4640
        )

        // User types immediately before geocoding returns
        viewModel.onNameChanged("My Custom Shelter")

        // Wait for geocoding coroutine to complete
        delay(120L)

        // Must still retain user's typed name
        assertEquals("My Custom Shelter", viewModel.nameState.first())
    }

    @Test
    fun testReverseGeocodingFailureGracefulFallback() = runBlocking {
        val errorRepo = FakeGeocodingRepository(throwError = true)
        val viewModel = AddWaypointViewModel(
            repository = errorRepo,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Should not crash or throw unhandled exceptions
        viewModel.initialize(lat = 25.0, lon = 121.0)

        // Name remains empty, loading resets to false
        assertEquals("", viewModel.nameState.first())
        assertFalse(viewModel.isGeocodingLoading.first())
    }

    @Test
    fun testBuildWaypointValidationAndCanonicalSymbol() = runBlocking {
        val viewModel = AddWaypointViewModel(
            repository = FakeGeocodingRepository("WPT-01"),
            ioDispatcher = Dispatchers.Unconfined
        )

        viewModel.initialize(
            lat = 25.0330,
            lon = 121.5654,
            inheritedTime = Instant.parse("2026-10-03T10:00:00Z"),
            isTimeInheritedFromTrack = true
        )

        // Blank name should fail validation
        viewModel.onNameChanged("   ")
        assertNull(viewModel.buildWaypointIfValid())
        assertTrue(viewModel.isNameError.first())

        // Set valid name and non-standard symbol
        viewModel.onNameChanged("Taipei 101 Base")
        viewModel.onSymbolChanged("Flag, Yellow")

        val wpt = viewModel.buildWaypointIfValid()
        assertNotNull(wpt)
        assertEquals("Taipei 101 Base", wpt!!.name)
        assertEquals(GpxWaypoint.SYM_FLAG_YELLOW, wpt.sym)
        assertEquals(25.0330, wpt.lat, 0.0001)
        assertEquals(121.5654, wpt.lon, 0.0001)
        assertEquals(Instant.parse("2026-10-03T10:00:00Z"), wpt.time)
    }
}
