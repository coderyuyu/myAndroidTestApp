package com.gpxedt.app.model

data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
}

data class GpxData(
    val name: String? = null,
    val description: String? = null,
    val trackPoints: List<TrackPoint> = emptyList(),
    val waypoints: List<Waypoint> = emptyList()
) {
    val totalDistanceMeters: Double by lazy {
        if (trackPoints.size < 2) return@lazy 0.0
        var dist = 0.0
        for (i in 0 until trackPoints.size - 1) {
            dist += trackPoints[i].distanceTo(trackPoints[i + 1])
        }
        dist
    }

    val boundingBox: BoundingBox? by lazy {
        val allPoints = mutableListOf<Pair<Double, Double>>()
        trackPoints.forEach { allPoints.add(it.lat to it.lon) }
        waypoints.forEach { allPoints.add(it.lat to it.lon) }

        if (allPoints.isEmpty()) return@lazy null

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        allPoints.forEach { (lat, lon) ->
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }

        BoundingBox(minLat, maxLat, minLon, maxLon)
    }
}
