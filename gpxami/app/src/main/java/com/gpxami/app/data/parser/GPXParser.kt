package com.gpxami.app.data.parser

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.gpxami.app.data.model.GeoBounds
import com.gpxami.app.data.model.GpxPoint
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.GpxWaypoint
import com.gpxami.app.data.model.RawGpxPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

object GPXParser {

    /**
     * Strips UTF-8 Byte Order Mark (0xEF, 0xBB, 0xBF) if present at start of stream.
     */
    private fun getBomStrippedStream(rawStream: InputStream): InputStream {
        val buffered = if (rawStream.markSupported()) rawStream else rawStream.buffered()
        buffered.mark(3)
        val bom = ByteArray(3)
        val n = buffered.read(bom, 0, 3)
        if (n == 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte()) {
            // Discarded UTF-8 BOM
        } else {
            buffered.reset()
        }
        return buffered
    }

    data class ParsedTrack(
        val name: String?,
        val points: List<RawGpxPoint>
    )

    data class ParsedRoute(
        val name: String?,
        val points: List<RawGpxPoint>
    )

    data class GpxParseData(
        val globalName: String?,
        val tracks: List<ParsedTrack>,
        val routes: List<ParsedRoute>,
        val waypoints: List<GpxWaypoint>
    )

    /**
     * Parses a GPX stream asynchronously via Coroutines on Dispatchers.IO.
     */
    suspend fun parse(inputStream: InputStream): Result<GpxTrack> = withContext(Dispatchers.IO) {
        try {
            val bytes = getBomStrippedStream(inputStream).readBytes()
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("選取的 GPX 檔案為空 (0 bytes)"))
            }
            val parsedData = try {
                val pullParser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(bytes.inputStream(), null)
                }
                parseWithPullParser(pullParser)
            } catch (_: Throwable) {
                // When running in JVM unit tests where android.util.Xml is stubbed/unmocked,
                // fall back to the standard Java SAXParserFactory.
                parseWithSax(bytes.inputStream())
            }

            val (trackName, rawPoints) = resolveBestTrack(parsedData)

            if (rawPoints.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("檔案中未包含有效的 <trk> 航跡資料 (No valid <trk> track data found in GPX)"))
            }

            // Post-process raw points into full GpxTrack with interpolation and sanitization
            val processedTrack = processTrackPoints(trackName ?: "GPX Route", rawPoints, parsedData.waypoints)
            Result.success(processedTrack)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseWithPullParser(parser: XmlPullParser): GpxParseData {
        var eventType = parser.eventType
        var globalName: String? = null
        val tracks = mutableListOf<ParsedTrack>()
        val routes = mutableListOf<ParsedRoute>()
        val waypoints = mutableListOf<GpxWaypoint>()

        var currentTrackName: String? = null
        val currentTrackPoints = mutableListOf<RawGpxPoint>()

        var currentRouteName: String? = null
        val currentRoutePoints = mutableListOf<RawGpxPoint>()

        var currentWptName: String? = null
        var currentWptDesc: String? = null

        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTime: Long? = null

        val tagStack = ArrayDeque<String>()
        val textBuilder = java.lang.StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawName = parser.name ?: ""
                    val localName = rawName.substringAfterLast(':').lowercase(Locale.ROOT)
                    tagStack.addLast(localName)
                    textBuilder.setLength(0)

                    when (localName) {
                        "trk" -> {
                            currentTrackName = null
                            currentTrackPoints.clear()
                        }
                        "rte" -> {
                            currentRouteName = null
                            currentRoutePoints.clear()
                        }
                        "wpt" -> {
                            currentWptName = null
                            currentWptDesc = null
                        }
                    }
                    when (localName) {
                        "trkpt", "rtept", "wpt" -> {
                            var lat: Double? = null
                            var lon: Double? = null
                            for (i in 0 until parser.attributeCount) {
                                val attrName = parser.getAttributeName(i).substringAfterLast(':').lowercase(Locale.ROOT)
                                val attrVal = parser.getAttributeValue(i)?.trim()?.replace(',', '.')
                                when (attrName) {
                                    "lat", "latitude" -> lat = attrVal?.toDoubleOrNull()
                                    "lon", "lng", "longitude" -> lon = attrVal?.toDoubleOrNull()
                                }
                            }
                            currentLat = lat
                            currentLon = lon
                            currentEle = null
                            currentTime = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    textBuilder.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val rawName = parser.name ?: ""
                    val localName = rawName.substringAfterLast(':').lowercase(Locale.ROOT)
                    val text = textBuilder.toString().trim()

                    when (localName) {
                        "name" -> {
                            if (text.isNotEmpty()) {
                                if (tagStack.contains("wpt") && currentWptName == null) {
                                    currentWptName = text
                                } else if (tagStack.contains("trk") && currentTrackName == null) {
                                    currentTrackName = text
                                } else if (tagStack.contains("rte") && currentRouteName == null) {
                                    currentRouteName = text
                                } else if (tagStack.contains("metadata") && globalName == null) {
                                    globalName = text
                                }
                            }
                        }
                        "desc" -> {
                            if (text.isNotEmpty() && tagStack.contains("wpt") && currentWptDesc == null) {
                                currentWptDesc = text
                            }
                        }
                        "ele" -> {
                            if (text.isNotEmpty()) {
                                currentEle = text.replace(',', '.').toDoubleOrNull()
                            }
                        }
                        "time" -> {
                            if (text.isNotEmpty()) {
                                currentTime = parseIsoTimestamp(text)
                            }
                        }
                        "trkpt" -> {
                            if (currentLat != null && currentLon != null) {
                                currentTrackPoints.add(
                                    RawGpxPoint(
                                        lat = currentLat!!,
                                        lon = currentLon!!,
                                        ele = currentEle,
                                        time = currentTime
                                    )
                                )
                            }
                            currentLat = null
                            currentLon = null
                            currentEle = null
                            currentTime = null
                        }
                        "rtept" -> {
                            if (currentLat != null && currentLon != null) {
                                currentRoutePoints.add(
                                    RawGpxPoint(
                                        lat = currentLat!!,
                                        lon = currentLon!!,
                                        ele = currentEle,
                                        time = currentTime
                                    )
                                )
                            }
                            currentLat = null
                            currentLon = null
                            currentEle = null
                            currentTime = null
                        }
                        "wpt" -> {
                            if (currentLat != null && currentLon != null) {
                                waypoints.add(
                                    GpxWaypoint(
                                        lat = currentLat!!,
                                        lon = currentLon!!,
                                        elevation = currentEle,
                                        name = currentWptName,
                                        desc = currentWptDesc
                                    )
                                )
                            }
                            currentLat = null
                            currentLon = null
                            currentEle = null
                            currentTime = null
                            currentWptName = null
                            currentWptDesc = null
                        }
                        "trk" -> {
                            if (currentTrackPoints.isNotEmpty()) {
                                tracks.add(ParsedTrack(currentTrackName, currentTrackPoints.toList()))
                                currentTrackPoints.clear()
                                currentTrackName = null
                            }
                        }
                        "rte" -> {
                            if (currentRoutePoints.isNotEmpty()) {
                                routes.add(ParsedRoute(currentRouteName, currentRoutePoints.toList()))
                                currentRoutePoints.clear()
                                currentRouteName = null
                            }
                        }
                    }
                    if (tagStack.isNotEmpty()) {
                        tagStack.pollLast()
                    }
                    textBuilder.setLength(0)
                }
            }
            eventType = parser.next()
        }

        if (currentTrackPoints.isNotEmpty()) {
            tracks.add(ParsedTrack(currentTrackName, currentTrackPoints.toList()))
        }
        if (currentRoutePoints.isNotEmpty()) {
            routes.add(ParsedRoute(currentRouteName, currentRoutePoints.toList()))
        }

        return GpxParseData(globalName, tracks, routes, waypoints)
    }

    private fun parseWithSax(stream: InputStream): GpxParseData {
        val factory = javax.xml.parsers.SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        val saxParser = factory.newSAXParser()

        var globalName: String? = null
        val tracks = mutableListOf<ParsedTrack>()
        val routes = mutableListOf<ParsedRoute>()
        val waypoints = mutableListOf<GpxWaypoint>()

        var currentTrackName: String? = null
        val currentTrackPoints = mutableListOf<RawGpxPoint>()

        var currentRouteName: String? = null
        val currentRoutePoints = mutableListOf<RawGpxPoint>()

        var currentWptName: String? = null
        var currentWptDesc: String? = null

        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTime: Long? = null

        val tagStack = ArrayDeque<String>()
        val textBuilder = java.lang.StringBuilder()

        val handler = object : org.xml.sax.helpers.DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: org.xml.sax.Attributes?) {
                val tag = (qName ?: localName ?: "").substringAfterLast(':').lowercase(Locale.ROOT)
                tagStack.addLast(tag)
                textBuilder.setLength(0)
                when (tag) {
                    "trk" -> {
                        currentTrackName = null
                        currentTrackPoints.clear()
                    }
                    "rte" -> {
                        currentRouteName = null
                        currentRoutePoints.clear()
                    }
                    "wpt" -> {
                        currentWptName = null
                        currentWptDesc = null
                    }
                }
                when (tag) {
                    "trkpt", "rtept", "wpt" -> {
                        var lat: Double? = null
                        var lon: Double? = null
                        if (attributes != null) {
                            for (i in 0 until attributes.length) {
                                val attrName = attributes.getQName(i).substringAfterLast(':').lowercase(Locale.ROOT)
                                val attrVal = attributes.getValue(i)?.trim()?.replace(',', '.')
                                when (attrName) {
                                    "lat", "latitude" -> lat = attrVal?.toDoubleOrNull()
                                    "lon", "lng", "longitude" -> lon = attrVal?.toDoubleOrNull()
                                }
                            }
                        }
                        currentLat = lat
                        currentLon = lon
                        currentEle = null
                        currentTime = null
                    }
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (ch != null && length > 0) {
                    textBuilder.append(ch, start, length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (qName ?: localName ?: "").substringAfterLast(':').lowercase(Locale.ROOT)
                val text = textBuilder.toString().trim()
                when (tag) {
                    "name" -> {
                        if (text.isNotEmpty()) {
                            if (tagStack.contains("wpt") && currentWptName == null) {
                                currentWptName = text
                            } else if (tagStack.contains("trk") && currentTrackName == null) {
                                currentTrackName = text
                            } else if (tagStack.contains("rte") && currentRouteName == null) {
                                currentRouteName = text
                            } else if (tagStack.contains("metadata") && globalName == null) {
                                globalName = text
                            }
                        }
                    }
                    "desc" -> {
                        if (text.isNotEmpty() && tagStack.contains("wpt") && currentWptDesc == null) {
                            currentWptDesc = text
                        }
                    }
                    "ele" -> {
                        if (text.isNotEmpty()) {
                            currentEle = text.replace(',', '.').toDoubleOrNull()
                        }
                    }
                    "time" -> {
                        if (text.isNotEmpty()) {
                            currentTime = parseIsoTimestamp(text)
                        }
                    }
                    "trkpt" -> {
                        if (currentLat != null && currentLon != null) {
                            currentTrackPoints.add(
                                RawGpxPoint(
                                    lat = currentLat!!,
                                    lon = currentLon!!,
                                    ele = currentEle,
                                    time = currentTime
                                )
                            )
                        }
                        currentLat = null
                        currentLon = null
                        currentEle = null
                        currentTime = null
                    }
                    "rtept" -> {
                        if (currentLat != null && currentLon != null) {
                            currentRoutePoints.add(
                                RawGpxPoint(
                                    lat = currentLat!!,
                                    lon = currentLon!!,
                                    ele = currentEle,
                                    time = currentTime
                                )
                            )
                        }
                        currentLat = null
                        currentLon = null
                        currentEle = null
                        currentTime = null
                    }
                    "wpt" -> {
                        if (currentLat != null && currentLon != null) {
                            waypoints.add(
                                GpxWaypoint(
                                    lat = currentLat!!,
                                    lon = currentLon!!,
                                    elevation = currentEle,
                                    name = currentWptName,
                                    desc = currentWptDesc
                                )
                            )
                        }
                        currentLat = null
                        currentLon = null
                        currentEle = null
                        currentTime = null
                        currentWptName = null
                        currentWptDesc = null
                    }
                    "trk" -> {
                        if (currentTrackPoints.isNotEmpty()) {
                            tracks.add(ParsedTrack(currentTrackName, currentTrackPoints.toList()))
                            currentTrackPoints.clear()
                            currentTrackName = null
                        }
                    }
                    "rte" -> {
                        if (currentRoutePoints.isNotEmpty()) {
                            routes.add(ParsedRoute(currentRouteName, currentRoutePoints.toList()))
                            currentRoutePoints.clear()
                            currentRouteName = null
                        }
                    }
                }
                if (tagStack.isNotEmpty()) {
                    tagStack.pollLast()
                }
                textBuilder.setLength(0)
            }
        }

        saxParser.parse(stream, handler)

        if (currentTrackPoints.isNotEmpty()) {
            tracks.add(ParsedTrack(currentTrackName, currentTrackPoints.toList()))
        }
        if (currentRoutePoints.isNotEmpty()) {
            routes.add(ParsedRoute(currentRouteName, currentRoutePoints.toList()))
        }

        return GpxParseData(globalName, tracks, routes, waypoints)
    }

    /**
     * Resolves the primary route/track from the parsed GPX elements:
     * - Program ONLY uses <trk> data to animate the track.
     * - Planned routes (<rte>) and standalone waypoints (<wpt>) are never used to animate the track.
     * - Detects and eliminates duplicate overlapping/rough tracks (e.g. coarse overview vs detailed track).
     * - Merges sequential stages (e.g. Day 1, Day 2) where the end of one stage connects to the next.
     */
    private fun resolveBestTrack(data: GpxParseData): Pair<String?, List<RawGpxPoint>> {
        // Strictly use tracks (<trk>) only for animating the track
        val validTracks = data.tracks.filter { it.points.isNotEmpty() }
        if (validTracks.isNotEmpty()) {
            val (name, points) = selectFromTracks(validTracks)
            return Pair(name ?: data.globalName, points)
        }

        // Do NOT use <rte> or <wpt> to animate the track
        return Pair(data.globalName, emptyList())
    }

    private fun selectFromTracks(tracks: List<ParsedTrack>): Pair<String?, List<RawGpxPoint>> {
        if (tracks.size == 1) {
            return Pair(tracks[0].name, tracks[0].points)
        }

        // Check if the tracks are duplicate/alternative loops of the same route
        // E.g. Track 1 is a rough overview (30 points) and Track 2 is detailed (2000 points).
        val areDuplicateLoops = checkAreDuplicateLoops(tracks)
        if (areDuplicateLoops) {
            val bestTrack = tracks.maxByOrNull { it.points.size } ?: tracks[0]
            return Pair(bestTrack.name ?: tracks.firstOrNull { it.name != null }?.name, bestTrack.points)
        }

        // If sequential segments, merge them
        val mergedPoints = mutableListOf<RawGpxPoint>()
        val primaryName = tracks.firstOrNull { it.name != null }?.name
        for (t in tracks) {
            mergedPoints.addAll(t.points)
        }
        return Pair(primaryName, mergedPoints)
    }

    private fun selectFromRoutes(routes: List<ParsedRoute>): Pair<String?, List<RawGpxPoint>> {
        if (routes.size == 1) {
            return Pair(routes[0].name, routes[0].points)
        }
        val tracksEquivalent = routes.map { ParsedTrack(it.name, it.points) }
        val areDuplicate = checkAreDuplicateLoops(tracksEquivalent)
        if (areDuplicate) {
            val bestRoute = routes.maxByOrNull { it.points.size } ?: routes[0]
            return Pair(bestRoute.name ?: routes.firstOrNull { it.name != null }?.name, bestRoute.points)
        }
        val mergedPoints = mutableListOf<RawGpxPoint>()
        val primaryName = routes.firstOrNull { it.name != null }?.name
        for (r in routes) {
            mergedPoints.addAll(r.points)
        }
        return Pair(primaryName, mergedPoints)
    }

    private fun checkAreDuplicateLoops(tracks: List<ParsedTrack>): Boolean {
        if (tracks.size < 2) return false
        val t0 = tracks[0].points
        val t1 = tracks[1].points
        if (t0.isEmpty() || t1.isEmpty()) return false

        val s0 = t0.first()
        val e0 = t0.last()
        val s1 = t1.first()
        val e1 = t1.last()

        val dStartStart = GpxTrack.distanceMeters(s0.lat, s0.lon, s1.lat, s1.lon)
        val dEndEnd = GpxTrack.distanceMeters(e0.lat, e0.lon, e1.lat, e1.lon)
        val dEnd0Start1 = GpxTrack.distanceMeters(e0.lat, e0.lon, s1.lat, s1.lon)
        val maxSpan = max(
            GpxTrack.distanceMeters(s0.lat, s0.lon, e0.lat, e0.lon),
            GpxTrack.distanceMeters(s1.lat, s1.lon, e1.lat, e1.lon)
        )

        // If open route: both start near each other, both end near each other, but end of t0 is far from start of t1
        val tolerance = max(1000.0, maxSpan * 0.25)
        if (dStartStart < tolerance && dEndEnd < tolerance && dEnd0Start1 > max(500.0, maxSpan * 0.3)) {
            return true
        }

        // If closed circuit or vastly different point densities covering similar area
        val sizeRatio = max(t0.size.toDouble() / max(1, t1.size), t1.size.toDouble() / max(1, t0.size))
        if (sizeRatio >= 2.0 && dStartStart < tolerance && dEndEnd < tolerance) {
            return true
        }

        return false
    }

    /**
     * Parses GPX directly from an Android content Uri (Storage Access Framework).
     */
    suspend fun parseFromUri(contentResolver: ContentResolver, uri: Uri): Result<GpxTrack> {
        return withContext(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IllegalArgumentException("無法開啟選取的檔案 (Unable to open stream from URI: $uri)"))
                if (bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("選取的 GPX 檔案為空 (0 bytes)"))
                }
                parse(bytes.inputStream())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Cleans, interpolates missing values, and builds cumulative metrics.
     */
    fun processTrackPoints(
        name: String,
        rawPoints: List<RawGpxPoint>,
        waypoints: List<GpxWaypoint> = emptyList()
    ): GpxTrack {
        if (rawPoints.isEmpty()) return GpxTrack(name = name, waypoints = waypoints)

        // Step 1: Filter blatant GPS glitches (e.g. lat/lon = 0,0 or unrealistic coordinates)
        val validCoords = rawPoints.filter {
            it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && !(it.lat == 0.0 && it.lon == 0.0)
        }
        val pointsToProcess = if (validCoords.isNotEmpty()) validCoords else rawPoints

        // Step 2: Handle missing elevations via linear interpolation
        val interpolatedElevations = interpolateMissingElevations(pointsToProcess)

        // Step 3: Handle missing timestamps via synthetic progression
        val pointsWithTime = ensureTimestamps(interpolatedElevations)

        // Step 4: Calculate cumulative distances, speeds, gradients, bounds
        var cumulativeMeters = 0.0
        var minEle = Double.MAX_VALUE
        var maxEle = Double.MIN_VALUE
        var totalAscent = 0.0
        var totalDescent = 0.0

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        // Include static waypoints in bounding box so camera captures them
        for (wpt in waypoints) {
            minLat = min(minLat, wpt.lat)
            maxLat = max(maxLat, wpt.lat)
            minLon = min(minLon, wpt.lon)
            maxLon = max(maxLon, wpt.lon)
        }

        val finalPoints = ArrayList<GpxPoint>(pointsWithTime.size)

        for (i in pointsWithTime.indices) {
            val curr = pointsWithTime[i]
            val ele = curr.ele ?: 0.0

            minEle = min(minEle, ele)
            maxEle = max(maxEle, ele)
            minLat = min(minLat, curr.lat)
            maxLat = max(maxLat, curr.lat)
            minLon = min(minLon, curr.lon)
            maxLon = max(maxLon, curr.lon)

            var speedKmh = 0.0
            var gradient = 0.0

            if (i > 0) {
                val prev = pointsWithTime[i - 1]
                val prevEle = prev.ele ?: 0.0
                val deltaDist = GpxTrack.distanceMeters(prev.lat, prev.lon, curr.lat, curr.lon)
                cumulativeMeters += deltaDist

                val deltaEle = ele - prevEle
                if (deltaEle > 0) totalAscent += deltaEle else totalDescent += -deltaEle

                if (deltaDist > 0.5) {
                    gradient = (deltaEle / deltaDist) * 100.0
                }

                // Speed calculation
                val timeDeltaMs = if (curr.time != null && prev.time != null) curr.time - prev.time else 0L
                if (timeDeltaMs > 200) {
                    val speedMps = (deltaDist / (timeDeltaMs / 1000.0))
                    // Filter unrealistic speed spikes (> 150 km/h)
                    speedKmh = min(speedMps * 3.6, 150.0)
                } else {
                    speedKmh = finalPoints.lastOrNull()?.speedKmh ?: 20.0
                }
            }

            finalPoints.add(
                GpxPoint(
                    lat = curr.lat,
                    lon = curr.lon,
                    elevation = ele,
                    time = curr.time,
                    cumulativeDistanceMeters = cumulativeMeters,
                    cumulativeDistanceKm = cumulativeMeters / 1000.0,
                    speedKmh = speedKmh,
                    gradientPercent = gradient
                )
            )
        }

        // Smooth elevation values with a 5-point moving window to remove GPS noise
        val smoothedPoints = smoothElevationProfile(finalPoints)

        val startTime = smoothedPoints.firstOrNull()?.time
        val endTime = smoothedPoints.lastOrNull()?.time
        val durationSeconds = if (startTime != null && endTime != null && endTime > startTime) {
            (endTime - startTime) / 1000L
        } else {
            (cumulativeMeters / (20.0 / 3.6)).toLong() // Fallback at 20 km/h
        }

        return GpxTrack(
            name = name,
            points = smoothedPoints,
            waypoints = waypoints,
            totalDistanceMeters = cumulativeMeters,
            totalDistanceKm = cumulativeMeters / 1000.0,
            minElevation = if (minEle == Double.MAX_VALUE) 0.0 else minEle,
            maxElevation = if (maxEle == Double.MIN_VALUE) 0.0 else maxEle,
            totalAscent = totalAscent,
            totalDescent = totalDescent,
            startTime = startTime,
            endTime = endTime,
            totalDurationSeconds = max(1L, durationSeconds),
            bounds = GeoBounds(
                minLat = if (minLat == Double.MAX_VALUE) 0.0 else minLat,
                maxLat = if (maxLat == -Double.MAX_VALUE) 0.0 else maxLat,
                minLon = if (minLon == Double.MAX_VALUE) 0.0 else minLon,
                maxLon = if (maxLon == -Double.MAX_VALUE) 0.0 else maxLon
            )
        )
    }

    /**
     * Linearly interpolates missing elevation data between known anchor points.
     */
    private fun interpolateMissingElevations(points: List<RawGpxPoint>): List<RawGpxPoint> {
        val knownIndices = points.indices.filter { points[it].ele != null }
        if (knownIndices.isEmpty()) {
            // Entire track lacks elevation data; generate a baseline 0.0
            return points.map { it.copy(ele = 0.0) }
        }

        val result = points.toMutableList()

        // Fill leading points before first known elevation
        val firstKnown = knownIndices.first()
        val firstEle = points[firstKnown].ele ?: 0.0
        for (i in 0 until firstKnown) {
            result[i] = result[i].copy(ele = firstEle)
        }

        // Fill trailing points after last known elevation
        val lastKnown = knownIndices.last()
        val lastEle = points[lastKnown].ele ?: 0.0
        for (i in (lastKnown + 1) until points.size) {
            result[i] = result[i].copy(ele = lastEle)
        }

        // Interpolate gaps between known points
        for (k in 0 until knownIndices.size - 1) {
            val startIdx = knownIndices[k]
            val endIdx = knownIndices[k + 1]
            if (endIdx - startIdx > 1) {
                val e1 = points[startIdx].ele ?: 0.0
                val e2 = points[endIdx].ele ?: 0.0
                val count = endIdx - startIdx
                for (j in 1 until count) {
                    val fraction = j.toDouble() / count
                    val interpolatedEle = e1 + (e2 - e1) * fraction
                    result[startIdx + j] = result[startIdx + j].copy(ele = interpolatedEle)
                }
            }
        }

        return result
    }

    /**
     * Ensures all points have monotonically increasing timestamps.
     */
    private fun ensureTimestamps(points: List<RawGpxPoint>): List<RawGpxPoint> {
        if (points.isEmpty()) return points

        val knownIndices = points.indices.filter { points[it].time != null }
        if (knownIndices.isEmpty()) {
            // Entire track lacks timestamps; synthesize assuming realistic speed of 25 km/h
            val baseTime = System.currentTimeMillis() - 3600_000L
            var accumulatedTime = baseTime
            val result = ArrayList<RawGpxPoint>(points.size)

            for (i in points.indices) {
                if (i == 0) {
                    result.add(points[0].copy(time = accumulatedTime))
                } else {
                    val d = GpxTrack.distanceMeters(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
                    val dtSec = max(1.0, d / (25.0 / 3.6)) // 25 km/h
                    accumulatedTime += (dtSec * 1000L).toLong()
                    result.add(points[i].copy(time = accumulatedTime))
                }
            }
            return result
        }

        if (knownIndices.size == points.size) {
            // All points have timestamps; ensure strictly monotonic
            val result = ArrayList<RawGpxPoint>(points.size)
            var lastTime = points[0].time ?: 0L
            result.add(points[0])
            for (i in 1 until points.size) {
                val t = points[i].time ?: (lastTime + 1000L)
                val nonDecreasingTime = max(lastTime + 100L, t)
                lastTime = nonDecreasingTime
                result.add(points[i].copy(time = nonDecreasingTime))
            }
            return result
        }

        // Partial timestamps: interpolate between known timestamps, extrapolate before and after
        val result = points.toMutableList()
        val firstKnownIdx = knownIndices.first()
        val firstTime = points[firstKnownIdx].time!!
        var curBackTime = firstTime
        for (i in firstKnownIdx - 1 downTo 0) {
            val d = GpxTrack.distanceMeters(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
            val dtMs = max(100L, ((d / (25.0 / 3.6)) * 1000.0).toLong())
            curBackTime -= dtMs
            result[i] = result[i].copy(time = curBackTime)
        }

        val lastKnownIdx = knownIndices.last()
        val lastTime = points[lastKnownIdx].time!!
        var curFwdTime = lastTime
        for (i in lastKnownIdx + 1 until points.size) {
            val d = GpxTrack.distanceMeters(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            val dtMs = max(100L, ((d / (25.0 / 3.6)) * 1000.0).toLong())
            curFwdTime += dtMs
            result[i] = result[i].copy(time = curFwdTime)
        }

        for (k in 0 until knownIndices.size - 1) {
            val startIdx = knownIndices[k]
            val endIdx = knownIndices[k + 1]
            if (endIdx - startIdx > 1) {
                val t1 = points[startIdx].time!!
                val t2 = max(t1 + (endIdx - startIdx) * 100L, points[endIdx].time!!)
                val totalSpan = t2 - t1
                val count = endIdx - startIdx
                for (j in 1 until count) {
                    val fraction = j.toDouble() / count
                    val interpolatedTime = t1 + (totalSpan * fraction).toLong()
                    result[startIdx + j] = result[startIdx + j].copy(time = interpolatedTime)
                }
            }
        }

        return result
    }

    /**
     * Applies a moving average filter to smooth raw elevation fluctuations.
     */
    private fun smoothElevationProfile(points: List<GpxPoint>, windowSize: Int = 5): List<GpxPoint> {
        if (points.size <= windowSize) return points
        val halfWindow = windowSize / 2
        val smoothed = ArrayList<GpxPoint>(points.size)

        for (i in points.indices) {
            val start = max(0, i - halfWindow)
            val end = min(points.size - 1, i + halfWindow)
            var sumEle = 0.0
            var count = 0
            for (w in start..end) {
                sumEle += points[w].elevation
                count++
            }
            val avgEle = sumEle / count
            smoothed.add(points[i].copy(elevation = avgEle))
        }
        return smoothed
    }

    /**
     * Robust ISO 8601 timestamp parser supporting multiple UTC and local formats.
     */
    private fun parseIsoTimestamp(text: String): Long? {
        // Try parsing numeric epoch millis or seconds
        text.toLongOrNull()?.let {
            return if (it > 1_000_000_000_000L) it else it * 1000L
        }

        try {
            return java.time.Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {}
        try {
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {}

        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(text)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
