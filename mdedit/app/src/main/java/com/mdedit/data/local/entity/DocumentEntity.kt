package com.mdedit.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.SyncStatus

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey
    val id: String,
    val driveFileId: String?,
    val title: String,
    val content: String,
    val localModifiedAt: Long,
    val remoteModifiedAt: Long?,
    val syncStatus: String,
    val isDeleted: Boolean
)

fun DocumentEntity.toDomain(): Document {
    return Document(
        id = id,
        driveFileId = driveFileId,
        title = title,
        content = content,
        localModifiedAt = localModifiedAt,
        remoteModifiedAt = remoteModifiedAt,
        syncStatus = try {
            SyncStatus.valueOf(syncStatus)
        } catch (_: Exception) {
            SyncStatus.LOCAL_ONLY
        },
        isDeleted = isDeleted
    )
}

fun Document.toEntity(): DocumentEntity {
    return DocumentEntity(
        id = id,
        driveFileId = driveFileId,
        title = title,
        content = content,
        localModifiedAt = localModifiedAt,
        remoteModifiedAt = remoteModifiedAt,
        syncStatus = syncStatus.name,
        isDeleted = isDeleted
    )
}
