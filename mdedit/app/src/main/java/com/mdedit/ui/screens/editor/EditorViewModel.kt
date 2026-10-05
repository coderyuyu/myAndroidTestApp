package com.mdedit.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.mdedit.data.parser.MarkdownParser
import com.mdedit.data.remote.DriveAuthManager
import com.mdedit.data.repository.DocumentRepository
import com.mdedit.data.repository.FileRepository
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import com.mdedit.domain.model.EditorFormatState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val fileRepository: FileRepository,
    private val authManager: DriveAuthManager,
    private val markdownParser: MarkdownParser,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val KEY_DOC_ID = "key_editor_doc_id"
        const val KEY_TITLE = "key_editor_title"
        const val KEY_CONTENT = "key_editor_content"
        const val KEY_IS_DIRTY = "key_editor_is_dirty"
        const val KEY_FILE_URI = "key_editor_file_uri"
        const val KEY_IS_PREVIEW = "key_editor_is_preview"
        const val KEY_IS_VISUAL = "key_editor_is_visual"
    }

    private val _uiState: MutableStateFlow<EditorUiState>
    val uiState: StateFlow<EditorUiState>

    private val autoSaveTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val markdownPreviewTrigger = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)

    init {
        // Restore state from SavedStateHandle to survive configuration changes & process death
        val restoredContent = savedStateHandle.get<String>(KEY_CONTENT)
        val restoredTitle = savedStateHandle.get<String>(KEY_TITLE)
        val restoredIsDirty = savedStateHandle.get<Boolean>(KEY_IS_DIRTY) ?: false
        val restoredUriStr = savedStateHandle.get<String>(KEY_FILE_URI)
        val restoredIsPreview = savedStateHandle.get<Boolean>(KEY_IS_PREVIEW) ?: false
        val restoredIsVisual = savedStateHandle.get<Boolean>(KEY_IS_VISUAL) ?: false

        val initialUri = restoredUriStr?.let { Uri.parse(it) }

        _uiState = MutableStateFlow(
            EditorUiState(
                title = restoredTitle ?: "Untitled",
                markdownContent = restoredContent ?: "",
                isDirty = restoredIsDirty,
                currentFileUri = initialUri,
                isPreviewMode = restoredIsPreview,
                isVisualMode = restoredIsVisual
            )
        )
        uiState = _uiState.asStateFlow()

        observeDocuments()
        observeNetworkAndSync()
        observeAuth()
        setupAutoSave()
        setupMarkdownPreviewDebounce()

        // Initial preview parse if restored content is present
        if (!restoredContent.isNullOrEmpty()) {
            markdownPreviewTrigger.tryEmit(restoredContent)
        }
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.getAllDocuments().collect { docs ->
                _uiState.update { current ->
                    // If user has unsaved edits in active session or restored from process death, preserve them
                    if (current.isDirty) {
                        return@update current.copy(allDocuments = docs)
                    }

                    if (docs.isEmpty()) {
                        val defaultDoc = Document(
                            title = "Welcome to MDEdit",
                            content = "# Welcome to MDEdit \n\nA **What-You-See-Is-What-You-Get** Markdown Editor with Google Drive sync.\n\n- [x] Rich visual formatting\n- [x] Auto-save to local storage\n- [x] Google Drive cloud sync\n- [ ] Try creating your own notes!\n\n> Edit comfortably with inline styles or switch to raw markdown mode anytime."
                        )
                        viewModelScope.launch { repository.saveDocumentLocally(defaultDoc) }
                        markdownPreviewTrigger.tryEmit(defaultDoc.content)
                        current.copy(
                            allDocuments = listOf(defaultDoc),
                            currentDocument = defaultDoc,
                            title = defaultDoc.title,
                            markdownContent = defaultDoc.content
                        )
                    } else {
                        val updatedCurrent = docs.find { it.id == current.currentDocument.id } ?: docs.first()
                        val shouldUpdateContent = current.markdownContent.isEmpty() && !current.isDirty
                        if (shouldUpdateContent) {
                            markdownPreviewTrigger.tryEmit(updatedCurrent.content)
                        }
                        current.copy(
                            allDocuments = docs,
                            currentDocument = updatedCurrent,
                            title = if (shouldUpdateContent) updatedCurrent.title else current.title,
                            markdownContent = if (shouldUpdateContent) updatedCurrent.content else current.markdownContent
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
                .debounce(2500L)
                .collectLatest {
                    saveDocument(autoSync = true)
                }
        }
    }

    private fun setupMarkdownPreviewDebounce() {
        viewModelScope.launch {
            markdownPreviewTrigger
                .debounce(300L) // 300ms debounce to prevent frame drops and recomposition loops
                .collectLatest { text ->
                    val parsed = markdownParser.parseToAnnotatedString(text)
                    _uiState.update { it.copy(parsedMarkdown = parsed) }
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
        savedStateHandle[KEY_CONTENT] = newContent
        savedStateHandle[KEY_IS_DIRTY] = true

        autoSaveTrigger.tryEmit(Unit)
        markdownPreviewTrigger.tryEmit(newContent)
    }

    fun onTitleChanged(newTitle: String) {
        if (_uiState.value.title == newTitle) return
        _uiState.update {
            it.copy(
                title = newTitle,
                isDirty = true
            )
        }
        savedStateHandle[KEY_TITLE] = newTitle
        savedStateHandle[KEY_IS_DIRTY] = true

        autoSaveTrigger.tryEmit(Unit)
    }

    fun onFormatStateChanged(formatState: EditorFormatState) {
        _uiState.update { it.copy(formatState = formatState) }
    }

    fun toggleVisualMode() {
        val nextVisual = !_uiState.value.isVisualMode
        _uiState.update { it.copy(isVisualMode = nextVisual) }
        savedStateHandle[KEY_IS_VISUAL] = nextVisual
    }

    fun togglePreviewMode() {
        val nextPreview = !_uiState.value.isPreviewMode
        _uiState.update { it.copy(isPreviewMode = nextPreview) }
        savedStateHandle[KEY_IS_PREVIEW] = nextPreview
        if (nextPreview) {
            // Immediately refresh preview when opening preview mode
            markdownPreviewTrigger.tryEmit(_uiState.value.markdownContent)
        }
    }

    // =========================================================================
    // Storage Access Framework (SAF) File Operations
    // =========================================================================

    /**
     * Loads a file from a Storage Access Framework (SAF) [Uri].
     * Executed strictly on background IO without blocking the main UI thread.
     */
    fun openFileFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = fileRepository.readFile(uri)
            result.onSuccess { loaded ->
                _uiState.update {
                    it.copy(
                        currentFileUri = loaded.uri,
                        title = loaded.filename,
                        markdownContent = loaded.content,
                        isDirty = false,
                        isLoading = false,
                        errorMessage = null,
                        snackbarMessage = "Opened '${loaded.filename}'"
                    )
                }
                savedStateHandle[KEY_FILE_URI] = loaded.uri.toString()
                savedStateHandle[KEY_TITLE] = loaded.filename
                savedStateHandle[KEY_CONTENT] = loaded.content
                savedStateHandle[KEY_IS_DIRTY] = false

                // Trigger background markdown parse
                markdownPreviewTrigger.tryEmit(loaded.content)

                // Also save/cache into local document database
                val externalDoc = Document(
                    title = loaded.filename,
                    content = loaded.content
                )
                repository.saveDocumentLocally(externalDoc)
                _uiState.update { it.copy(currentDocument = externalDoc) }
            }.onFailure { ex ->
                val errorMsg = when (ex) {
                    is SecurityException -> "Permission denied: unable to access external file."
                    else -> "Failed to open file: ${ex.localizedMessage ?: "Unknown error"}"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = errorMsg
                    )
                }
            }
        }
    }

    /**
     * Saves current editor content to a SAF [Uri].
     */
    fun saveFileToUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val content = _uiState.value.markdownContent
            val result = fileRepository.writeFile(uri, content)
            result.onSuccess {
                val filename = fileRepository.getFileName(uri)
                _uiState.update {
                    it.copy(
                        currentFileUri = uri,
                        title = filename,
                        isDirty = false,
                        isLoading = false,
                        errorMessage = null,
                        snackbarMessage = "Saved '$filename' successfully"
                    )
                }
                savedStateHandle[KEY_FILE_URI] = uri.toString()
                savedStateHandle[KEY_TITLE] = filename
                savedStateHandle[KEY_IS_DIRTY] = false

                // Also update local copy
                saveDocument(autoSync = false)
            }.onFailure { ex ->
                val errorMsg = when (ex) {
                    is SecurityException -> "Permission denied: unable to save file to destination."
                    else -> "Failed to save file: ${ex.localizedMessage ?: "Unknown error"}"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = errorMsg
                    )
                }
            }
        }
    }

    fun saveCurrentFile() {
        val currentUri = _uiState.value.currentFileUri
        if (currentUri != null) {
            saveFileToUri(currentUri)
        } else {
            saveDocument(autoSync = true)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // =========================================================================
    // Document Database & Google Drive Operations
    // =========================================================================

    fun saveDocument(autoSync: Boolean = true) {
        val state = _uiState.value
        val docToSave = state.currentDocument.copy(
            title = state.title.ifBlank { "Untitled" },
            content = state.markdownContent,
            localModifiedAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            if (autoSync && state.isOnline && state.signedInAccount != null && docToSave.driveFileId != null) {
                val result = repository.saveAndSyncDocument(docToSave)
                result.onSuccess { synced ->
                    _uiState.update {
                        it.copy(
                            currentDocument = synced,
                            isDirty = false
                        )
                    }
                    savedStateHandle[KEY_IS_DIRTY] = false
                }.onFailure {
                    repository.saveDocumentLocally(docToSave)
                    _uiState.update { it.copy(isDirty = false) }
                    savedStateHandle[KEY_IS_DIRTY] = false
                }
            } else {
                repository.saveDocumentLocally(docToSave)
                _uiState.update {
                    it.copy(
                        currentDocument = docToSave,
                        isDirty = false
                    )
                }
                savedStateHandle[KEY_IS_DIRTY] = false
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
                    currentFileUri = null,
                    isDirty = false,
                    errorMessage = null
                )
            }
            savedStateHandle[KEY_DOC_ID] = newDoc.id
            savedStateHandle[KEY_TITLE] = newDoc.title
            savedStateHandle[KEY_CONTENT] = newDoc.content
            savedStateHandle[KEY_FILE_URI] = null
            savedStateHandle[KEY_IS_DIRTY] = false

            markdownPreviewTrigger.tryEmit(newDoc.content)
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
                currentFileUri = null,
                isDirty = false,
                errorMessage = null
            )
        }
        savedStateHandle[KEY_DOC_ID] = doc.id
        savedStateHandle[KEY_TITLE] = doc.title
        savedStateHandle[KEY_CONTENT] = doc.content
        savedStateHandle[KEY_FILE_URI] = null
        savedStateHandle[KEY_IS_DIRTY] = false

        markdownPreviewTrigger.tryEmit(doc.content)
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
                savedStateHandle[KEY_DOC_ID] = importedDoc.id
                savedStateHandle[KEY_TITLE] = importedDoc.title
                savedStateHandle[KEY_CONTENT] = importedDoc.content
                savedStateHandle[KEY_IS_DIRTY] = false

                markdownPreviewTrigger.tryEmit(importedDoc.content)
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
                savedStateHandle[KEY_IS_DIRTY] = false
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
}
