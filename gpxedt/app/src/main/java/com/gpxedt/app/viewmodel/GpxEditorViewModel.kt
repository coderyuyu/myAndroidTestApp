package com.gpxedt.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpxedt.app.engine.GpxEditEngine
import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxFileInfo
import com.gpxedt.app.model.RoutingProfile
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import com.gpxedt.app.network.OsrmRoutingApi
import com.gpxedt.app.network.RoutingRepository
import com.gpxedt.app.parser.GpxParser
import com.gpxedt.app.parser.GpxSerializer
import com.gpxedt.app.ui.components.OutOfRouteInfo
import com.gpxedt.app.util.GeoUtils
import com.gpxedt.app.util.GpxFileScanner
import com.gpxedt.app.util.PhotoExifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class GpxEditorViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val editEngine = GpxEditEngine()
    private val sharedPrefs = application.getSharedPreferences("gpx_editor_prefs", Context.MODE_PRIVATE)

    private val savedServerUrl = sharedPrefs.getString("osrm_server_url", OsrmRoutingApi.DEFAULT_OSRM_URL)
        ?: OsrmRoutingApi.DEFAULT_OSRM_URL

    private val routingRepository = RoutingRepository(savedServerUrl)

    private val _uiState = MutableStateFlow(
        GpxEditorUiState(
            osrmServerUrl = savedServerUrl
        )
    )
    val uiState: StateFlow<GpxEditorUiState> = _uiState.asStateFlow()

    fun updateServerUrl(newUrl: String) {
        val trimmed = newUrl.trim().ifEmpty { OsrmRoutingApi.DEFAULT_OSRM_URL }
        sharedPrefs.edit().putString("osrm_server_url", trimmed).apply()
        routingRepository.updateServerUrl(trimmed)
        _uiState.update { it.copy(osrmServerUrl = trimmed, isServerSettingsDialogOpen = false) }
    }

    fun openServerSettingsDialog() {
        _uiState.update { it.copy(isServerSettingsDialogOpen = true) }
    }

    fun dismissServerSettingsDialog() {
        _uiState.update { it.copy(isServerSettingsDialogOpen = false) }
    }

    fun openSaveConfirmDialog() {
        _uiState.update { it.copy(isSaveConfirmDialogOpen = true) }
    }

    fun dismissSaveConfirmDialog() {
        _uiState.update { it.copy(isSaveConfirmDialogOpen = false) }
    }

    fun openGpxFileSelectionDialog(context: Context) {
        _uiState.update { it.copy(isOpenGpxFileDialogOpen = true, isScanningGpxFiles = true) }
        viewModelScope.launch {
            val scanned = GpxFileScanner.scanGpxFiles(context)
            _uiState.update { it.copy(availableGpxFiles = scanned, isScanningGpxFiles = false) }
        }
    }

    fun dismissGpxFileSelectionDialog() {
        _uiState.update { it.copy(isOpenGpxFileDialogOpen = false) }
    }

    fun loadSelectedGpxFile(fileInfo: GpxFileInfo, contentResolver: ContentResolver) {
        dismissGpxFileSelectionDialog()
        val uri = fileInfo.uri ?: return
        loadFromUri(uri, contentResolver)
    }

    fun confirmSave(contentResolver: ContentResolver) {
        val uri = _uiState.value.fileUri
        dismissSaveConfirmDialog()
        if (uri != null) {
            saveToUri(uri, contentResolver)
        }
    }

    fun dismissUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    // --- 3-Pointer Slider Controls ---

    fun setStartPointer(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (points.isEmpty()) return
        val clampedStart = index.coerceIn(0, points.size - 1)
        _uiState.update { state ->
            val newMiddle = state.middlePointerIndex.coerceIn(clampedStart, state.endPointerIndex.coerceAtLeast(clampedStart))
            val newEnd = state.endPointerIndex.coerceAtLeast(clampedStart)
            val pt = points[clampedStart]
            state.copy(
                startPointerIndex = clampedStart,
                middlePointerIndex = newMiddle,
                endPointerIndex = newEnd,
                cameraCenterLocation = pt.lat to pt.lon
            )
        }
    }

    fun setMiddlePointer(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (points.isEmpty()) return
        val clampedMiddle = index.coerceIn(_uiState.value.startPointerIndex, _uiState.value.endPointerIndex)
        _uiState.update { state ->
            val pt = points[clampedMiddle]
            state.copy(
                middlePointerIndex = clampedMiddle,
                cameraCenterLocation = pt.lat to pt.lon
            )
        }
    }

    fun setEndPointer(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (points.isEmpty()) return
        val clampedEnd = index.coerceIn(0, points.size - 1)
        _uiState.update { state ->
            val newStart = state.startPointerIndex.coerceAtMost(clampedEnd)
            val newMiddle = state.middlePointerIndex.coerceIn(newStart, clampedEnd)
            val pt = points[clampedEnd]
            state.copy(
                startPointerIndex = newStart,
                middlePointerIndex = newMiddle,
                endPointerIndex = clampedEnd,
                cameraCenterLocation = pt.lat to pt.lon
            )
        }
    }

    fun onPointerMoving(index: Int) {
        val points = _uiState.value.gpxData.trackPoints
        if (index in points.indices) {
            val pt = points[index]
            _uiState.update { it.copy(cameraCenterLocation = pt.lat to pt.lon) }
        }
    }

    fun setRoutingProfile(profile: RoutingProfile) {
        _uiState.update { it.copy(routingProfile = profile) }
    }

    // --- Span and Reset ---

    fun span() {
        val state = _uiState.value
        val points = state.gpxData.trackPoints
        if (points.isEmpty()) return

        val startIdx = state.startPointerIndex
        val endIdx = state.endPointerIndex

        if (startIdx >= endIdx) return

        val updated = editEngine.span(startIdx, endIdx)
        val newLastIndex = updated.trackPoints.size - 1

        _uiState.update {
            it.copy(
                gpxData = updated,
                startPointerIndex = 0,
                endPointerIndex = newLastIndex,
                middlePointerIndex = newLastIndex / 2,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                mapBoundsTrigger = it.mapBoundsTrigger + 1,
                userMessage = "Spanned to range ($startIdx..$endIdx), ${updated.trackPoints.size} points remaining"
            )
        }
    }

    fun reset() {
        val state = _uiState.value
        val originalPts = state.originalTrackPoints

        if (originalPts != null && originalPts != state.gpxData.trackPoints) {
            // Restore original full track if it was previously cropped/spanned
            val restored = state.gpxData.copy(trackPoints = originalPts)
            editEngine.loadData(restored)
            val lastIdx = (originalPts.size - 1).coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    gpxData = restored,
                    startPointerIndex = 0,
                    endPointerIndex = lastIdx,
                    middlePointerIndex = lastIdx / 2,
                    canUndo = editEngine.canUndo,
                    canRedo = editEngine.canRedo,
                    mapBoundsTrigger = it.mapBoundsTrigger + 1,
                    userMessage = "Reset to original track (${originalPts.size} points)"
                )
            }
        } else {
            // Reset slide pointers to 0 and full range
            val lastIdx = (state.gpxData.trackPoints.size - 1).coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    startPointerIndex = 0,
                    endPointerIndex = lastIdx,
                    middlePointerIndex = lastIdx / 2,
                    userMessage = "Reset pointers to full span"
                )
            }
        }
    }

    // --- Waypoint Management ---

    fun openAddWaypointDialog(lat: Double?, lon: Double?) {
        val targetLoc = if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
            lat to lon
        } else {
            null
        }
        _uiState.update {
            it.copy(
                isAddWaypointDialogOpen = true,
                pendingWaypointLocation = targetLoc,
                pendingPhotoWaypoint = null,
                photoRouteDistanceMeters = null
            )
        }
    }

    fun openAddWaypointForMiddlePointer() {
        val state = _uiState.value
        val middlePt = state.middlePoint
        val targetLoc = when {
            middlePt != null -> middlePt.lat to middlePt.lon
            state.cameraCenterLocation != null -> state.cameraCenterLocation
            state.focusLocation != null -> state.focusLocation
            state.gpxData.trackPoints.isNotEmpty() -> state.gpxData.trackPoints[0].lat to state.gpxData.trackPoints[0].lon
            state.gpxData.waypoints.isNotEmpty() -> state.gpxData.waypoints[0].lat to state.gpxData.waypoints[0].lon
            else -> null
        }
        _uiState.update {
            it.copy(
                isAddWaypointDialogOpen = true,
                pendingWaypointLocation = targetLoc,
                pendingPhotoWaypoint = null,
                photoRouteDistanceMeters = null
            )
        }
    }

    fun dismissAddWaypointDialog() {
        _uiState.update {
            it.copy(
                isAddWaypointDialogOpen = false,
                pendingWaypointLocation = null,
                pendingPhotoWaypoint = null,
                photoRouteDistanceMeters = null
            )
        }
    }

    fun addWaypoint(
        name: String,
        desc: String?,
        sym: String?,
        lat: Double? = null,
        lon: Double? = null,
        time: Instant? = null,
        ele: Double? = null
    ) {
        val finalLat = lat ?: _uiState.value.pendingWaypointLocation?.first
            ?: _uiState.value.pendingPhotoWaypoint?.lat
            ?: _uiState.value.middlePoint?.lat
            ?: return
        val finalLon = lon ?: _uiState.value.pendingWaypointLocation?.second
            ?: _uiState.value.pendingPhotoWaypoint?.lon
            ?: _uiState.value.middlePoint?.lon
            ?: return

        val photoWpt = _uiState.value.pendingPhotoWaypoint
        val finalTime = time ?: photoWpt?.time
        val finalEle = ele ?: photoWpt?.ele

        val waypoint = Waypoint(
            lat = finalLat,
            lon = finalLon,
            name = name.ifBlank { "Waypoint" },
            desc = desc?.ifBlank { null },
            sym = sym?.ifBlank { null },
            ele = finalEle,
            time = finalTime
        )

        val updated = editEngine.addWaypoint(waypoint)
        _uiState.update {
            it.copy(
                gpxData = updated,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                isAddWaypointDialogOpen = false,
                pendingWaypointLocation = null,
                pendingPhotoWaypoint = null,
                photoRouteDistanceMeters = null,
                userMessage = "Waypoint \"${waypoint.name}\" added"
            )
        }
    }

    /**
     * Reads EXIF from the selected photo, resolves place name, checks if photo location
     * is on the current route, and either presents it for editing/saving or notifies that it is out of route.
     */
    fun processSelectedPhoto(context: Context, uri: Uri) {
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingMessage = "Reading photo EXIF metadata..."
            )
        }

        viewModelScope.launch {
            val photoMeta = withContext(Dispatchers.IO) {
                PhotoExifReader.readPhotoMetadata(context, uri)
            }

            if (photoMeta == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userMessage = "No GPS coordinates found in photo EXIF (照片未包含 GPS 座標)"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(loadingMessage = "Resolving place name & checking route...")
            }

            val placeName = GeoUtils.reverseGeocode(context, photoMeta.lat, photoMeta.lon)
            val track = _uiState.value.gpxData.trackPoints

            if (track.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userMessage = "No route loaded to check photo location (尚未載入路線)"
                    )
                }
                return@launch
            }

            val dist = GeoUtils.distanceToPolyline(photoMeta.lat, photoMeta.lon, track)
            val threshold = GeoUtils.DEFAULT_ROUTE_PROXIMITY_THRESHOLD_METERS

            if (dist <= threshold) {
                val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())

                val descText = buildString {
                    if (photoMeta.time != null) {
                        append("Photo Time: ${timeFormatter.format(photoMeta.time)}")
                    }
                    if (photoMeta.ele != null) {
                        if (isNotEmpty()) append(", ")
                        append("Ele: %.1fm".format(Locale.US, photoMeta.ele))
                    }
                    if (!placeName.isNullOrBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("Place: $placeName")
                    }
                }

                val prefilled = Waypoint(
                    lat = photoMeta.lat,
                    lon = photoMeta.lon,
                    name = placeName ?: "Photo WPT",
                    desc = descText.ifBlank { null },
                    sym = "Flag, Red",
                    ele = photoMeta.ele,
                    time = photoMeta.time
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAddWaypointDialogOpen = true,
                        pendingWaypointLocation = photoMeta.lat to photoMeta.lon,
                        pendingPhotoWaypoint = prefilled,
                        photoRouteDistanceMeters = dist,
                        focusLocation = photoMeta.lat to photoMeta.lon,
                        userMessage = "Photo location is on route (%.1f m from track)".format(Locale.US, dist)
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        outOfRouteInfo = OutOfRouteInfo(
                            lat = photoMeta.lat,
                            lon = photoMeta.lon,
                            placeName = placeName,
                            time = photoMeta.time,
                            distanceMeters = dist,
                            thresholdMeters = threshold
                        )
                    )
                }
            }
        }
    }

    fun dismissOutOfRouteDialog() {
        _uiState.update { it.copy(outOfRouteInfo = null) }
    }

    fun openWaypointListDialog() {
        _uiState.update { it.copy(isWaypointListDialogOpen = true) }
    }

    fun dismissWaypointListDialog() {
        _uiState.update { it.copy(isWaypointListDialogOpen = false) }
    }

    fun openEditWaypointDialog(waypoint: Waypoint) {
        _uiState.update { it.copy(editingWaypoint = waypoint) }
    }

    fun dismissEditWaypointDialog() {
        _uiState.update { it.copy(editingWaypoint = null) }
    }

    fun updateWaypoint(oldWaypoint: Waypoint, newWaypoint: Waypoint) {
        val updated = editEngine.updateWaypoint(oldWaypoint, newWaypoint)
        _uiState.update {
            it.copy(
                gpxData = updated,
                editingWaypoint = null,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                userMessage = "Waypoint \"${newWaypoint.name}\" updated"
            )
        }
    }

    fun deleteWaypoint(waypoint: Waypoint) {
        val updated = editEngine.removeWaypoint(waypoint)
        _uiState.update {
            it.copy(
                gpxData = updated,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                userMessage = "Waypoint \"${waypoint.name}\" deleted"
            )
        }
    }

    fun locateWaypoint(waypoint: Waypoint) {
        _uiState.update {
            it.copy(
                focusLocation = waypoint.lat to waypoint.lon,
                isWaypointListDialogOpen = false,
                userMessage = "Centered on \"${waypoint.name}\""
            )
        }
    }

    // --- Online Routing Replace ---

    fun replaceSegmentWithOnlineRoute() {
        val state = _uiState.value
        val startIdx = state.startPointerIndex
        val endIdx = state.endPointerIndex

        if (startIdx >= endIdx || state.gpxData.trackPoints.isEmpty()) {
            _uiState.update { it.copy(userMessage = "Start pointer must be before End pointer") }
            return
        }

        val startPt = state.gpxData.trackPoints[startIdx]
        val endPt = state.gpxData.trackPoints[endIdx]

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingMessage = "Requesting route from OSRM..."
                )
            }

            try {
                val routePoints = withContext(Dispatchers.IO) {
                    routingRepository.fetchRouteSegment(
                        startPoint = startPt,
                        endPoint = endPt,
                        profile = state.routingProfile
                    )
                }

                val updated = editEngine.replaceSegment(startIdx, endIdx, routePoints)
                val newLastIndex = updated.trackPoints.size - 1

                _uiState.update {
                    it.copy(
                        gpxData = updated,
                        endPointerIndex = newLastIndex.coerceAtLeast(startIdx),
                        middlePointerIndex = ((startIdx + newLastIndex) / 2).coerceIn(startIdx, newLastIndex),
                        canUndo = editEngine.canUndo,
                        canRedo = editEngine.canRedo,
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "Replaced segment with ${routePoints.size} OSRM route points"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "OSRM routing failed: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun undo() {
        val undone = editEngine.undo() ?: return
        val lastIdx = (undone.trackPoints.size - 1).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                gpxData = undone,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                startPointerIndex = it.startPointerIndex.coerceIn(0, lastIdx),
                endPointerIndex = it.endPointerIndex.coerceIn(0, lastIdx),
                middlePointerIndex = it.middlePointerIndex.coerceIn(0, lastIdx),
                userMessage = "Undo"
            )
        }
    }

    fun redo() {
        val redone = editEngine.redo() ?: return
        val lastIdx = (redone.trackPoints.size - 1).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                gpxData = redone,
                canUndo = editEngine.canUndo,
                canRedo = editEngine.canRedo,
                startPointerIndex = it.startPointerIndex.coerceIn(0, lastIdx),
                endPointerIndex = it.endPointerIndex.coerceIn(0, lastIdx),
                middlePointerIndex = it.middlePointerIndex.coerceIn(0, lastIdx),
                userMessage = "Redo"
            )
        }
    }

    fun loadFromUri(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Opening GPX file...") }

            try {
                val fileName = queryFileName(uri, contentResolver) ?: "Track.gpx"
                val parsedData = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        GpxParser.parse(stream)
                    } ?: throw IllegalStateException("Cannot open input stream")
                }

                editEngine.loadData(parsedData)
                val lastIdx = (parsedData.trackPoints.size - 1).coerceAtLeast(0)

                _uiState.update {
                    it.copy(
                        fileName = fileName,
                        fileUri = uri,
                        gpxData = parsedData,
                        originalTrackPoints = parsedData.trackPoints,
                        startPointerIndex = 0,
                        middlePointerIndex = lastIdx / 2,
                        endPointerIndex = lastIdx,
                        canUndo = false,
                        canRedo = false,
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "Loaded \"$fileName\" (${parsedData.trackPoints.size} points)",
                        mapBoundsTrigger = it.mapBoundsTrigger + 1
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "Failed to load GPX: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun saveToUri(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Saving GPX file...") }

            try {
                val currentData = _uiState.value.gpxData
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        GpxSerializer.serialize(currentData, stream)
                    } ?: throw IllegalStateException("Cannot open output stream")
                }

                val fileName = queryFileName(uri, contentResolver) ?: _uiState.value.fileName ?: "Track.gpx"

                _uiState.update {
                    it.copy(
                        fileName = fileName,
                        fileUri = uri,
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "Saved successfully to \"$fileName\""
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = null,
                        userMessage = "Failed to save GPX: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    private fun queryFileName(uri: Uri, contentResolver: ContentResolver): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return name ?: uri.lastPathSegment
    }
}
