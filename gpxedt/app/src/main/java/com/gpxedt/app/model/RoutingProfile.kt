package com.gpxedt.app.model

enum class RoutingProfile(
    val apiValue: String,
    val displayName: String,
    val description: String
) {
    FOOT_HIKING("foot", "Foot (徒步)", "Walking & hiking paths"),
    CYCLING_REGULAR("bike", "Bike (自行車)", "Bicycle routes & paths"),
    DRIVING_CAR("driving", "Car (汽車)", "Driving routes");

    companion object {
        fun fromApiValue(value: String): RoutingProfile {
            return entries.firstOrNull { it.apiValue == value } ?: FOOT_HIKING
        }
    }
}
