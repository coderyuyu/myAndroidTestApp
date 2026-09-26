package com.gpxedt.app.engine

import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint

class GpxEditEngine(initialData: GpxData = GpxData()) {

    private val maxHistorySize = 50
    private val undoStack = ArrayDeque<GpxData>()
    private val redoStack = ArrayDeque<GpxData>()

    var currentData: GpxData = initialData
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Resets or loads new GPX data, clearing undo/redo history.
     */
    fun loadData(data: GpxData) {
        undoStack.clear()
        redoStack.clear()
        currentData = data
    }

    private fun pushState() {
        if (undoStack.size >= maxHistorySize) {
            undoStack.removeFirst()
        }
        undoStack.addLast(currentData)
        redoStack.clear()
    }

    /**
     * Undoes the last operation. Returns the restored GpxData, or null if cannot undo.
     */
    fun undo(): GpxData? {
        if (!canUndo) return null
        val previousState = undoStack.removeLast()
        redoStack.addLast(currentData)
        currentData = previousState
        return currentData
    }

    /**
     * Redoes the last undone operation. Returns the redone GpxData, or null if cannot redo.
     */
    fun redo(): GpxData? {
        if (!canRedo) return null
        val nextState = redoStack.removeLast()
        undoStack.addLast(currentData)
        currentData = nextState
        return currentData
    }

    /**
     * 1. Trim Start (去頭):
     * Given selected index i, delete all points from index 0 to i-1.
     * Point i becomes the new start (index 0).
     */
    fun trimStart(index: Int): GpxData {
        val points = currentData.trackPoints
        if (points.isEmpty() || index <= 0 || index >= points.size) {
            return currentData
        }

        pushState()
        val newPoints = points.subList(index, points.size).toList()
        currentData = currentData.copy(trackPoints = newPoints)
        return currentData
    }

    /**
     * 2. Trim End (去尾):
     * Given selected index j, delete all points from index j+1 to end.
     * Point j becomes the new end.
     */
    fun trimEnd(index: Int): GpxData {
        val points = currentData.trackPoints
        if (points.isEmpty() || index < 0 || index >= points.size - 1) {
            return currentData
        }

        pushState()
        val newPoints = points.subList(0, index + 1).toList()
        currentData = currentData.copy(trackPoints = newPoints)
        return currentData
    }

    /**
     * Span: Sets the track to the range between startIndex and endIndex.
     * Drops points before startIndex and points after endIndex.
     */
    fun span(startIndex: Int, endIndex: Int): GpxData {
        val points = currentData.trackPoints
        if (points.isEmpty() || startIndex < 0 || endIndex >= points.size || startIndex >= endIndex) {
            return currentData
        }

        pushState()
        val newPoints = points.subList(startIndex, endIndex + 1).toList()
        currentData = currentData.copy(trackPoints = newPoints)
        return currentData
    }

    /**
     * 3. Replace Segment with Online Route (線上重繞取代):
     * User selects Start Point P_A (index i) and End Point P_B (index j, where j > i).
     * Splicing: newTrack = original[0..i] + generatedRoute[1..k-1] + original[j..end].
     */
    fun replaceSegment(
        startIndex: Int,
        endIndex: Int,
        generatedRoute: List<TrackPoint>
    ): GpxData {
        val points = currentData.trackPoints
        if (startIndex < 0 || endIndex >= points.size || startIndex >= endIndex) {
            return currentData
        }

        pushState()

        val head = points.subList(0, startIndex + 1)
        val tail = points.subList(endIndex, points.size)

        // Middle points from generated route: exclude the 1st and last if they duplicate the endpoints
        val middle = if (generatedRoute.size > 2) {
            generatedRoute.subList(1, generatedRoute.size - 1)
        } else {
            emptyList()
        }

        val newPoints = ArrayList<TrackPoint>(head.size + middle.size + tail.size)
        newPoints.addAll(head)
        newPoints.addAll(middle)
        newPoints.addAll(tail)

        currentData = currentData.copy(trackPoints = newPoints)
        return currentData
    }

    /**
     * Adds a new Waypoint.
     */
    fun addWaypoint(waypoint: Waypoint): GpxData {
        pushState()
        val updatedWaypoints = currentData.waypoints + waypoint
        currentData = currentData.copy(waypoints = updatedWaypoints)
        return currentData
    }

    /**
     * Removes an existing Waypoint.
     */
    fun removeWaypoint(waypoint: Waypoint): GpxData {
        pushState()
        val updatedWaypoints = currentData.waypoints - waypoint
        currentData = currentData.copy(waypoints = updatedWaypoints)
        return currentData
    }

    /**
     * Updates an existing Waypoint.
     */
    fun updateWaypoint(oldWaypoint: Waypoint, newWaypoint: Waypoint): GpxData {
        pushState()
        val updatedWaypoints = currentData.waypoints.map {
            if (it == oldWaypoint) newWaypoint else it
        }
        currentData = currentData.copy(waypoints = updatedWaypoints)
        return currentData
    }
}
