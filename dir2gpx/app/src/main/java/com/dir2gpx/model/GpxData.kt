package com.dir2gpx.model

import java.time.Duration

/**
 * Complete GPX data container holding all information needed for:
 * - GPX 1.1 XML serialization
 * - OSMDroid map visualization
 * - Route statistics overlay
 *
 * @param waypoints Named waypoints (Origin, Destination, Via) for GPX `<wpt>` elements.
 * @param trackPoints Ordered sequence of track points for GPX `<trkseg>` → `<trkpt>`.
 * @param name Route name used in GPX `<metadata><name>` and `<trk><name>`.
 * @param totalDistanceKm Total route distance in kilometers (Haversine sum).
 * @param duration Time span from start to end (EndTime − StartTime).
 * @param gpxXml The serialized GPX 1.1 XML string, ready for export.
 */
data class GpxData(
    val waypoints: List<RoutePoint>,
    val trackPoints: List<RoutePoint>,
    val name: String,
    val totalDistanceKm: Double,
    val duration: Duration,
    val gpxXml: String
)
