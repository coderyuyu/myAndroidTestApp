package com.mdedit.domain.model

import java.util.UUID

data class Document(
    val id: String = UUID.randomUUID().toString(),
    val driveFileId: String? = null,
    val title: String = "Untitled",
    val content: String = "",
    val localModifiedAt: Long = System.currentTimeMillis(),
    val remoteModifiedAt: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val isDeleted: Boolean = false
) {
    val isDriveSynced: Boolean
        get() = driveFileId != null && syncStatus == SyncStatus.SYNCED

    val filename: String
        get() = if (title.endsWith(".md", ignoreCase = true)) title else "$title.md"
}
