package com.mdedit.ui.screens.editor

import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.mdedit.domain.model.Document
import com.mdedit.domain.model.DriveFileInfo
import com.mdedit.domain.model.EditorFormatState

data class EditorUiState(
    val currentDocument: Document = Document(),
    val markdownContent: String = "",
    val title: String = "Untitled",
    val isDirty: Boolean = false,
    val isVisualMode: Boolean = true,
    val formatState: EditorFormatState = EditorFormatState(),
    val allDocuments: List<Document> = emptyList(),
    val driveFiles: List<DriveFileInfo> = emptyList(),
    val isLoadingDriveFiles: Boolean = false,
    val driveErrorMessage: String? = null,
    val isDriveDialogVisible: Boolean = false,
    val isOnline: Boolean = true,
    val isSyncing: Boolean = false,
    val signedInAccount: GoogleSignInAccount? = null,
    val snackbarMessage: String? = null
)
