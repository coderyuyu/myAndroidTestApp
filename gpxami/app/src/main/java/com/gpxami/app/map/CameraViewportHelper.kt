package com.gpxami.app.map

import com.gpxami.app.data.model.GpxTrack
import kotlin.math.*

/**
 * Shared camera viewport helper for both real-time Playback and off-screen Video Export.
 * Ensures consistent initial camera framing, zoom levels, and start point visibility checks.
 */
object CameraViewportHelper {

    data class InitialCameraState(
        val isStartInViewport: Boolean,
        val zoomOffset: Double,
        val panOffsetX: Float,
        val panOffsetY: Float,
        val centerLat: Double,
        val centerLon: Double
    )

    /**
     * Determines whether the effective starting point of [track] (the first point of the active sub-track)
     * is currently visible within the visible map viewport.
     *
     * Specification:
     * 1. If start point is within viewport:
     *    Retains user's current zoom level ([currentZoomOffset]) and panning ([currentPanX], [currentPanY]).
     * 2. If start point is OUTSIDE viewport:
     *    Automatically centers on the start point and resets to optimal zoom level ([zoomOffset] = 0.0, zero pan).
     */
    fun determineInitialCamera(
        track: GpxTrack,
        viewportWidth: Float,
        viewportHeight: Float,
        currentZoomOffset: Double,
        currentPanX: Float,
        currentPanY: Float,
        mapRotation: Float = 0f,
        showElevationProfile: Boolean = true,
        focusPoint: Pair<Double, Double>? = null,
        currentProgress: Float = 0.0f
    ): InitialCameraState {
        val startPt = track.points.firstOrNull() ?: return InitialCameraState(
            isStartInViewport = true,
            zoomOffset = currentZoomOffset,
            panOffsetX = currentPanX,
            panOffsetY = currentPanY,
            centerLat = track.bounds.centerLat,
            centerLon = track.bounds.centerLon
        )

        val w = if (viewportWidth > 0f) viewportWidth else 1080f
        val h = if (viewportHeight > 0f) viewportHeight else 607.5f

        // 1. Calculate optimal base overview zoom for active route
        val baseOverviewZoom = MapRenderer.calculateOptimalOverviewZoom(
            track = track,
            viewportWidth = w,
            viewportHeight = h,
            rotationDegrees = mapRotation,
            showElevationProfile = showElevationProfile
        )
        val activeZoom = baseOverviewZoom + 1.0 + currentZoomOffset

        // 2. Determine current camera anchor point before pan
        val (anchorLat, anchorLon) = when {
            focusPoint != null -> focusPoint
            currentProgress > 0.0f -> {
                val interp = track.interpolate(currentProgress)
                Pair(interp.lat, interp.lon)
            }
            else -> Pair(startPt.lat, startPt.lon)
        }

        val (anchorWx, anchorWy) = MapRenderer.projectLatLon(anchorLat, anchorLon, activeZoom)
        val effectiveCamWx = anchorWx - currentPanX
        val effectiveCamWy = anchorWy - currentPanY

        val halfW = w / 2.0
        val halfH = h / 2.0

        // 3. Project start point coordinates into unrotated screen pixel coordinates
        val (startWx, startWy) = MapRenderer.projectLatLon(startPt.lat, startPt.lon, activeZoom)
        val sx = (startWx - effectiveCamWx + halfW).toFloat()
        val sy = (startWy - effectiveCamWy + halfH).toFloat()

        // 4. Apply rotation around viewport center (halfW, halfH)
        val rad = Math.toRadians(mapRotation.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        val dx = sx - halfW.toFloat()
        val dy = sy - halfH.toFloat()
        val rx = halfW.toFloat() + dx * cosR - dy * sinR
        val ry = halfH.toFloat() + dx * sinR + dy * cosR

        // 5. Evaluate if start point falls within visible viewport area (with 5% edge padding)
        val minX = w * 0.05f
        val maxX = w * 0.95f
        val minY = h * 0.05f
        // Bottom 20% reserved for elevation profile if shown
        val maxY = if (showElevationProfile) h * 0.78f else h * 0.95f

        val inViewport = (rx in minX..maxX) && (ry in minY..maxY)

        return if (inViewport) {
            InitialCameraState(
                isStartInViewport = true,
                zoomOffset = currentZoomOffset,
                panOffsetX = currentPanX,
                panOffsetY = currentPanY,
                centerLat = anchorLat,
                centerLon = anchorLon
            )
        } else {
            InitialCameraState(
                isStartInViewport = false,
                zoomOffset = 0.0, // Optimal zoom level
                panOffsetX = 0f,
                panOffsetY = 0f,
                centerLat = startPt.lat,
                centerLon = startPt.lon
            )
        }
    }
}
