package com.mdedit

import com.mdedit.domain.model.Document
import com.mdedit.domain.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {

    @Test
    fun testFilenameFormatting() {
        val docWithMd = Document(title = "Notes.md")
        assertEquals("Notes.md", docWithMd.filename)

        val docWithoutMd = Document(title = "MyDailyJournal")
        assertEquals("MyDailyJournal.md", docWithoutMd.filename)
    }

    @Test
    fun testDriveSyncedStatus() {
        val localDoc = Document(driveFileId = null, syncStatus = SyncStatus.LOCAL_ONLY)
        assertFalse(localDoc.isDriveSynced)

        val pendingDoc = Document(driveFileId = "drive_123", syncStatus = SyncStatus.PENDING_UPLOAD)
        assertFalse(pendingDoc.isDriveSynced)

        val syncedDoc = Document(driveFileId = "drive_123", syncStatus = SyncStatus.SYNCED)
        assertTrue(syncedDoc.isDriveSynced)
    }
}
