package com.gpxedt.app.util

import com.gpxedt.app.model.GpxFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxFileScannerTest {

    @Test
    fun testIsGpxFileExtensionFiltering() {
        assertTrue(GpxFileScanner.isGpxFile("track.gpx"))
        assertTrue(GpxFileScanner.isGpxFile("TRACK.GPX"))
        assertTrue(GpxFileScanner.isGpxFile("Route_2026_09_21.Gpx"))
        assertTrue(GpxFileScanner.isGpxFile("my.custom.track.gpx"))

        assertFalse(GpxFileScanner.isGpxFile("photo.jpg"))
        assertFalse(GpxFileScanner.isGpxFile("document.pdf"))
        assertFalse(GpxFileScanner.isGpxFile("track.gpx.bak"))
        assertFalse(GpxFileScanner.isGpxFile("data.xml"))
        assertFalse(GpxFileScanner.isGpxFile("gpx"))
        assertFalse(GpxFileScanner.isGpxFile(""))
    }

    @Test
    fun testSortFilesByModifiedDescending() {
        val file1 = GpxFileInfo(name = "old_route.gpx", uri = null, lastModified = 1000L)
        val file2 = GpxFileInfo(name = "newest_route.gpx", uri = null, lastModified = 5000L)
        val file3 = GpxFileInfo(name = "middle_route.gpx", uri = null, lastModified = 3000L)

        val sorted = GpxFileScanner.sortFilesByModifiedDescending(listOf(file1, file2, file3))

        assertEquals(3, sorted.size)
        assertEquals("newest_route.gpx", sorted[0].name)
        assertEquals(5000L, sorted[0].lastModified)

        assertEquals("middle_route.gpx", sorted[1].name)
        assertEquals(3000L, sorted[1].lastModified)

        assertEquals("old_route.gpx", sorted[2].name)
        assertEquals(1000L, sorted[2].lastModified)
    }
}
