package com.mdedit.data.repository

import com.mdedit.data.local.dao.DocumentDao
import com.mdedit.data.local.entity.toDomain
import com.mdedit.data.local.entity.toEntity
import com.mdedit.data.remote.DriveSyncManager
import com.mdedit.data.remote.GoogleDriveService
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import com.mdedit.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val driveService: GoogleDriveService,
    private val syncManager: DriveSyncManager
) : DocumentRepository {

    override fun getAllDocuments(): Flow<List<Document>> {
        return documentDao.getAllDocuments().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getDocumentById(id: String): Document? {
        return documentDao.getDocumentById(id)?.toDomain()
    }

    override suspend fun saveDocumentLocally(document: Document) {
        val status = if (document.driveFileId != null) SyncStatus.PENDING_UPLOAD else SyncStatus.LOCAL_ONLY
        val updated = document.copy(
            localModifiedAt = System.currentTimeMillis(),
            syncStatus = status
        )
        documentDao.upsertDocument(updated.toEntity())
    }

    override suspend fun saveAndSyncDocument(document: Document): Result<Document> {
        val updated = document.copy(localModifiedAt = System.currentTimeMillis())
        documentDao.upsertDocument(updated.toEntity())
        return syncManager.syncDocument(updated)
    }

    override suspend fun deleteDocument(id: String) {
        val doc = documentDao.getDocumentById(id)
        if (doc?.driveFileId != null) {
            runCatching {
                driveService.deleteFile(doc.driveFileId)
            }
        }
        documentDao.hardDelete(id)
    }

    override suspend fun listDriveFiles(): Result<List<DriveFileInfo>> {
        return driveService.listFiles()
    }

    override suspend fun importFromDrive(driveFileId: String, title: String): Result<Document> {
        return syncManager.fetchDriveFile(driveFileId, title)
    }

    override fun isOnline(): Flow<Boolean> {
        return syncManager.isOnline
    }

    override fun isSyncing(): Flow<Boolean> {
        return syncManager.isSyncing
    }
}
