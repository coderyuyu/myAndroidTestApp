package com.gpxedt.app.util

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class PhotoMetadata(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val time: Instant? = null
)

object PhotoExifReader {

    private val EXIF_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    /**
     * Reads EXIF metadata (coordinates, elevation, datetime) from a photo URI.
     * Returns null if reading fails or if the photo contains no valid GPS coordinates.
     */
    fun readPhotoMetadata(context: Context, uri: Uri): PhotoMetadata? {
        val resolver = context.contentResolver

        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY
        ) {
            try {
                MediaStore.setRequireOriginal(uri)
            } catch (_: Exception) {
                uri
            }
        } else {
            uri
        }

        // 1. Try reading directly via openInputStream (most reliable across Android content providers)
        val streamMeta = tryOpenStreamAndExtract(resolver, targetUri)
        if (streamMeta != null) return streamMeta

        if (targetUri != uri) {
            val origStreamMeta = tryOpenStreamAndExtract(resolver, uri)
            if (origStreamMeta != null) return origStreamMeta
        }

        // 2. Fallback: ParcelFileDescriptor
        val pfdMeta = tryOpenFdAndExtract(resolver, targetUri)
        if (pfdMeta != null) return pfdMeta

        if (targetUri != uri) {
            val origPfdMeta = tryOpenFdAndExtract(resolver, uri)
            if (origPfdMeta != null) return origPfdMeta
        }

        return null
    }

    private fun tryOpenStreamAndExtract(resolver: android.content.ContentResolver, uri: Uri): PhotoMetadata? {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                extractMetadata(exif)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOpenFdAndExtract(resolver: android.content.ContentResolver, uri: Uri): PhotoMetadata? {
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { parcel ->
                val exif = ExifInterface(parcel.fileDescriptor)
                extractMetadata(exif)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMetadata(exif: ExifInterface): PhotoMetadata? {
        var lat: Double? = null
        var lon: Double? = null

        // 1. Try native getLatLong
        val latLong = FloatArray(2)
        val hasGps = try {
            exif.getLatLong(latLong)
        } catch (_: Exception) {
            false
        }

        if (hasGps) {
            val candidateLat = latLong[0].toDouble()
            val candidateLon = latLong[1].toDouble()
            // Reject 0.0, 0.0 (Null Island indicates missing / stripped coordinates)
            if (!(abs(candidateLat) < 1e-6 && abs(candidateLon) < 1e-6)) {
                lat = candidateLat
                lon = candidateLon
            }
        }

        // 2. Fallback: manual parsing from EXIF GPS tags
        if (lat == null || lon == null) {
            val rawLat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val rawLatRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            val rawLon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            val rawLonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)

            val parsedLat = parseGpsCoordinate(rawLat, rawLatRef)
            val parsedLon = parseGpsCoordinate(rawLon, rawLonRef)

            if (parsedLat != null && parsedLon != null &&
                !(abs(parsedLat) < 1e-6 && abs(parsedLon) < 1e-6)
            ) {
                lat = parsedLat
                lon = parsedLon
            }
        }

        // 3. Fallback: try destination coordinates
        if (lat == null || lon == null) {
            val destLat = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE)
            val destLatRef = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF)
            val destLon = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE)
            val destLonRef = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF)

            val parsedDestLat = parseGpsCoordinate(destLat, destLatRef)
            val parsedDestLon = parseGpsCoordinate(destLon, destLonRef)

            if (parsedDestLat != null && parsedDestLon != null &&
                !(abs(parsedDestLat) < 1e-6 && abs(parsedDestLon) < 1e-6)
            ) {
                lat = parsedDestLat
                lon = parsedDestLon
            }
        }

        if (lat == null || lon == null) return null

        // Validate coordinate bounds
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        val alt = exif.getAltitude(-1.0)
        val ele = if (alt >= 0.0) alt else null

        val time = parseExifTime(exif)

        return PhotoMetadata(
            lat = lat,
            lon = lon,
            ele = ele,
            time = time
        )
    }

    /**
     * Parses EXIF GPS coordinates string in degrees/minutes/seconds rational format
     * e.g., "25/1, 14/1, 2345/100" with ref "N" -> 25.239847
     */
    fun parseGpsCoordinate(coordStr: String?, ref: String?): Double? {
        if (coordStr.isNullOrBlank()) return null
        return try {
            val parts = coordStr.trim().split(",", " ").filter { it.isNotBlank() }
            val value = when {
                parts.size == 3 -> {
                    val d = parseRational(parts[0]) ?: return null
                    val m = parseRational(parts[1]) ?: return null
                    val s = parseRational(parts[2]) ?: return null
                    d + (m / 60.0) + (s / 3600.0)
                }
                parts.size == 1 -> {
                    parseRational(parts[0]) ?: return null
                }
                else -> return null
            }

            val isNegative = ref.equals("S", ignoreCase = true) || ref.equals("W", ignoreCase = true)
            if (isNegative) -abs(value) else abs(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRational(token: String): Double? {
        val clean = token.trim().removeSuffix(",")
        val slashIdx = clean.indexOf('/')
        return if (slashIdx != -1) {
            val num = clean.substring(0, slashIdx).trim().toDoubleOrNull() ?: return null
            val den = clean.substring(slashIdx + 1).trim().toDoubleOrNull() ?: return null
            if (den == 0.0) null else num / den
        } else {
            clean.toDoubleOrNull()
        }
    }

    private fun parseExifTime(exif: ExifInterface): Instant? {
        // Try built-in epoch millis first
        val originalMillis = exif.dateTimeOriginal
        if (originalMillis > 0) {
            return Instant.ofEpochMilli(originalMillis)
        }

        val gpsMillis = exif.gpsDateTime
        if (gpsMillis > 0) {
            return Instant.ofEpochMilli(gpsMillis)
        }

        val generalMillis = exif.dateTime
        if (generalMillis > 0) {
            return Instant.ofEpochMilli(generalMillis)
        }

        // Fallback: parse string attributes
        val gpsDate = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP)
        val gpsTime = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP)
        if (!gpsDate.isNullOrBlank() && !gpsTime.isNullOrBlank()) {
            try {
                val combined = "$gpsDate $gpsTime"
                val formatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
                val ldt = LocalDateTime.parse(combined.trim(), formatter)
                return ldt.atZone(ZoneId.of("UTC")).toInstant()
            } catch (_: Exception) {}
        }

        val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

        if (!dateStr.isNullOrBlank()) {
            try {
                val clean = dateStr.trim().take(19)
                val ldt = LocalDateTime.parse(clean, EXIF_DATE_FORMATTER)
                return ldt.atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {}
        }

        return null
    }
}
