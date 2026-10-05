package com.gpxedt.app.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utility functions for ISO-8601 GPX timestamp formatting and local display presentation.
 */
object GpxDateTimeFormatter {

    /**
     * Standard GPX 1.1 ISO-8601 UTC formatter: e.g. "2026-10-03T05:56:00Z".
     */
    val ISO_UTC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * User-facing local timestamp formatter: e.g. "2026-10-03 13:56:00".
     */
    val LOCAL_DISPLAY_FORMATTER: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(ZoneId.systemDefault())

    /**
     * Formats an [Instant] into strict ISO-8601 UTC for GPX serialization.
     */
    fun formatIsoUtc(instant: Instant): String {
        return ISO_UTC_FORMATTER.format(instant)
    }

    /**
     * Formats an [Instant] for local UI display.
     */
    fun formatLocalDisplay(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(zoneId)
        return formatter.format(instant)
    }

    /**
     * Parses an ISO-8601 date-time string into an [Instant], returning null on failure.
     */
    fun parseIsoInstant(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        return try {
            Instant.parse(trimmed)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }
}
