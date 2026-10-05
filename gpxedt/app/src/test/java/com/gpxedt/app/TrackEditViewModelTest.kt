package com.gpxedt.app

import com.gpxedt.app.data.preferences.UserPreferencesRepository
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.ui.map.TrackEditViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class TrackEditViewModelTest {

    private class FakeUserPreferencesRepository(initialShow: Boolean = true) : UserPreferencesRepository {
        private val _flow = MutableStateFlow(initialShow)
        override val showTrackpointsFlow: Flow<Boolean> = _flow.asStateFlow()

        override suspend fun setShowTrackpoints(show: Boolean) {
            _flow.value = show
        }

        override fun isShowTrackpoints(): Boolean = _flow.value
    }

    private lateinit var prefsRepo: FakeUserPreferencesRepository
    private lateinit var sampleData: GpxData
    private lateinit var viewModel: TrackEditViewModel

    @Before
    fun setUp() {
        prefsRepo = FakeUserPreferencesRepository(initialShow = true)

        val points = listOf(
            TrackPoint(25.0, 121.0, ele = 100.0, time = Instant.parse("2026-10-01T10:00:00Z")),
            TrackPoint(25.0, 121.2, ele = 150.0, time = Instant.parse("2026-10-01T10:10:00Z")),
            TrackPoint(25.0, 121.4, ele = 200.0, time = Instant.parse("2026-10-01T10:20:00Z"))
        )
        sampleData = GpxData(name = "Test Route", trackPoints = points)
        viewModel = TrackEditViewModel(
            preferencesRepository = prefsRepo,
            initialData = sampleData,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun testShowTrackpointsPersistenceFlow() = runBlocking {
        assertTrue(viewModel.uiState.value.showTrackpoints)

        viewModel.toggleShowTrackpoints(false)
        // Give coroutine a moment to update state
        Thread.sleep(100)

        assertFalse(prefsRepo.isShowTrackpoints())
        assertFalse(viewModel.uiState.value.showTrackpoints)

        viewModel.toggleShowTrackpoints(true)
        Thread.sleep(100)

        assertTrue(prefsRepo.isShowTrackpoints())
        assertTrue(viewModel.uiState.value.showTrackpoints)
    }

    @Test
    fun testPointActionMenuSelection() {
        viewModel.onPointTapped(1)

        val state = viewModel.uiState.value
        assertTrue(state.isActionMenuOpen)
        assertEquals(1, state.selectedPointIndex)
        assertEquals(sampleData.trackPoints[1], state.selectedPoint)

        viewModel.dismissActionMenu()
        assertFalse(viewModel.uiState.value.isActionMenuOpen)
        assertNull(viewModel.uiState.value.selectedPointIndex)
    }

    @Test
    fun testDeleteVertexSuccessAnd2PointGuard() {
        // Track starts with 3 points
        assertEquals(3, viewModel.uiState.value.gpxData.trackPoints.size)

        // Deleting index 1 succeeds
        val success = viewModel.deletePoint(1)
        assertTrue(success)
        assertEquals(2, viewModel.uiState.value.gpxData.trackPoints.size)
        assertEquals(sampleData.trackPoints[0], viewModel.uiState.value.gpxData.trackPoints[0])
        assertEquals(sampleData.trackPoints[2], viewModel.uiState.value.gpxData.trackPoints[1])

        // Now track has 2 points -> attempting deletion MUST fail to preserve polyline geometry
        val failDelete = viewModel.deletePoint(0)
        assertFalse(failDelete)
        assertEquals(2, viewModel.uiState.value.gpxData.trackPoints.size)
        assertNotNull(viewModel.uiState.value.userMessage)
        assertTrue(viewModel.uiState.value.userMessage!!.contains("at least 2 points"))
    }

    @Test
    fun testMoveVertexFlow() {
        viewModel.startMoveMode(1)
        assertTrue(viewModel.uiState.value.isMoveMode)
        assertEquals(1, viewModel.uiState.value.movingPointIndex)

        viewModel.updateMovePosition(25.5, 121.5)
        assertEquals(Pair(25.5, 121.5), viewModel.uiState.value.movingPointPosition)

        viewModel.commitMovePoint(1, 25.5, 121.5)
        val state = viewModel.uiState.value
        assertFalse(state.isMoveMode)
        assertNull(state.movingPointIndex)
        assertEquals(25.5, state.gpxData.trackPoints[1].lat, 0.00001)
        assertEquals(121.5, state.gpxData.trackPoints[1].lon, 0.00001)
    }

    @Test
    fun testConvertToWaypointFlow() {
        viewModel.startConvertToWaypoint(1)
        val state = viewModel.uiState.value
        assertTrue(state.isEditWaypointDialogOpen)
        assertNotNull(state.pendingWaypoint)
        assertEquals(sampleData.trackPoints[1].lat, state.pendingWaypoint!!.lat, 0.00001)
        assertEquals(sampleData.trackPoints[1].lon, state.pendingWaypoint!!.lon, 0.00001)
        assertEquals(sampleData.trackPoints[1].ele, state.pendingWaypoint!!.ele)
        assertEquals(sampleData.trackPoints[1].time, state.pendingWaypoint!!.time)

        val customWpt = Waypoint(
            lat = state.pendingWaypoint!!.lat,
            lon = state.pendingWaypoint!!.lon,
            name = "Camp Spot",
            desc = "Good campsite",
            sym = GpxWaypoint.SYM_FLAG_GREEN
        )
        viewModel.confirmWaypoint(customWpt)

        val updatedState = viewModel.uiState.value
        assertFalse(updatedState.isEditWaypointDialogOpen)
        assertNull(updatedState.pendingWaypoint)
        assertEquals(1, updatedState.gpxData.waypoints.size)
        assertEquals("Camp Spot", updatedState.gpxData.waypoints[0].name)
    }

    @Test
    fun testRouteDragDropInsertion() {
        // Track: P0(25.0, 121.0), P1(25.0, 121.2), P2(25.0, 121.4)
        // Drag dropping near segment 0 (midpoint ~ 25.0, 121.1)
        viewModel.startDragInsertion(25.02, 121.1)
        assertTrue(viewModel.uiState.value.isDragInserting)
        assertEquals(1, viewModel.uiState.value.dragInsertProjectedIndex) // index 0 + 1 = 1

        viewModel.commitDragInsertion(25.02, 121.1)

        val state = viewModel.uiState.value
        assertFalse(state.isDragInserting)
        assertEquals(4, state.gpxData.trackPoints.size)
        assertEquals(25.02, state.gpxData.trackPoints[1].lat, 0.00001)
        assertEquals(121.1, state.gpxData.trackPoints[1].lon, 0.00001)
    }

    @Test
    fun testUndoRedoAfterVertexOperations() {
        assertEquals(3, viewModel.uiState.value.gpxData.trackPoints.size)

        // Delete point at index 1
        viewModel.deletePoint(1)
        assertEquals(2, viewModel.uiState.value.gpxData.trackPoints.size)
        assertTrue(viewModel.uiState.value.canUndo)

        // Undo -> restores 3 points
        viewModel.undo()
        assertEquals(3, viewModel.uiState.value.gpxData.trackPoints.size)
        assertTrue(viewModel.uiState.value.canRedo)

        // Redo -> deletes again to 2 points
        viewModel.redo()
        assertEquals(2, viewModel.uiState.value.gpxData.trackPoints.size)
    }

    @Test
    fun testEditModeDefaultAndToggle() {
        // 1. Initial default state must be locked (false) for accidental touch prevention
        assertFalse(viewModel.isEditModeEnabled.value)
        assertFalse(viewModel.uiState.value.isEditModeEnabled)

        // 2. Toggle to unlocked (true)
        viewModel.toggleEditMode()
        assertTrue(viewModel.isEditModeEnabled.value)
        assertTrue(viewModel.uiState.value.isEditModeEnabled)

        // 3. Open action menu while unlocked
        viewModel.onPointTapped(1)
        assertTrue(viewModel.uiState.value.isActionMenuOpen)
        assertEquals(1, viewModel.uiState.value.selectedPointIndex)

        // 4. Toggle back to locked (false) -> should automatically dismiss action menu & selection
        viewModel.toggleEditMode()
        assertFalse(viewModel.isEditModeEnabled.value)
        assertFalse(viewModel.uiState.value.isEditModeEnabled)
        assertFalse(viewModel.uiState.value.isActionMenuOpen)
        assertNull(viewModel.uiState.value.selectedPointIndex)
    }

    @Test
    fun testToggleEditModePreservesTrackData() {
        val originalTrack = viewModel.uiState.value.gpxData.trackPoints

        // Toggle edit mode multiple times
        viewModel.toggleEditMode()
        assertTrue(viewModel.isEditModeEnabled.value)
        assertEquals(originalTrack, viewModel.uiState.value.gpxData.trackPoints)

        viewModel.toggleEditMode()
        assertFalse(viewModel.isEditModeEnabled.value)
        assertEquals(originalTrack, viewModel.uiState.value.gpxData.trackPoints)
    }
}
