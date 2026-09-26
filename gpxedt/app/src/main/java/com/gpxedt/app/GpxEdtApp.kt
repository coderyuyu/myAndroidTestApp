package com.gpxedt.app

import android.app.Application
import org.maplibre.android.MapLibre

class GpxEdtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MapLibre Native SDK
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
