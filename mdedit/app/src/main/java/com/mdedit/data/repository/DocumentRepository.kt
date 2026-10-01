package com.mdedit.data.repository

import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<Document>>
    suspend fun getDocumentById(id: String): Document?
    suspend fun saveDocumentLocally(document: Document)
    suspend fun saveAndSyncDocument(document: Document): Result<Document>
    suspend fun deleteDocument(id: String)
    suspend fun listDriveFiles(): Result<List<DriveFileInfo>>
    suspend fun importFromDrive(driveFileId: String, title: String): Result<Document>
    fun isOnline(): Flow<Boolean>
    fun isSyncing(): Flow<Boolean>
}
