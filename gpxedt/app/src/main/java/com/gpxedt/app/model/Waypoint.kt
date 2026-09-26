package com.gpxedt.app.model

import java.time.Instant

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val name: String,
    val desc: String? = null,
    val sym: String? = null,
    val ele: Double? = null,
    val time: Instant? = null
)
