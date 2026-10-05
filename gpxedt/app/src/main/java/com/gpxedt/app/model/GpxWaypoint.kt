package com.gpxedt.app.model

import androidx.compose.ui.graphics.Color
import java.time.Instant

/**
 * Waypoint domain model with standardized GPX symbol definitions.
 * Restricts standard symbols strictly to three canonical flag variants:
 * "Flag, Red", "Flag, Yellow", and "Flag, Green".
 */
data class GpxWaypoint(
    val lat: Double,
    val lon: Double,
    val name: String,
    val desc: String? = null,
    val sym: String? = SYM_FLAG_RED,
    val ele: Double? = null,
    val time: Instant? = null
) {
    companion object {
        const val SYM_FLAG_RED = "Flag, Red"
        const val SYM_FLAG_YELLOW = "Flag, Yellow"
        const val SYM_FLAG_GREEN = "Flag, Green"

        val STANDARD_FLAG_SYMBOLS = listOf(
            SYM_FLAG_RED,
            SYM_FLAG_YELLOW,
            SYM_FLAG_GREEN
        )

        const val DEFAULT_SYMBOL = SYM_FLAG_RED

        /**
         * Resolves Compose UI Color tint for the waypoint symbol.
         */
        fun getFlagColor(sym: String?): Color {
            return when (sym?.trim()) {
                SYM_FLAG_RED -> Color(0xFFE53935)
                SYM_FLAG_YELLOW -> Color(0xFFFBC02D)
                SYM_FLAG_GREEN -> Color(0xFF43A047)
                else -> Color(0xFFE53935) // Fallback to Red
            }
        }

        /**
         * Resolves Android integer Color for MapLibre marker rendering.
         */
        fun getFlagColorInt(sym: String?): Int {
            return when (sym?.trim()) {
                SYM_FLAG_RED -> android.graphics.Color.parseColor("#E53935")
                SYM_FLAG_YELLOW -> android.graphics.Color.parseColor("#FBC02D")
                SYM_FLAG_GREEN -> android.graphics.Color.parseColor("#43A047")
                else -> android.graphics.Color.parseColor("#E53935")
            }
        }

        /**
         * Normalizes any input symbol into one of the standard three flag symbols,
         * gracefully handling legacy or unrecognized symbols.
         */
        fun normalizeSymbol(sym: String?): String {
            val trimmed = sym?.trim() ?: return DEFAULT_SYMBOL
            return when {
                trimmed.equals(SYM_FLAG_RED, ignoreCase = true) || trimmed.contains("red", ignoreCase = true) -> SYM_FLAG_RED
                trimmed.equals(SYM_FLAG_YELLOW, ignoreCase = true) || trimmed.contains("yellow", ignoreCase = true) -> SYM_FLAG_YELLOW
                trimmed.equals(SYM_FLAG_GREEN, ignoreCase = true) || trimmed.contains("green", ignoreCase = true) -> SYM_FLAG_GREEN
                else -> DEFAULT_SYMBOL
            }
        }
    }
}

typealias Waypoint = GpxWaypoint
