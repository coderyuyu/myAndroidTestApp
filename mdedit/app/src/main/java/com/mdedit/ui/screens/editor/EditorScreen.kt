package com.mdedit.ui.screens.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.AnnotatedString
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

    // SAF Activity Result Launchers
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.openFileFromUri(it) }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let { viewModel.saveFileToUri(it) }
    }

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
                onOpenFile = {
                    coroutineScope.launch { drawerState.close() }
                    openDocumentLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                },
                onSaveAs = {
                    coroutineScope.launch { drawerState.close() }
                    val filename = if (uiState.title.endsWith(".md", ignoreCase = true)) {
                        uiState.title
                    } else {
                        "${uiState.title.ifEmpty { "document" }}.md"
                    }
                    saveDocumentLauncher.launch(filename)
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
                                    fontSize = 17.sp,
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
                                                fontSize = 17.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            // Sync Status Indicator Badge
                            SyncIndicator(
                                status = uiState.currentDocument.syncStatus,
                                isOnline = uiState.isOnline,
                                showLabel = false
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
                        // Open External SAF Document
                        IconButton(onClick = {
                            openDocumentLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                        }) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Open Local File",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Toggle Preview Mode vs Editor Mode
                        IconButton(onClick = {
                            viewModel.togglePreviewMode()
                        }) {
                            Icon(
                                imageVector = if (uiState.isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = if (uiState.isPreviewMode) "Switch to Editor" else "Switch to Preview",
                                tint = if (uiState.isPreviewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Toggle Visual (WYSIWYG) vs Raw Markdown Mode (when in edit mode)
                        if (!uiState.isPreviewMode) {
                            IconButton(onClick = {
                                viewModel.toggleVisualMode()
                                if (!uiState.isVisualMode) {
                                    editorController.setMarkdown(uiState.markdownContent)
                                }
                            }) {
                                Icon(
                                    imageVector = if (uiState.isVisualMode) Icons.Default.Code else Icons.Default.AutoFixHigh,
                                    contentDescription = if (uiState.isVisualMode) "Switch to Raw Markdown" else "Switch to Visual WYSIWYG",
                                    tint = if (uiState.isVisualMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Save As / Export to SAF
                        IconButton(onClick = {
                            val filename = if (uiState.title.endsWith(".md", ignoreCase = true)) {
                                uiState.title
                            } else {
                                "${uiState.title.ifEmpty { "document" }}.md"
                            }
                            saveDocumentLauncher.launch(filename)
                        }) {
                            Icon(
                                imageVector = Icons.Default.SaveAs,
                                contentDescription = "Save As / Export",
                                tint = MaterialTheme.colorScheme.onSurface
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
                // Graceful Error Banner
                if (uiState.errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearErrorMessage() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss error",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Toolbar (Active during Visual Mode when not in Preview)
                if (uiState.isVisualMode && !uiState.isPreviewMode) {
                    EditorToolbar(
                        formatState = uiState.formatState,
                        onAction = { action ->
                            editorController.executeAction(action)
                        }
                    )
                }

                // Content View Area: Preview vs Visual vs Raw Text
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    when {
                        uiState.isPreviewMode -> {
                            MarkdownPreview(
                                annotatedString = uiState.parsedMarkdown,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        uiState.isVisualMode -> {
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
                        }
                        else -> {
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

/**
 * Formatted Markdown preview composable.
 * Renders the pre-parsed, styled [AnnotatedString] smoothly without freezing the UI.
 */
@Composable
fun MarkdownPreview(
    annotatedString: AnnotatedString,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SelectionContainer {
            if (annotatedString.isEmpty()) {
                Text(
                    text = "No content to preview",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                Text(
                    text = annotatedString,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        }
    }
}
