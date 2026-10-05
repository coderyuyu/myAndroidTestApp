package com.gpxedt.app.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpxedt.app.data.preferences.UserPreferencesRepository
import com.gpxedt.app.data.preferences.UserPreferencesRepositoryImpl
import com.gpxedt.app.engine.GpxEditEngine
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.util.GeoSpatialUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI State representing interactive track editing, vertex visibility,
 * coordinate drag mode, and waypoint conversion.
 */
data class TrackEditUiState(
    val gpxData: GpxData = GpxData(),
    val showTrackpoints: Boolean = true,
    val isEditModeEnabled: Boolean = false,
    val selectedPointIndex: Int? = null,
    val isActionMenuOpen: Boolean = false,
    // Move mode state
    val isMoveMode: Boolean = false,
    val movingPointIndex: Int? = null,
    val movingPointPosition: Pair<Double, Double>? = null,
    // Drag-insertion state
    val isDragInserting: Boolean = false,
    val dragInsertCoordinate: Pair<Double, Double>? = null,
    val dragInsertProjectedIndex: Int? = null,
    // Waypoint conversion dialog state
    val isEditWaypointDialogOpen: Boolean = false,
    val pendingWaypoint: GpxWaypoint? = null,
    // User message & history
    val userMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val selectedPoint: TrackPoint?
        get() = selectedPointIndex?.let {
            if (it in gpxData.trackPoints.indices) gpxData.trackPoints[it] else null
        }
}

/**
 * ViewModel managing trackpoint editing gestures, point actions (Move, Delete, Set as WPT),
 * segment snap insertion, and visibility state persistence.
 */
class TrackEditViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    initialData: GpxData = GpxData(),
    scope: kotlinx.coroutines.CoroutineScope? = null
) : ViewModel() {

    private val effectiveScope: kotlinx.coroutines.CoroutineScope = scope ?: viewModelScope

    constructor(application: Application) : this(
        preferencesRepository = UserPreferencesRepositoryImpl(application)
    )

    constructor() : this(
        preferencesRepository = object : UserPreferencesRepository {
            private val _flow = MutableStateFlow(true)
            override val showTrackpointsFlow = _flow.asStateFlow()
            override suspend fun setShowTrackpoints(show: Boolean) { _flow.value = show }
            override fun isShowTrackpoints() = _flow.value
        }
    )

    private val editEngine = GpxEditEngine(initialData)

    private val _isEditModeEnabled = MutableStateFlow(false)
    val isEditModeEnabled: StateFlow<Boolean> = _isEditModeEnabled.asStateFlow()

    private val _uiState = MutableStateFlow(
        TrackEditUiState(
            gpxData = initialData,
            showTrackpoints = preferencesRepository.isShowTrackpoints(),
            isEditModeEnabled = _isEditModeEnabled.value,
            canUndo = editEngine.canUndo,
            canRedo = editEngine.canRedo
        )
    )
    val uiState: StateFlow<TrackEditUiState> = _uiState.asStateFlow()

    /**
     * Toggles trackpoint map edit mode lock/unlock.
     * When toggling to locked (false), any active editing interactions (action menu,
     * vertex dragging, segment insertion) are dismissed/cancelled.
     */
    fun toggleEditMode() {
        val nextState = !_isEditModeEnabled.value
        _isEditModeEnabled.value = nextState
        _uiState.update { current ->
            current.copy(
                isEditModeEnabled = nextState,
                isActionMenuOpen = if (!nextState) false else current.isActionMenuOpen,
                selectedPointIndex = if (!nextState) null else current.selectedPointIndex,
                isMoveMode = if (!nextState) false else current.isMoveMode,
                movingPointIndex = if (!nextState) null else current.movingPointIndex,
                movingPointPosition = if (!nextState) null else current.movingPointPosition,
                isDragInserting = if (!nextState) false else current.isDragInserting,
                dragInsertCoordinate = if (!nextState) null else current.dragInsertCoordinate,
                dragInsertProjectedIndex = if (!nextState) null else current.dragInsertProjectedIndex
            )
        }
    }

    init {
        preferencesRepository.showTrackpointsFlow
            .onEach { show ->
                _uiState.update { it.copy(showTrackpoints = show) }
            }
            .launchIn(effectiveScope)
    }

    /**
     * Loads or replaces current GPX track data.
     */
    fun loadGpxData(data: GpxData) {
        editEngine.loadData(data)
        _uiState.update {
            it.copy(
                gpxData = data,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                selectedPointIndex = null,
                isActionMenuOpen = false,
                isMoveMode = false,
                isDragInserting = false
            )
        }
    }

    /**
     * Toggles intermediate trackpoint dots visibility and persists the preference.
     */
    fun toggleShowTrackpoints(show: Boolean) {
        effectiveScope.launch {
            preferencesRepository.setShowTrackpoints(show)
        }
    }

    /**
     * User tapped an existing vertex; opens the action sheet for that point.
     */
    fun onPointTapped(index: Int) {
        if (index in _uiState.value.gpxData.trackPoints.indices) {
            _uiState.update {
                it.copy(
                    selectedPointIndex = index,
                    isActionMenuOpen = true
                )
            }
        }
    }

    /**
     * Dismisses the point action sheet.
     */
    fun dismissActionMenu() {
        _uiState.update {
            it.copy(
                isActionMenuOpen = false,
                selectedPointIndex = null
            )
        }
    }

    /**
     * Enters coordinate drag mode for the specified vertex.
     */
    fun startMoveMode(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (index in points.indices) {
            val pt = points[index]
            _uiState.update {
                it.copy(
                    isActionMenuOpen = false,
                    isMoveMode = true,
                    movingPointIndex = index,
                    movingPointPosition = Pair(pt.lat, pt.lon)
                )
            }
        }
    }

    /**
     * Updates preview coordinates while dragging a vertex in move mode.
     */
    fun updateMovePosition(lat: Double, lon: Double) {
        if (_uiState.value.isMoveMode) {
            _uiState.update { it.copy(movingPointPosition = Pair(lat, lon)) }
        }
    }

    /**
     * Commits updated coordinates on drop in move mode.
     */
    fun commitMovePoint(index: Int, newLat: Double, newLon: Double) {
        val updated = editEngine.moveTrackPoint(index, newLat, newLon)
        _uiState.update {
            it.copy(
                gpxData = updated,
                isMoveMode = false,
                movingPointIndex = null,
                movingPointPosition = null,
                selectedPointIndex = null,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
    }

    /**
     * Cancels coordinate drag mode without committing changes.
     */
    fun cancelMoveMode() {
        _uiState.update {
            it.copy(
                isMoveMode = false,
                movingPointIndex = null,
                movingPointPosition = null
            )
        }
    }

    /**
     * Deletes the vertex at [index], strictly enforcing the 2-point minimum check.
     */
    fun deletePoint(index: Int): Boolean {
        val currentPoints = _uiState.value.gpxData.trackPoints
        if (currentPoints.size <= 2) {
            _uiState.update {
                it.copy(
                    isActionMenuOpen = false,
                    userMessage = "Cannot delete: Track requires at least 2 points"
                )
            }
            return false
        }

        val updated = editEngine.deleteTrackPoint(index)
        _uiState.update {
            it.copy(
                gpxData = updated,
                isActionMenuOpen = false,
                selectedPointIndex = null,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
        return true
    }

    /**
     * Initiates converting a trackpoint to a waypoint by constructing a GpxWaypoint
     * inheriting coordinates, elevation, and timestamp, then opening the EditWaypointDialog.
     */
    fun startConvertToWaypoint(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (index in points.indices) {
            val pt = points[index]
            val wptName = "Point_${index + 1}"
            val wpt = GeoSpatialUtil.pointToWaypoint(
                point = pt,
                name = wptName,
                desc = "Converted from trackpoint #${index + 1}"
            )
            _uiState.update {
                it.copy(
                    isActionMenuOpen = false,
                    selectedPointIndex = null,
                    pendingWaypoint = wpt,
                    isEditWaypointDialogOpen = true
                )
            }
        }
    }

    /**
     * Confirms and persists the newly converted waypoint.
     */
    fun confirmWaypoint(waypoint: Waypoint) {
        val updated = editEngine.addWaypoint(waypoint)
        _uiState.update {
            it.copy(
                gpxData = updated,
                pendingWaypoint = null,
                isEditWaypointDialogOpen = false,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
    }

    /**
     * Dismisses the waypoint conversion edit dialog.
     */
    fun dismissEditWaypointDialog() {
        _uiState.update {
            it.copy(
                pendingWaypoint = null,
                isEditWaypointDialogOpen = false
            )
        }
    }

    /**
     * Starts route drag & drop insertion gesture.
     */
    fun startDragInsertion(lat: Double, lon: Double) {
        val points = _uiState.value.gpxData.trackPoints
        val nearest = GeoSpatialUtil.findNearestSegment(lat, lon, points)
        _uiState.update {
            it.copy(
                isDragInserting = true,
                dragInsertCoordinate = Pair(lat, lon),
                dragInsertProjectedIndex = nearest?.insertionIndex
            )
        }
    }

    /**
     * Updates drag position during route drag insertion gesture.
     */
    fun updateDragInsertion(lat: Double, lon: Double) {
        if (_uiState.value.isDragInserting) {
            val points = _uiState.value.gpxData.trackPoints
            val nearest = GeoSpatialUtil.findNearestSegment(lat, lon, points)
            _uiState.update {
                it.copy(
                    dragInsertCoordinate = Pair(lat, lon),
                    dragInsertProjectedIndex = nearest?.insertionIndex
                )
            }
        }
    }

    /**
     * Drops onto the map on gesture release to insert a new TrackPoint at the projected index.
     */
    fun commitDragInsertion(lat: Double, lon: Double) {
        val points = _uiState.value.gpxData.trackPoints
        val targetIndex = _uiState.value.dragInsertProjectedIndex
            ?: GeoSpatialUtil.findNearestSegment(lat, lon, points)?.insertionIndex
            ?: points.size

        val newPoint = TrackPoint(lat = lat, lon = lon)
        val updated = editEngine.insertTrackPoint(targetIndex, newPoint)

        _uiState.update {
            it.copy(
                gpxData = updated,
                isDragInserting = false,
                dragInsertCoordinate = null,
                dragInsertProjectedIndex = null,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
    }

    /**
     * Cancels route drag insertion gesture.
     */
    fun cancelDragInsertion() {
        _uiState.update {
            it.copy(
                isDragInserting = false,
                dragInsertCoordinate = null,
                dragInsertProjectedIndex = null
            )
        }
    }

    fun undo() {
        val undone = editEngine.undo() ?: return
        _uiState.update {
            it.copy(
                gpxData = undone,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
    }

    fun redo() {
        val redone = editEngine.redo() ?: return
        _uiState.update {
            it.copy(
                gpxData = redone,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo
            )
        }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
