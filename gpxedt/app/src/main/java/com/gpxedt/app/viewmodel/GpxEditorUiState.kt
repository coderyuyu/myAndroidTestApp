package com.gpxedt.app.viewmodel

import android.net.Uri
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxFileInfo
import com.gpxedt.app.model.RoutingProfile
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.network.OsrmRoutingApi
import java.time.Instant

data class GpxEditorUiState(
    val fileName: String? = null,
    val fileUri: Uri? = null,
    val gpxData: GpxData = GpxData(),
    val originalTrackPoints: List<TrackPoint>? = null,
    val startPointerIndex: Int = 0,
    val middlePointerIndex: Int = 0,
    val endPointerIndex: Int = 0,
    val routingProfile: RoutingProfile = RoutingProfile.FOOT_HIKING,
    val osrmServerUrl: String = OsrmRoutingApi.DEFAULT_OSRM_URL,
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val userMessage: String? = null,
    val isAddWaypointDialogOpen: Boolean = false,
    val pendingWaypointLocation: Pair<Double, Double>? = null,
    val pendingPhotoWaypoint: Waypoint? = null,
    val photoRouteDistanceMeters: Double? = null,
    val pendingWaypointTime: Instant? = null,
    val isPendingWaypointTimeFromTrack: Boolean = false,
    val pendingWaypointEle: Double? = null,
    val outOfRouteInfo: com.gpxedt.app.ui.components.OutOfRouteInfo? = null,
    val isWaypointListDialogOpen: Boolean = false,
    val waypointSortOrder: com.gpxedt.app.model.WaypointSortOrder = com.gpxedt.app.model.WaypointSortOrder.MANUAL,
    val editingWaypoint: Waypoint? = null,
    val isServerSettingsDialogOpen: Boolean = false,
    val isSaveConfirmDialogOpen: Boolean = false,
    val isOpenGpxFileDialogOpen: Boolean = false,
    val availableGpxFiles: List<GpxFileInfo> = emptyList(),
    val isScanningGpxFiles: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val mapBoundsTrigger: Int = 0,
    val focusLocation: Pair<Double, Double>? = null,
    val cameraCenterLocation: Pair<Double, Double>? = null,
    val showTrackpoints: Boolean = true,
    val selectedVertexIndex: Int? = null,
    val isPointActionSheetOpen: Boolean = false,
    val isMoveVertexMode: Boolean = false,
    val movingVertexIndex: Int? = null,
    val movingVertexPosition: Pair<Double, Double>? = null,
    val isDragInsertingVertex: Boolean = false,
    val dragInsertCoordinate: Pair<Double, Double>? = null,
    val dragInsertProjectedIndex: Int? = null
) {
    val selectedVertex: TrackPoint?
        get() = selectedVertexIndex?.let { if (it in gpxData.trackPoints.indices) gpxData.trackPoints[it] else null }
    val startPoint: TrackPoint?
        get() = if (startPointerIndex in gpxData.trackPoints.indices) gpxData.trackPoints[startPointerIndex] else null

    val middlePoint: TrackPoint?
        get() = if (middlePointerIndex in gpxData.trackPoints.indices) gpxData.trackPoints[middlePointerIndex] else null

    val endPoint: TrackPoint?
        get() = if (endPointerIndex in gpxData.trackPoints.indices) gpxData.trackPoints[endPointerIndex] else null

    val canReplaceRoute: Boolean
        get() = gpxData.trackPoints.isNotEmpty() && startPointerIndex < endPointerIndex

    val canSpan: Boolean
        get() = gpxData.trackPoints.isNotEmpty() && (startPointerIndex > 0 || endPointerIndex < gpxData.trackPoints.size - 1)

    val canReset: Boolean
        get() = (startPointerIndex > 0 || (gpxData.trackPoints.isNotEmpty() && endPointerIndex < gpxData.trackPoints.size - 1)) ||
                (originalTrackPoints != null && originalTrackPoints != gpxData.trackPoints)
}
