package com.mdedit.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdedit.ui.components.DocumentListDrawer
import com.mdedit.ui.components.DriveFileListDialog
import com.mdedit.ui.components.EditorToolbar
import com.mdedit.ui.components.SyncIndicator
import com.mdedit.ui.components.WysiwygEditorView
import com.mdedit.ui.components.rememberWysiwygEditorController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val editorController = rememberWysiwygEditorController()

    // Sync content to WebView when selected document changes
    LaunchedEffect(uiState.currentDocument.id) {
        editorController.setMarkdown(uiState.markdownContent)
    }

    // Show snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DocumentListDrawer(
                documents = uiState.allDocuments,
                selectedDocumentId = uiState.currentDocument.id,
                account = uiState.signedInAccount,
                isOnline = uiState.isOnline,
                onDocumentSelect = { doc ->
                    viewModel.selectDocument(doc)
                    coroutineScope.launch { drawerState.close() }
                },
                onDocumentDelete = { doc ->
                    viewModel.deleteDocument(doc)
                },
                onNewDocument = {
                    viewModel.createNewDocument()
                    coroutineScope.launch { drawerState.close() }
                },
                onOpenDriveDialog = {
                    viewModel.openDriveDialog()
                    coroutineScope.launch { drawerState.close() }
                },
                onSignIn = onSignInClick,
                onSignOut = { viewModel.signOut() }
            )
        }
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            BasicTextField(
                                value = uiState.title,
                                onValueChange = { viewModel.onTitleChanged(it) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (uiState.title.isEmpty()) {
                                        Text(
                                            "Untitled.md",
                                            style = TextStyle(
                                                fontSize = 18.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            // Sync Status Indicator Badge
                            SyncIndicator(
                                status = uiState.currentDocument.syncStatus,
                                isOnline = uiState.isOnline,
                                showLabel = true
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Navigation Menu"
                            )
                        }
                    },
                    actions = {
                        // Toggle Visual (WYSIWYG) vs Raw Markdown Mode
                        IconButton(onClick = {
                            viewModel.toggleVisualMode()
                            if (!uiState.isVisualMode) {
                                // Switching back to Visual Mode: update webview
                                editorController.setMarkdown(uiState.markdownContent)
                            }
                        }) {
                            Icon(
                                imageVector = if (uiState.isVisualMode) Icons.Default.Code else Icons.Default.Preview,
                                contentDescription = if (uiState.isVisualMode) "Switch to Raw Markdown" else "Switch to WYSIWYG",
                                tint = if (!uiState.isVisualMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Manual Save / Cloud Sync Action
                        IconButton(onClick = { viewModel.syncCurrentDocumentToDrive() }) {
                            Icon(
                                imageVector = if (uiState.signedInAccount != null) Icons.Default.CloudSync else Icons.Default.Save,
                                contentDescription = "Save and Sync",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Toolbar (Active during Visual Mode)
                if (uiState.isVisualMode) {
                    EditorToolbar(
                        formatState = uiState.formatState,
                        onAction = { action ->
                            editorController.executeAction(action)
                        }
                    )
                }

                // Editor View Area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    if (uiState.isVisualMode) {
                        WysiwygEditorView(
                            controller = editorController,
                            onContentChanged = { markdown ->
                                viewModel.onContentChanged(markdown)
                            },
                            onFormatStateChanged = { formatState ->
                                viewModel.onFormatStateChanged(formatState)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Raw Markdown text editor mode
                        TextField(
                            value = uiState.markdownContent,
                            onValueChange = { newContent ->
                                viewModel.onContentChanged(newContent)
                            },
                            modifier = Modifier.fillMaxSize(),
                            placeholder = { Text("Type raw Markdown here...") },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    }

    // Google Drive File Picker Dialog
    if (uiState.isDriveDialogVisible) {
        DriveFileListDialog(
            files = uiState.driveFiles,
            isLoading = uiState.isLoadingDriveFiles,
            errorMessage = uiState.driveErrorMessage,
            onFileSelected = { file ->
                viewModel.importDriveFile(file)
            },
            onRefresh = {
                viewModel.loadDriveFiles()
            },
            onDismiss = {
                viewModel.closeDriveDialog()
            }
        )
    }
}
