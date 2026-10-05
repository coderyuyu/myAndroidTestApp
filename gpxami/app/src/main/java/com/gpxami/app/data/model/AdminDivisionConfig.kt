package com.gpxami.app.data.model

/**
 * Configuration option for administrative division naming format.
 * - LEVEL_1_ONLY: First-level administrative division (e.g. 臺灣 / State / Province)
 * - LEVEL_1_AND_2: First-level + Second-level administrative division (e.g. 臺灣 · 苗栗縣)
 */
enum class AdminDivisionConfig(val displayName: String) {
    LEVEL_1_ONLY("一級行政區"),
    LEVEL_1_AND_2("一級行政區 + 二級行政區")
}
