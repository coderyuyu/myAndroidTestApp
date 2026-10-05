package com.gpxedt.app.ui.waypoint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.model.WaypointSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Dedicated ViewModel for waypoint management:
 * - Reactive sorting pipeline via [sortedWaypoints] off Dispatchers.Default
 * - Restricted symbol state ([selectedSymbol]) defaulted to [GpxWaypoint.SYM_FLAG_RED]
 * - Unidirectional Data Flow (UDF) without in-place mutations
 */
class WaypointViewModel : ViewModel() {

    private val _waypoints = MutableStateFlow<List<GpxWaypoint>>(emptyList())
    val waypoints: StateFlow<List<GpxWaypoint>> = _waypoints.asStateFlow()

    private val _sortOrder = MutableStateFlow(WaypointSortOrder.MANUAL)
    val sortOrder: StateFlow<WaypointSortOrder> = _sortOrder.asStateFlow()

    private val _selectedSymbol = MutableStateFlow(GpxWaypoint.DEFAULT_SYMBOL)
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    val sortedWaypoints: StateFlow<List<GpxWaypoint>> = combine(_waypoints, _sortOrder) { list, order ->
        sortWaypointList(list, order)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setWaypoints(list: List<GpxWaypoint>) {
        _waypoints.value = list
    }

    fun setSortOrder(order: WaypointSortOrder) {
        _sortOrder.value = order
    }

    fun setSelectedSymbol(symbol: String) {
        _selectedSymbol.value = GpxWaypoint.normalizeSymbol(symbol)
    }

    fun addWaypoint(
        name: String,
        desc: String? = null,
        sym: String? = _selectedSymbol.value,
        lat: Double,
        lon: Double,
        time: Instant? = null,
        ele: Double? = null
    ) {
        val newWpt = GpxWaypoint(
            lat = lat,
            lon = lon,
            name = name,
            desc = desc,
            sym = GpxWaypoint.normalizeSymbol(sym),
            ele = ele,
            time = time
        )
        _waypoints.value = _waypoints.value + newWpt
    }

    fun updateWaypoint(oldWaypoint: GpxWaypoint, newWaypoint: GpxWaypoint) {
        _waypoints.value = _waypoints.value.map {
            if (it == oldWaypoint) newWaypoint else it
        }
    }

    fun deleteWaypoint(waypoint: GpxWaypoint) {
        _waypoints.value = _waypoints.value - waypoint
    }

    companion object {
        /**
         * Sorts a list of waypoints according to [WaypointSortOrder].
         * Safe for empty or single-element lists without index exceptions.
         */
        fun sortWaypointList(list: List<GpxWaypoint>, order: WaypointSortOrder): List<GpxWaypoint> {
            if (list.size <= 1) return list
            return when (order) {
                WaypointSortOrder.MANUAL -> list
                WaypointSortOrder.NAME_ASC -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                WaypointSortOrder.TIME_DESC -> list.sortedWith { a, b ->
                    when {
                        a.time != null && b.time != null -> b.time.compareTo(a.time)
                        a.time != null -> -1
                        b.time != null -> 1
                        else -> 0
                    }
                }
            }
        }
    }
}
