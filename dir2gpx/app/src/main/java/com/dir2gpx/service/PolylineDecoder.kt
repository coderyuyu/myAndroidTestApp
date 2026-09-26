package com.dir2gpx.service

import com.dir2gpx.model.RoutePoint

/**
 * Decodes Google's Encoded Polyline Algorithm format into a list of [RoutePoint].
 *
 * The algorithm uses variable-length encoding with a precision of 1e-5 degrees.
 * See: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object PolylineDecoder {

    /**
     * Decodes an encoded polyline string into a list of [RoutePoint].
     *
     * @param encoded The encoded polyline string.
     * @return An ordered list of [RoutePoint] with TRACK type.
     * @throws IllegalArgumentException if the encoded string is malformed.
     */
    fun decode(encoded: String): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        var index = 0
        var lat = 0
        var lng = 0
        val length = encoded.length

        while (index < length) {
            // Decode latitude
            var shift = 0
            var result = 0
            var byte: Int
            do {
                require(index < length) { "Unexpected end of encoded polyline at index $index" }
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1F) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            // Decode longitude
            shift = 0
            result = 0
            do {
                require(index < length) { "Unexpected end of encoded polyline at index $index" }
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1F) shl shift)
                shift += 5
            } while (byte >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(
                RoutePoint(
                    latitude = lat / 1e5,
                    longitude = lng / 1e5
                )
            )
        }

        return points
    }
}
