package com.gpxami.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.data.parser.GPXParser
import com.gpxami.app.export.VideoEncoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

sealed interface ExportState {
    object Idle : ExportState
    data class Exporting(val progress: Float, val currentFrame: Int, val totalFrames: Int) : ExportState
    data class Success(val videoUri: Uri) : ExportState
    data class Error(val message: String) : ExportState
}

data class MapUiState(
    val fullTrack: GpxTrack? = null,
    val track: GpxTrack? = null,
    val selectedRange: ClosedFloatingPointRange<Float> = 0f..1f,
    val progress: Float = 0.0f,
    val interpolatedPoint: InterpolatedPoint? = null,
    val isPlaying: Boolean = false,
    val speedMultiplier: Float = 2.0f, // Default 2x speed for exciting preview
    val showElevationProfile: Boolean = true, // Default enabled (bottom 1/5 overlay)
    val showWaypointLabels: Boolean = true, // Toggle display of waypoint text labels (default enabled)
    val mapStyle: com.gpxami.app.map.MapStyle = com.gpxami.app.map.MapStyle.OPEN_STREET_MAP, // OpenStreetMap default
    val mapRotation: Float = 0f, // Rotation in degrees (0 = North-up)
    val zoomOffset: Double = 0.0, // Zoom offset from optimal
    val panOffsetX: Float = 0f, // User pan offset X in unrotated screen pixels
    val panOffsetY: Float = 0f, // User pan offset Y in unrotated screen pixels
    val focusPoint: Pair<Double, Double>? = null, // Explicit camera focus point (e.g. end point during scrubbing)
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportState: ExportState = ExportState.Idle
)

class MapAnimationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private val videoEncoder = VideoEncoder(application)

    init {
        // Automatically load the built-in scenic mountain demo route upon launch
        loadDemoTrack()
    }

    /**
     * Loads the built-in scenic mountain climb GPX track from assets.
     */
    fun loadDemoTrack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val inputStream = getApplication<Application>().assets.open("demo_route.gpx")
                val result = GPXParser.parse(inputStream)
                result.onSuccess { track ->
                    val initialPoint = track.interpolate(0.0f)
                    _uiState.update {
                        it.copy(
                            fullTrack = track,
                            track = track,
                            selectedRange = 0f..1f,
                            progress = 0.0f,
                            interpolatedPoint = initialPoint,
                            isLoading = false
                        )
                    }
                    startPlayback()
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to parse demo track: ${error.localizedMessage}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error opening demo route: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Loads a user-selected GPX file from Storage Access Framework (SAF).
     */
    fun loadGpxFromUri(uri: Uri) {
        viewModelScope.launch {
            pausePlayback()
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val app = getApplication<Application>()
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val result = GPXParser.parseFromUri(app.contentResolver, uri)

            result.onSuccess { track ->
                val initialPoint = track.interpolate(0.0f)
                _uiState.update {
                    it.copy(
                        fullTrack = track,
                        track = track,
                        selectedRange = 0f..1f,
                        progress = 0.0f,
                        interpolatedPoint = initialPoint,
                        isLoading = false,
                        errorMessage = null
                    )
                }
                startPlayback()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "無法讀取 GPX 檔案: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Starts or resumes route animation playback with 60fps pacing.
     */
    fun startPlayback() {
        val currentTrack = _uiState.value.track ?: return
        playbackJob?.cancel()

        _uiState.update { it.copy(isPlaying = true, focusPoint = null) }

        playbackJob = viewModelScope.launch {
            // Target animation duration: 30 seconds for entire track at 1x speed
            val nominalTrackDuration = 30.0f
            val frameIntervalMs = 16L // ~60 fps

            var lastTime = System.nanoTime()

            while (isActive && _uiState.value.isPlaying) {
                delay(frameIntervalMs)

                val now = System.nanoTime()
                val deltaSec = (now - lastTime) / 1_000_000_000.0f
                lastTime = now

                val speed = _uiState.value.speedMultiplier
                val progressStep = (deltaSec * speed) / nominalTrackDuration

                var newProgress = _uiState.value.progress + progressStep
                if (newProgress >= 1.0f) {
                    newProgress = 0.0f // Seamless loop
                }

                val interpolated = currentTrack.interpolate(newProgress)

                _uiState.update {
                    it.copy(
                        progress = newProgress,
                        interpolatedPoint = interpolated
                    )
                }
            }
        }
    }

    /**
     * Pauses the animation playback.
     */
    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    /**
     * Toggles play/pause state.
     */
    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    /**
     * Seeks to a specific progress position (0.0 .. 1.0) along the track.
     */
    fun seekTo(progress: Float) {
        val track = _uiState.value.track ?: return
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        val interpolated = track.interpolate(clampedProgress)

        _uiState.update {
            it.copy(
                progress = clampedProgress,
                interpolatedPoint = interpolated,
                focusPoint = null
            )
        }
    }

    /**
     * Sets playback speed multiplier (1x, 2x, 5x, 10x).
     */
    fun setSpeedMultiplier(multiplier: Float) {
        _uiState.update { it.copy(speedMultiplier = multiplier) }
    }

    /**
     * Toggles elevation profile bottom 1/5 overlay visibility.
     */
    fun toggleElevationProfile(show: Boolean? = null) {
        _uiState.update {
            it.copy(showElevationProfile = show ?: !it.showElevationProfile)
        }
    }

    /**
     * Slices the active route according to the user's selected time/progress range (0f .. 1f).
     * If [focusEnd] is true, smoothly centers camera directly on the end point so the user sees it in real-time.
     */
    fun setTimeRange(
        range: ClosedFloatingPointRange<Float>,
        focusEnd: Boolean = false,
        focusStart: Boolean = false
    ) {
        val full = _uiState.value.fullTrack ?: return
        val start = range.start.coerceIn(0f, 0.999f)
        val end = range.endInclusive.coerceIn(start + 0.001f, 1f)

        val sliced = full.sliceRange(start, end)
        pausePlayback()

        val focusPt = when {
            focusEnd && sliced.points.isNotEmpty() -> {
                val pt = sliced.points.last()
                Pair(pt.lat, pt.lon)
            }
            focusStart && sliced.points.isNotEmpty() -> {
                val pt = sliced.points.first()
                Pair(pt.lat, pt.lon)
            }
            else -> null
        }

        val initialPoint = if (focusEnd && sliced.points.isNotEmpty()) {
            sliced.interpolate(1.0f)
        } else {
            sliced.interpolate(0.0f)
        }

        _uiState.update {
            it.copy(
                selectedRange = start..end,
                track = sliced,
                progress = if (focusEnd) 1.0f else 0.0f,
                interpolatedPoint = initialPoint,
                focusPoint = focusPt,
                panOffsetX = if (focusEnd || focusStart) 0f else it.panOffsetX,
                panOffsetY = if (focusEnd || focusStart) 0f else it.panOffsetY
            )
        }
    }

    /**
     * Resets the time range back to the 100% full track.
     */
    fun resetTimeRange() {
        val full = _uiState.value.fullTrack ?: return
        pausePlayback()
        val initialPoint = full.interpolate(0.0f)
        _uiState.update {
            it.copy(
                selectedRange = 0f..1f,
                track = full,
                progress = 0.0f,
                interpolatedPoint = initialPoint,
                focusPoint = null
            )
        }
    }

    /**
     * Switches between OpenStreetMap (default) and OpenTopoMap.
     */
    fun setMapStyle(style: com.gpxami.app.map.MapStyle) {
        _uiState.update { it.copy(mapStyle = style) }
    }

    /**
     * Sets map rotation angle in degrees.
     */
    fun setMapRotation(degrees: Float) {
        val normalized = (degrees % 360f + 360f) % 360f
        _uiState.update { it.copy(mapRotation = normalized) }
    }

    /**
     * Resets map rotation to North-up (0 degrees).
     */
    fun resetMapRotation() {
        _uiState.update { it.copy(mapRotation = 0f) }
    }

    /**
     * Rotates map by relative degrees (e.g. +45° or -45°).
     */
    fun rotateMapBy(degrees: Float) {
        setMapRotation(_uiState.value.mapRotation + degrees)
    }

    /**
     * Toggles whether waypoint (<wpt>) text name labels are displayed on the map.
     */
    fun toggleWaypointLabels() {
        _uiState.update { it.copy(showWaypointLabels = !it.showWaypointLabels) }
    }

    fun setShowWaypointLabels(show: Boolean) {
        _uiState.update { it.copy(showWaypointLabels = show) }
    }

    /**
     * Adjusts map zoom offset (relative to optimal zoom).
     */
    fun adjustZoom(delta: Double) {
        _uiState.update {
            it.copy(zoomOffset = (it.zoomOffset + delta).coerceIn(-3.0, 4.0))
        }
    }

    /**
     * Sets exact zoom offset.
     */
    fun setZoomOffset(offset: Double) {
        _uiState.update {
            it.copy(zoomOffset = offset.coerceIn(-3.0, 4.0))
        }
    }

    /**
     * Resets zoom offset back to optimal fit.
     */
    fun resetZoom() {
        _uiState.update { it.copy(zoomOffset = 0.0) }
    }

    /**
     * Interactive pan / move map by [dx] and [dy] pixels (in unrotated canvas coordinates).
     */
    fun onPan(dx: Float, dy: Float) {
        _uiState.update {
            it.copy(
                panOffsetX = it.panOffsetX + dx,
                panOffsetY = it.panOffsetY + dy,
                focusPoint = null // Manual pan overrides explicit focus
            )
        }
    }

    /**
     * Resets pan offsets back to center on the active route/marker.
     */
    fun resetPan() {
        _uiState.update {
            it.copy(
                panOffsetX = 0f,
                panOffsetY = 0f,
                focusPoint = null
            )
        }
    }

    /**
     * Resets all viewport modifications (rotation, zoom, pan).
     */
    fun resetView() {
        _uiState.update {
            it.copy(
                mapRotation = 0f,
                zoomOffset = 0.0,
                panOffsetX = 0f,
                panOffsetY = 0f,
                focusPoint = null
            )
        }
    }

    /**
     * Launches the off-screen MP4 video export pipeline with active range, rotation, zoom, pan, and map style.
     */
    fun exportVideo(config: VideoEncoder.ExportConfig) {
        val track = _uiState.value.track ?: return

        pausePlayback()
        _uiState.update { it.copy(exportState = ExportState.Exporting(0f, 0, config.durationSeconds * config.fps)) }

        val exportConfig = config.copy(
            showElevationProfile = _uiState.value.showElevationProfile,
            showWaypointLabels = _uiState.value.showWaypointLabels,
            zoomOffset = _uiState.value.zoomOffset,
            rotationDegrees = _uiState.value.mapRotation,
            mapStyle = _uiState.value.mapStyle,
            panOffsetX = _uiState.value.panOffsetX,
            panOffsetY = _uiState.value.panOffsetY
        )

        viewModelScope.launch {
            val result = videoEncoder.exportTrackVideo(
                track = track,
                config = exportConfig,
                onProgress = { progress, currentFrame, totalFrames ->
                    _uiState.update {
                        it.copy(exportState = ExportState.Exporting(progress, currentFrame, totalFrames))
                    }
                }
            )

            result.onSuccess { uri ->
                _uiState.update { it.copy(exportState = ExportState.Success(uri)) }
            }.onFailure { err ->
                _uiState.update { it.copy(exportState = ExportState.Error(err.localizedMessage ?: "Unknown export error")) }
            }
        }
    }

    /**
     * Dismisses the export modal / snackbar.
     */
    fun dismissExportDialog() {
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }

    /**
     * Clears any active error message.
     */
    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}
