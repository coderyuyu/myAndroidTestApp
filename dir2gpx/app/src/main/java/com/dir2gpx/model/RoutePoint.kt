package com.dir2gpx.model

import java.time.Instant

/**
 * Represents a single geographic point along a route.
 *
 * @param latitude WGS-84 latitude in decimal degrees.
 * @param longitude WGS-84 longitude in decimal degrees.
 * @param name Human-readable label (e.g. "Start", "Via 1", "Destination").
 * @param type Classification of this point for GPX output and marker styling.
 * @param timestamp Interpolated UTC timestamp for GPX time elements.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
    val type: PointType = PointType.TRACK,
    val timestamp: Instant? = null
)

/**
 * Classification of a route point determining its GPX element and map marker style.
 */
enum class PointType {
    /** Route origin — rendered as green marker, GPX `<wpt>`. */
    ORIGIN,

    /** Route destination — rendered as red marker, GPX `<wpt>`. */
    DESTINATION,

    /** Intermediate via-stop — rendered as blue/amber marker, GPX `<wpt>`. */
    VIA,

    /** Standard track point — part of `<trkseg>`, no standalone marker. */
    TRACK
}
