package com.mdedit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mdedit.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE isDeleted = 0 ORDER BY localModifiedAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun getDocumentByDriveId(driveFileId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE syncStatus = 'PENDING_UPLOAD' AND isDeleted = 0")
    suspend fun getPendingUploadDocuments(): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: DocumentEntity)

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("UPDATE documents SET isDeleted = 1, syncStatus = 'PENDING_UPLOAD' WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("UPDATE documents SET syncStatus = :status, remoteModifiedAt = :remoteTime WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String, remoteTime: Long?)

    @Query("UPDATE documents SET driveFileId = :driveFileId, syncStatus = :status WHERE id = :id")
    suspend fun updateDriveFileId(id: String, driveFileId: String, status: String)
}
