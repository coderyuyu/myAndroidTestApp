package com.gpxedt.app.model

/**
 * Waypoint sort criteria:
 * - MANUAL: Original sequence as defined in the GPX track
 * - NAME_ASC: Alphabetical sorting by name (case-insensitive)
 * - TIME_DESC: Chronological sorting by creation timestamp (newest first)
 */
enum class WaypointSortOrder(val displayName: String) {
    MANUAL("Manual (Original Sequence)"),
    NAME_ASC("Name (A-Z)"),
    TIME_DESC("Time (Newest First)")
}
