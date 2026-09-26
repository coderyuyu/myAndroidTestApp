package com.gpxedt.app.network

import com.gpxedt.app.model.TrackPoint
import java.time.Duration
import java.time.Instant

object TimeInterpolator {

    /**
     * Interpolates timestamps for newly generated route points between startPoint (T_A) and endPoint (T_B).
     * The interpolation is proportional to cumulative distance along the generated route.
     */
    fun interpolateRouteTimestamps(
        startPoint: TrackPoint,
        endPoint: TrackPoint,
        generatedPoints: List<TrackPoint>
    ): List<TrackPoint> {
        if (generatedPoints.isEmpty()) return emptyList()

        val timeA = startPoint.time
        val timeB = endPoint.time

        // If no start time, we cannot interpolate meaningful absolute timestamps
        if (timeA == null && timeB == null) {
            return generatedPoints
        }

        // Calculate cumulative distances along the generated route
        val distances = mutableListOf<Double>()
        var cumulativeDist = 0.0
        distances.add(0.0)

        for (i in 0 until generatedPoints.size - 1) {
            val d = generatedPoints[i].distanceTo(generatedPoints[i + 1])
            cumulativeDist += d
            distances.add(cumulativeDist)
        }

        val totalDist = cumulativeDist

        return if (timeA != null && timeB != null && timeB.isAfter(timeA)) {
            val totalDurationMillis = Duration.between(timeA, timeB).toMillis()
            generatedPoints.mapIndexed { index, pt ->
                val progress = if (totalDist > 0) (distances[index] / totalDist) else (index.toDouble() / (generatedPoints.size - 1).coerceAtLeast(1))
                val interpolatedMillis = (totalDurationMillis * progress).toLong()
                val interpolatedTime = timeA.plusMillis(interpolatedMillis)
                pt.copy(time = interpolatedTime)
            }
        } else if (timeA != null) {
            // Assume an average walking/cycling pace of ~4 m/s (14.4 km/h) if end time is missing
            val defaultSpeedMps = 4.0
            generatedPoints.mapIndexed { index, pt ->
                val secondsOffset = (distances[index] / defaultSpeedMps).toLong()
                pt.copy(time = timeA.plusSeconds(secondsOffset))
            }
        } else {
            // timeB is present, timeA is null: back-calculate
            val defaultSpeedMps = 4.0
            generatedPoints.mapIndexed { index, pt ->
                val remainingDist = totalDist - distances[index]
                val secondsBefore = (remainingDist / defaultSpeedMps).toLong()
                pt.copy(time = timeB!!.minusSeconds(secondsBefore))
            }
        }
    }
}
