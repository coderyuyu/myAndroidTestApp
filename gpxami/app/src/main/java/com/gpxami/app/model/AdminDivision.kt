package com.gpxami.app.model

import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.toTraditionalChinese
import com.gpxami.app.data.repository.AdminDivisionResult
import java.util.Locale

/**
 * Standardized domain model representing administrative divisions worldwide.
 *
 * @property countryCode ISO 3166-1 alpha-2 or alpha-3 country code (e.g., "TW", "JP", "US").
 * @property countryName Localized or standardized country name (e.g., "臺灣", "Japan", "United States").
 * @property level1Name First-level administrative division (State, Province, Prefecture, Municipality, Region).
 * @property level2Name Second-level administrative division (County, District, City, Sub-prefecture).
 * @property isHighSeas True strictly when the coordinate is in international waters / open ocean without sovereign territorial association.
 * @property formattedCoordinates Clean fallback string representation (e.g., "25.047°N, 121.518°E").
 */
data class AdminDivision(
    val countryCode: String? = null,
    val countryName: String? = null,
    val level1Name: String? = null,
    val level2Name: String? = null,
    val isHighSeas: Boolean = false,
    val formattedCoordinates: String? = null
) {
    companion object {
        const val HIGH_SEAS_LABEL = "公海 / 國際水域"
        const val UNKNOWN_REGION_LABEL = "未知區域"

        fun highSeas(formattedCoords: String? = null): AdminDivision = AdminDivision(
            isHighSeas = true,
            level1Name = HIGH_SEAS_LABEL,
            formattedCoordinates = formattedCoords
        )

        fun unknown(formattedCoords: String? = null): AdminDivision = AdminDivision(
            isHighSeas = false,
            level1Name = formattedCoords ?: UNKNOWN_REGION_LABEL,
            formattedCoordinates = formattedCoords
        )
    }

    /**
     * Formats the administrative division presentation according to [AdminDivisionConfig] and [locale].
     * - Respects system/device Locale: applies Traditional Chinese normalization when language is "zh",
     *   or preserves native spelling for Japanese (ja), English (en), etc.
     * - LEVEL_1_ONLY: e.g. "臺北市" or "東京都" or "New York"
     * - LEVEL_1_AND_2: e.g. "臺北市 · 中正區" or "東京都 · 千代田区" or "New York · New York County"
     */
    fun format(
        config: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY,
        locale: Locale = Locale.getDefault()
    ): String {
        if (isHighSeas) {
            return HIGH_SEAS_LABEL
        }

        val l1 = level1Name?.trim()?.takeIf { it.isNotEmpty() }
        val l2 = level2Name?.trim()?.takeIf { it.isNotEmpty() }

        val raw = when (config) {
            AdminDivisionConfig.LEVEL_1_ONLY -> {
                l1 ?: l2 ?: countryName ?: formattedCoordinates ?: UNKNOWN_REGION_LABEL
            }
            AdminDivisionConfig.LEVEL_1_AND_2 -> {
                when {
                    l1 != null && l2 != null -> {
                        if (l1.equals(l2, ignoreCase = true)) {
                            l1
                        } else {
                            "$l1 · $l2"
                        }
                    }
                    l1 != null -> l1
                    l2 != null -> l2
                    countryName != null -> countryName
                    formattedCoordinates != null -> formattedCoordinates
                    else -> UNKNOWN_REGION_LABEL
                }
            }
        }
        return if (locale.language == "zh") toTraditionalChinese(raw) else raw
    }

    /**
     * Converts to [AdminDivisionResult] for backward compatibility with existing components.
     */
    fun toAdminDivisionResult(): AdminDivisionResult {
        return AdminDivisionResult(
            level1 = if (isHighSeas) HIGH_SEAS_LABEL else (level1Name ?: countryName ?: formattedCoordinates),
            level2 = if (isHighSeas) null else level2Name
        )
    }
}
