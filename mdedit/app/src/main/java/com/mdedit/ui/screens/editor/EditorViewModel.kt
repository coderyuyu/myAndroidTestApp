package com.mdedit.ui.screens.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.mdedit.data.remote.DriveAuthManager
import com.mdedit.data.repository.DocumentRepository
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import com.mdedit.domain.model.EditorFormatState
import com.mdedit.domain.model.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val authManager: DriveAuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val autoSaveTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        observeDocuments()
        observeNetworkAndSync()
        observeAuth()
        setupAutoSave()
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.getAllDocuments().collect { docs ->
                _uiState.update { current ->
                    if (docs.isEmpty()) {
                        // Create default document if database is completely empty
                        val defaultDoc = Document(
                            title = "Welcome to MDEdit",
                            content = "# Welcome to MDEdit \n\nA **What-You-See-Is-What-You-Get** Markdown Editor with Google Drive sync.\n\n- [x] Rich visual formatting\n- [x] Auto-save to local storage\n- [x] Google Drive cloud sync\n- [ ] Try creating your own notes!\n\n> Edit comfortably with inline styles or switch to raw markdown mode anytime."
                        )
                        viewModelScope.launch { repository.saveDocumentLocally(defaultDoc) }
                        current.copy(
                            allDocuments = listOf(defaultDoc),
                            currentDocument = defaultDoc,
                            title = defaultDoc.title,
                            markdownContent = defaultDoc.content
                        )
                    } else {
                        // Keep current document reference up to date
                        val updatedCurrent = docs.find { it.id == current.currentDocument.id } ?: docs.first()
                        current.copy(
                            allDocuments = docs,
                            currentDocument = if (current.isDirty) current.currentDocument else updatedCurrent
                        )
                    }
                }
            }
        }
    }

    private fun observeNetworkAndSync() {
        viewModelScope.launch {
            repository.isOnline().collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }
        viewModelScope.launch {
            repository.isSyncing().collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }
    }

    private fun observeAuth() {
        viewModelScope.launch {
            authManager.signedInAccount.collect { account ->
                _uiState.update { it.copy(signedInAccount = account) }
            }
        }
    }

    private fun setupAutoSave() {
        viewModelScope.launch {
            autoSaveTrigger
                .debounce(2500L) // 2.5 second debounce for auto-save
                .collectLatest {
                    saveDocument(autoSync = true)
                }
        }
    }

    fun onContentChanged(newContent: String) {
        if (_uiState.value.markdownContent == newContent) return
        _uiState.update {
            it.copy(
                markdownContent = newContent,
                isDirty = true
            )
        }
        autoSaveTrigger.tryEmit(Unit)
    }

    fun onTitleChanged(newTitle: String) {
        if (_uiState.value.title == newTitle) return
        _uiState.update {
            it.copy(
                title = newTitle,
                isDirty = true
            )
        }
        autoSaveTrigger.tryEmit(Unit)
    }

    fun onFormatStateChanged(formatState: EditorFormatState) {
        _uiState.update { it.copy(formatState = formatState) }
    }

    fun toggleVisualMode() {
        _uiState.update { it.copy(isVisualMode = !it.isVisualMode) }
    }

    fun saveDocument(autoSync: Boolean = true) {
        val state = _uiState.value
        val docToSave = state.currentDocument.copy(
            title = state.title.ifBlank { "Untitled" },
            content = state.markdownContent,
            localModifiedAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            if (autoSync && state.isOnline && state.signedInAccount != null && docToSave.driveFileId != null) {
                // Sync to Google Drive
                val result = repository.saveAndSyncDocument(docToSave)
                result.onSuccess { synced ->
                    _uiState.update {
                        it.copy(
                            currentDocument = synced,
                            isDirty = false
                        )
                    }
                }.onFailure {
                    // Saved locally with error status
                    repository.saveDocumentLocally(docToSave)
                    _uiState.update { it.copy(isDirty = false) }
                }
            } else {
                repository.saveDocumentLocally(docToSave)
                _uiState.update {
                    it.copy(
                        currentDocument = docToSave,
                        isDirty = false
                    )
                }
            }
        }
    }

    fun createNewDocument() {
        saveDocument(autoSync = false)
        val newDoc = Document(
            title = "Untitled",
            content = "# Untitled\n\n"
        )
        viewModelScope.launch {
            repository.saveDocumentLocally(newDoc)
            _uiState.update {
                it.copy(
                    currentDocument = newDoc,
                    title = newDoc.title,
                    markdownContent = newDoc.content,
                    isDirty = false
                )
            }
        }
    }

    fun selectDocument(doc: Document) {
        if (doc.id == _uiState.value.currentDocument.id) return
        saveDocument(autoSync = false)
        _uiState.update {
            it.copy(
                currentDocument = doc,
                title = doc.title,
                markdownContent = doc.content,
                isDirty = false
            )
        }
    }

    fun deleteDocument(doc: Document) {
        viewModelScope.launch {
            repository.deleteDocument(doc.id)
            if (doc.id == _uiState.value.currentDocument.id) {
                val remaining = _uiState.value.allDocuments.filter { it.id != doc.id }
                if (remaining.isNotEmpty()) {
                    selectDocument(remaining.first())
                } else {
                    createNewDocument()
                }
            }
        }
    }

    // Google Drive Dialog & Operations
    fun openDriveDialog() {
        _uiState.update { it.copy(isDriveDialogVisible = true) }
        loadDriveFiles()
    }

    fun closeDriveDialog() {
        _uiState.update { it.copy(isDriveDialogVisible = false, driveErrorMessage = null) }
    }

    fun loadDriveFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDriveFiles = true, driveErrorMessage = null) }
            val result = repository.listDriveFiles()
            result.onSuccess { files ->
                _uiState.update {
                    it.copy(
                        driveFiles = files,
                        isLoadingDriveFiles = false
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        driveErrorMessage = ex.message ?: "Failed to list files from Google Drive",
                        isLoadingDriveFiles = false
                    )
                }
            }
        }
    }

    fun importDriveFile(file: DriveFileInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDriveFiles = true) }
            val result = repository.importFromDrive(file.id, file.name)
            result.onSuccess { importedDoc ->
                _uiState.update {
                    it.copy(
                        currentDocument = importedDoc,
                        title = importedDoc.title,
                        markdownContent = importedDoc.content,
                        isDirty = false,
                        isDriveDialogVisible = false,
                        isLoadingDriveFiles = false,
                        snackbarMessage = "Imported '${file.name}' from Drive"
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isLoadingDriveFiles = false,
                        driveErrorMessage = ex.message ?: "Failed to read file from Drive"
                    )
                }
            }
        }
    }

    fun syncCurrentDocumentToDrive() {
        val state = _uiState.value
        val doc = state.currentDocument.copy(
            title = state.title,
            content = state.markdownContent
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = repository.saveAndSyncDocument(doc)
            result.onSuccess { syncedDoc ->
                _uiState.update {
                    it.copy(
                        currentDocument = syncedDoc,
                        isDirty = false,
                        snackbarMessage = "Synced to Google Drive successfully!"
                    )
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(snackbarMessage = "Sync failed: ${ex.message}")
                }
            }
        }
    }

    fun onSignInResult(account: GoogleSignInAccount?) {
        authManager.updateAccount(account)
        if (account != null) {
            _uiState.update { it.copy(snackbarMessage = "Connected to Google Drive as ${account.email}") }
        }
    }

    fun signOut() {
        authManager.signOut {
            _uiState.update { it.copy(snackbarMessage = "Signed out of Google Drive") }
        }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
