package com.gpxedt.app.ui.waypoint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpxedt.app.data.repository.GeocodingRepository
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.util.GpxDateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

/**
 * ViewModel for Add Waypoint dialog.
 * Responsibilities:
 * 1. Timestamp synchronization: inherit from TrackPoint or default to Instant.now().
 * 2. Asynchronous reverse geocoding via [GeocodingRepository] off [Dispatchers.IO].
 * 3. Reactive state management with dirty-tracking so geocoded names do not overwrite user input.
 * 4. Coordinate and input validation for creating a standardized [GpxWaypoint].
 */
class AddWaypointViewModel(
    private var repository: GeocodingRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _nameState = MutableStateFlow("")
    val nameState: StateFlow<String> = _nameState.asStateFlow()

    private val _isGeocodingLoading = MutableStateFlow(false)
    val isGeocodingLoading: StateFlow<Boolean> = _isGeocodingLoading.asStateFlow()

    private val _descriptionState = MutableStateFlow("")
    val descriptionState: StateFlow<String> = _descriptionState.asStateFlow()

    private val _symbolState = MutableStateFlow(GpxWaypoint.DEFAULT_SYMBOL)
    val symbolState: StateFlow<String> = _symbolState.asStateFlow()

    private val _latTextState = MutableStateFlow("")
    val latTextState: StateFlow<String> = _latTextState.asStateFlow()

    private val _lonTextState = MutableStateFlow("")
    val lonTextState: StateFlow<String> = _lonTextState.asStateFlow()

    private val _selectedTime = MutableStateFlow<Instant>(Instant.now())
    val selectedTime: StateFlow<Instant> = _selectedTime.asStateFlow()

    private val _isTimeInheritedFromTrack = MutableStateFlow(false)
    val isTimeInheritedFromTrack: StateFlow<Boolean> = _isTimeInheritedFromTrack.asStateFlow()

    private val _elevationState = MutableStateFlow<Double?>(null)
    val elevationState: StateFlow<Double?> = _elevationState.asStateFlow()

    private val _isNameError = MutableStateFlow(false)
    val isNameError: StateFlow<Boolean> = _isNameError.asStateFlow()

    private val _isCoordError = MutableStateFlow(false)
    val isCoordError: StateFlow<Boolean> = _isCoordError.asStateFlow()

    private var isUserModifiedName: Boolean = false
    private var initialized: Boolean = false

    val geocodingRepository: GeocodingRepository?
        get() = repository

    fun setGeocodingRepository(repo: GeocodingRepository) {
        this.repository = repo
    }

    /**
     * Initializes state for a new waypoint addition.
     * Inherits timestamp from [inheritedTrackPoint] or [inheritedTime] if available,
     * or defaults to [Instant.now()].
     */
    fun initialize(
        lat: Double?,
        lon: Double?,
        initialWaypoint: GpxWaypoint? = null,
        inheritedTrackPoint: TrackPoint? = null,
        inheritedTime: Instant? = null,
        isTimeInheritedFromTrack: Boolean = false,
        inheritedEle: Double? = null,
        repo: GeocodingRepository? = null
    ) {
        if (repo != null) {
            this.repository = repo
        }

        // Initialize Waypoint field states
        if (initialWaypoint != null) {
            _nameState.value = initialWaypoint.name
            _descriptionState.value = initialWaypoint.desc ?: ""
            _symbolState.value = GpxWaypoint.normalizeSymbol(initialWaypoint.sym)
            _selectedTime.value = initialWaypoint.time ?: Instant.now()
            _isTimeInheritedFromTrack.value = false
            _elevationState.value = initialWaypoint.ele
            if (initialWaypoint.name.isNotBlank()) {
                isUserModifiedName = true
            }
        } else {
            _nameState.value = ""
            _descriptionState.value = ""
            _symbolState.value = GpxWaypoint.DEFAULT_SYMBOL
            isUserModifiedName = false

            // TrackPoint timestamp inheritance
            when {
                inheritedTrackPoint?.time != null -> {
                    _selectedTime.value = inheritedTrackPoint.time
                    _isTimeInheritedFromTrack.value = true
                    _elevationState.value = inheritedTrackPoint.ele
                }
                inheritedTime != null -> {
                    _selectedTime.value = inheritedTime
                    _isTimeInheritedFromTrack.value = isTimeInheritedFromTrack
                    _elevationState.value = inheritedEle
                }
                else -> {
                    _selectedTime.value = Instant.now()
                    _isTimeInheritedFromTrack.value = false
                    _elevationState.value = inheritedEle
                }
            }
        }

        // Coordinates resolution
        val resolvedLat = initialWaypoint?.lat ?: inheritedTrackPoint?.lat ?: lat
        val resolvedLon = initialWaypoint?.lon ?: inheritedTrackPoint?.lon ?: lon

        if (resolvedLat != null && resolvedLon != null && !(resolvedLat == 0.0 && resolvedLon == 0.0)) {
            _latTextState.value = String.format(Locale.US, "%.6f", resolvedLat)
            _lonTextState.value = String.format(Locale.US, "%.6f", resolvedLon)
            _isCoordError.value = false

            // If name is empty, trigger reverse geocoding asynchronously
            if (_nameState.value.isBlank()) {
                fetchReverseGeocoding(resolvedLat, resolvedLon)
            }
        } else {
            _latTextState.value = ""
            _lonTextState.value = ""
        }

        _isNameError.value = false
        initialized = true
    }

    /**
     * Executes reverse geocoding off Main thread.
     * Respects user input: if user already typed a name, ignores geocoded result.
     */
    fun fetchReverseGeocoding(lat: Double, lon: Double) {
        val repo = repository ?: return
        viewModelScope.launch(ioDispatcher) {
            _isGeocodingLoading.value = true
            try {
                val placeName = repo.reverseGeocode(lat, lon)
                if (!placeName.isNullOrBlank()) {
                    // Do not overwrite if user has already modified name or entered non-blank text
                    if (!isUserModifiedName && _nameState.value.isBlank()) {
                        _nameState.value = placeName
                        _isNameError.value = false
                    }
                }
            } catch (_: Exception) {
                // Graceful fallback: do nothing on failure
            } finally {
                _isGeocodingLoading.value = false
            }
        }
    }

    fun onNameChanged(newName: String) {
        isUserModifiedName = true
        _nameState.value = newName
        if (newName.isNotBlank()) {
            _isNameError.value = false
        }
    }

    fun onDescriptionChanged(newDesc: String) {
        _descriptionState.value = newDesc
    }

    fun onSymbolChanged(newSym: String) {
        _symbolState.value = GpxWaypoint.normalizeSymbol(newSym)
    }

    fun onCoordinatesChanged(latStr: String, lonStr: String) {
        _latTextState.value = latStr
        _lonTextState.value = lonStr
        _isCoordError.value = false
    }

    fun onLatChanged(latStr: String) {
        _latTextState.value = latStr
        _isCoordError.value = false
    }

    fun onLonChanged(lonStr: String) {
        _lonTextState.value = lonStr
        _isCoordError.value = false
    }

    /**
     * Validates current inputs and builds a [GpxWaypoint].
     * Returns null if name is blank or coordinates are invalid.
     */
    fun buildWaypointIfValid(): GpxWaypoint? {
        val name = _nameState.value.trim()
        val lat = _latTextState.value.toDoubleOrNull()
        val lon = _lonTextState.value.toDoubleOrNull()

        var hasError = false
        if (name.isBlank()) {
            _isNameError.value = true
            hasError = true
        }

        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            _isCoordError.value = true
            hasError = true
        }

        if (hasError) return null

        return GpxWaypoint(
            lat = lat!!,
            lon = lon!!,
            name = name,
            desc = _descriptionState.value.trim().ifEmpty { null },
            sym = GpxWaypoint.normalizeSymbol(_symbolState.value),
            ele = _elevationState.value,
            time = _selectedTime.value
        )
    }

    /**
     * Factory for creating AddWaypointViewModel with dependencies.
     */
    class Factory(
        private val repository: GeocodingRepository,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AddWaypointViewModel(repository, ioDispatcher) as T
        }
    }
}
