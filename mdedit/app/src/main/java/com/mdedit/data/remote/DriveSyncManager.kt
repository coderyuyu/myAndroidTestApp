package com.mdedit.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.mdedit.data.local.dao.DocumentDao
import com.mdedit.data.local.entity.toDomain
import com.mdedit.data.local.entity.toEntity
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import com.mdedit.domain.model.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveService: GoogleDriveService,
    private val authManager: DriveAuthManager,
    private val documentDao: DocumentDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(checkInitialNetworkState())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        registerNetworkCallback()
    }

    private fun checkInitialNetworkState(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
                // Network restored: trigger automatic sync of pending files
                scope.launch {
                    syncPendingDocuments()
                }
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
            }
        })
    }

    suspend fun syncDocument(doc: Document): Result<Document> {
        if (!_isOnline.value || !authManager.isSignedIn) {
            // Offline or unauthenticated: mark as pending upload
            val pendingDoc = doc.copy(syncStatus = SyncStatus.PENDING_UPLOAD)
            documentDao.upsertDocument(pendingDoc.toEntity())
            return Result.success(pendingDoc)
        }

        return runCatching {
            _isSyncing.value = true
            documentDao.updateSyncStatus(doc.id, SyncStatus.SYNCING.name, null)

            val driveInfo: DriveFileInfo = if (doc.driveFileId == null) {
                // Create file on Drive
                val result = driveService.createFile(doc.title, doc.content)
                result.getOrThrow()
            } else {
                // Update existing file on Drive
                val result = driveService.updateFile(doc.driveFileId, doc.title, doc.content)
                result.getOrThrow()
            }

            val syncedDoc = doc.copy(
                driveFileId = driveInfo.id,
                remoteModifiedAt = driveInfo.modifiedTime,
                syncStatus = SyncStatus.SYNCED
            )

            documentDao.upsertDocument(syncedDoc.toEntity())
            syncedDoc
        }.onFailure {
            documentDao.updateSyncStatus(doc.id, SyncStatus.ERROR.name, null)
        }.also {
            _isSyncing.value = false
        }
    }

    suspend fun syncPendingDocuments() {
        if (!_isOnline.value || !authManager.isSignedIn) return
        val pending = documentDao.getPendingUploadDocuments()
        for (item in pending) {
            syncDocument(item.toDomain())
        }
    }

    suspend fun fetchDriveFile(driveFileId: String, title: String): Result<Document> {
        return runCatching {
            _isSyncing.value = true
            val content = driveService.readFileContent(driveFileId).getOrThrow()
            
            // Check if document already exists locally
            val existing = documentDao.getDocumentByDriveId(driveFileId)
            val doc = if (existing != null) {
                existing.toDomain().copy(
                    title = title,
                    content = content,
                    remoteModifiedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.SYNCED
                )
            } else {
                Document(
                    driveFileId = driveFileId,
                    title = title,
                    content = content,
                    remoteModifiedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.SYNCED
                )
            }

            documentDao.upsertDocument(doc.toEntity())
            doc
        }.also {
            _isSyncing.value = false
        }
    }
}
