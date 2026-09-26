package com.gpxami.app

import android.app.Application
import org.maplibre.android.MapLibre

class GPXAmiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MapLibre Native SDK (free, open source, no API key required)
        try {
            MapLibre.getInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
