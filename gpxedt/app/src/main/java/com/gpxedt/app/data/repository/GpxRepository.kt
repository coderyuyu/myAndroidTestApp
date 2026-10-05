package com.gpxedt.app.data.repository

import com.gpxedt.app.model.GpxData
import com.gpxedt.app.model.GpxWaypoint
import com.gpxedt.app.parser.GpxParser
import com.gpxedt.app.parser.GpxSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Repository interface for GPX parsing and serialization with strict GPX 1.1 compliance
 * and standard symbol enforcement.
 */
interface GpxRepository {
    suspend fun parseGpx(inputStream: InputStream): GpxData
    suspend fun serializeGpx(gpxData: GpxData, outputStream: OutputStream)
    suspend fun serializeGpxToString(gpxData: GpxData): String
}

class GpxRepositoryImpl(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GpxRepository {

    override suspend fun parseGpx(inputStream: InputStream): GpxData = withContext(ioDispatcher) {
        val parsed = GpxParser.parse(inputStream)
        // Ensure waypoints with missing or blank symbols receive the standard fallback
        val normalizedWaypoints = parsed.waypoints.map { wpt ->
            if (wpt.sym.isNullOrBlank()) {
                wpt.copy(sym = GpxWaypoint.DEFAULT_SYMBOL)
            } else {
                wpt
            }
        }
        parsed.copy(waypoints = normalizedWaypoints)
    }

    override suspend fun serializeGpx(gpxData: GpxData, outputStream: OutputStream) = withContext(ioDispatcher) {
        GpxSerializer.serialize(gpxData, outputStream)
    }

    override suspend fun serializeGpxToString(gpxData: GpxData): String = withContext(ioDispatcher) {
        GpxSerializer.serializeToString(gpxData)
    }
}
