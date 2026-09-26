package com.dir2gpx

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class Dir2GpxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initOsmDroid()
    }

    private fun initOsmDroid() {
        val config = Configuration.getInstance()

        // Initialize OSMDroid configuration with app preferences
        val sharedPrefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        config.load(this, sharedPrefs)

        // Set user agent to identify this app for OpenStreetMap tile requests
        config.userAgentValue = packageName

        // Configure tile cache in app-specific cache directory
        val osmCacheDir = File(cacheDir, "osmdroid")
        if (!osmCacheDir.exists()) {
            osmCacheDir.mkdirs()
        }
        config.osmdroidBasePath = osmCacheDir
        config.osmdroidTileCache = File(osmCacheDir, "tiles")

        // Cache limits (256 MB max, trim to 200 MB)
        config.tileFileSystemCacheMaxBytes = 256L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 200L * 1024 * 1024
    }
}
