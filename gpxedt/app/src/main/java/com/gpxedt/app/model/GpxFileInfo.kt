package com.gpxedt.app.model

import android.net.Uri

/**
 * Metadata for a discovered or browsed GPX file.
 */
data class GpxFileInfo(
    val name: String,
    val uri: Uri?,
    val lastModified: Long,
    val sizeBytes: Long = 0L,
    val pathHint: String = ""
)
