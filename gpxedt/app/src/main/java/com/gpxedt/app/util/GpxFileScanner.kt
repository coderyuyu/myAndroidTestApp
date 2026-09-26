package com.gpxedt.app.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.gpxedt.app.model.GpxFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object GpxFileScanner {

    fun isGpxFile(fileName: String): Boolean {
        return fileName.endsWith(".gpx", ignoreCase = true)
    }

    fun sortFilesByModifiedDescending(files: List<GpxFileInfo>): List<GpxFileInfo> {
        return files.sortedByDescending { it.lastModified }
    }

    /**
     * Scans for GPX files across MediaStore, standard Downloads/Documents directories,
     * and app storage. Only returns files ending in .gpx, sorted by last modified descending.
     */
    suspend fun scanGpxFiles(context: Context): List<GpxFileInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<GpxFileInfo>()
        val seenNames = mutableSetOf<String>()

        // 1. Scan MediaStore
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE
            )

            val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.gpx' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.GPX'"
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                queryUri,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)

                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "track.gpx" else "track.gpx"
                    if (!isGpxFile(name)) continue

                    val dateSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val contentUri = ContentUris.withAppendedId(queryUri, id)

                    results.add(
                        GpxFileInfo(
                            name = name,
                            uri = contentUri,
                            lastModified = dateSec * 1000L,
                            sizeBytes = size,
                            pathHint = "Downloads"
                        )
                    )
                    seenNames.add(name)
                }
            }
        } catch (_: Exception) {
            // MediaStore query fallback
        }

        // 2. Scan standard public directories: Downloads & Documents
        try {
            val publicDirs = listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            )

            for (dir in publicDirs) {
                scanDirectory(dir, seenNames, results)
            }
        } catch (_: Exception) {
            // Filesystem access fallback
        }

        // 3. Scan app-specific external files directories
        try {
            val appDirs = context.getExternalFilesDirs(null)
            for (dir in appDirs) {
                if (dir != null && dir.exists()) {
                    scanDirectory(dir, seenNames, results)
                }
            }
        } catch (_: Exception) {
            // App-specific dirs fallback
        }

        sortFilesByModifiedDescending(results)
    }

    private fun scanDirectory(
        dir: File,
        seenNames: MutableSet<String>,
        outList: MutableList<GpxFileInfo>
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && isGpxFile(f.name)) {
                if (seenNames.add(f.name)) {
                    outList.add(
                        GpxFileInfo(
                            name = f.name,
                            uri = Uri.fromFile(f),
                            lastModified = f.lastModified(),
                            sizeBytes = f.length(),
                            pathHint = dir.name
                        )
                    )
                }
            }
        }
    }
}
