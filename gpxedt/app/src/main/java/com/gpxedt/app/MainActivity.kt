package com.gpxedt.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.gpxedt.app.ui.GpxEditorScreen
import com.gpxedt.app.ui.theme.GPXEditorTheme
import com.gpxedt.app.viewmodel.GpxEditorViewModel

/**
 * Storage Access Framework document picker configured specifically for .gpx files
 * with default initial URI pointing to device Downloads folder.
 */
class OpenGpxDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/gpx+xml",
                    "application/gpx",
                    "text/xml",
                    "application/xml",
                    "application/octet-stream",
                    "*/*"
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val downloadsUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
            }
        }
        return intent
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: GpxEditorViewModel by viewModels()

    // SAF Open Document launcher
    private val openDocumentLauncher = registerForActivityResult(
        OpenGpxDocumentContract()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadFromUri(uri, contentResolver)
        }
    }

    // SAF Create Document launcher for "Save As"
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.saveToUri(uri, contentResolver)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.data?.let { uri ->
            viewModel.loadFromUri(uri, contentResolver)
        }

        setContent {
            GPXEditorTheme {
                GpxEditorScreen(
                    viewModel = viewModel,
                    onOpenGpxFile = {
                        openDocumentLauncher.launch(
                            arrayOf(
                                "application/gpx+xml",
                                "application/xml",
                                "text/xml",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onSaveGpxFile = {
                        val currentUri = viewModel.uiState.value.fileUri
                        if (currentUri != null) {
                            viewModel.openSaveConfirmDialog()
                        } else {
                            val defaultName = viewModel.uiState.value.fileName ?: "track_edited.gpx"
                            createDocumentLauncher.launch(defaultName)
                        }
                    },
                    onSaveAsGpxFile = {
                        val currentName = viewModel.uiState.value.fileName ?: "track_edited.gpx"
                        val baseName = if (currentName.endsWith(".gpx", ignoreCase = true)) {
                            currentName.removeSuffix(".gpx") + "_edited.gpx"
                        } else {
                            "$currentName.gpx"
                        }
                        createDocumentLauncher.launch(baseName)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            viewModel.loadFromUri(uri, contentResolver)
        }
    }
}
