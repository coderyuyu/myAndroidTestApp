package com.gpxedt.app.parser

import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.TrackPoint
import com.gpxedt.app.model.Waypoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object GpxParser {

    /**
     * Parses an InputStream containing GPX 1.1 XML data into a GpxData object.
     */
    fun parse(inputStream: InputStream): GpxData {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var trackName: String? = null
        val trackPoints = mutableListOf<TrackPoint>()
        val waypoints = mutableListOf<Waypoint>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "trk" -> {
                        // Enter track parsing
                        val (name, points) = parseTrack(parser)
                        if (name != null) {
                            trackName = name
                        }
                        trackPoints.addAll(points)
                    }
                    "wpt" -> {
                        val wpt = parseWaypoint(parser)
                        if (wpt != null) {
                            waypoints.add(wpt)
                        }
                    }
                    "metadata" -> {
                        val metaName = parseMetadataName(parser)
                        if (trackName == null && metaName != null) {
                            trackName = metaName
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return GpxData(
            name = trackName,
            trackPoints = trackPoints,
            waypoints = waypoints
        )
    }

    private fun parseMetadataName(parser: XmlPullParser): String? {
        var name: String? = null
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "name") {
                name = parser.nextText()
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "metadata") {
                break
            }
            eventType = parser.next()
        }
        return name
    }

    private fun parseTrack(parser: XmlPullParser): Pair<String?, List<TrackPoint>> {
        var trackName: String? = null
        val points = mutableListOf<TrackPoint>()

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> trackName = parser.nextText()
                    "trkseg" -> points.addAll(parseTrackSegment(parser))
                }
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "trk") {
                break
            }
            eventType = parser.next()
        }
        return Pair(trackName, points)
    }

    private fun parseTrackSegment(parser: XmlPullParser): List<TrackPoint> {
        val segmentPoints = mutableListOf<TrackPoint>()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val point = parseTrackPoint(parser)
                if (point != null) {
                    segmentPoints.add(point)
                }
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "trkseg") {
                break
            }
            eventType = parser.next()
        }
        return segmentPoints
    }

    private fun parseTrackPoint(parser: XmlPullParser): TrackPoint? {
        val latStr = parser.getAttributeValue(null, "lat")
        val lonStr = parser.getAttributeValue(null, "lon")
        val lat = latStr?.toDoubleOrNull() ?: return null
        val lon = lonStr?.toDoubleOrNull() ?: return null

        var ele: Double? = null
        var time: Instant? = null

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ele" -> ele = parser.nextText().toDoubleOrNull()
                    "time" -> time = parseIsoInstant(parser.nextText())
                }
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "trkpt") {
                break
            }
            eventType = parser.next()
        }

        return TrackPoint(lat, lon, ele, time)
    }

    private fun parseWaypoint(parser: XmlPullParser): Waypoint? {
        val latStr = parser.getAttributeValue(null, "lat")
        val lonStr = parser.getAttributeValue(null, "lon")
        val lat = latStr?.toDoubleOrNull() ?: return null
        val lon = lonStr?.toDoubleOrNull() ?: return null

        var name = "Waypoint"
        var desc: String? = null
        var sym: String? = null
        var ele: Double? = null
        var time: Instant? = null

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "name" -> name = parser.nextText()
                    "desc" -> desc = parser.nextText()
                    "sym" -> sym = parser.nextText()
                    "ele" -> ele = parser.nextText().toDoubleOrNull()
                    "time" -> time = parseIsoInstant(parser.nextText())
                }
            } else if (eventType == XmlPullParser.END_TAG && parser.name == "wpt") {
                break
            }
            eventType = parser.next()
        }

        return Waypoint(
            lat = lat,
            lon = lon,
            name = name,
            desc = desc,
            sym = sym,
            ele = ele,
            time = time
        )
    }

    fun parseIsoInstant(text: String): Instant? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Instant.parse(trimmed)
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            } catch (e2: Exception) {
                null
            }
        }
    }
}
