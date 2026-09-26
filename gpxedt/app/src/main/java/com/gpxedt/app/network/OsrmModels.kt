package com.gpxedt.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OsrmRouteResponse(
    @SerialName("code")
    val code: String, // "Ok", "NoRoute", "InvalidQuery", etc.
    @SerialName("message")
    val message: String? = null,
    @SerialName("routes")
    val routes: List<OsrmRoute> = emptyList(),
    @SerialName("waypoints")
    val waypoints: List<OsrmWaypoint> = emptyList()
)

@Serializable
data class OsrmRoute(
    @SerialName("geometry")
    val geometry: OsrmGeometry,
    @SerialName("distance")
    val distance: Double? = null, // meters
    @SerialName("duration")
    val duration: Double? = null, // seconds
    @SerialName("weight")
    val weight: Double? = null
)

@Serializable
data class OsrmGeometry(
    @SerialName("type")
    val type: String, // "LineString"
    @SerialName("coordinates")
    val coordinates: List<List<Double>> // [[lon, lat], ...]
)

@Serializable
data class OsrmWaypoint(
    @SerialName("name")
    val name: String? = null,
    @SerialName("distance")
    val distance: Double? = null,
    @SerialName("location")
    val location: List<Double> = emptyList() // [lon, lat]
)
