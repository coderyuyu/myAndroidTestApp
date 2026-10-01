package com.mdedit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.mdedit.data.remote.DriveAuthManager
import com.mdedit.ui.screens.editor.EditorScreen
import com.mdedit.ui.screens.editor.EditorViewModel
import com.mdedit.ui.theme.MDEditTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: DriveAuthManager

    private val editorViewModel: EditorViewModel by viewModels()

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            editorViewModel.onSignInResult(account)
        } catch (_: ApiException) {
            editorViewModel.onSignInResult(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MDEditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorScreen(
                        viewModel = editorViewModel,
                        onSignInClick = {
                            signInLauncher.launch(authManager.getSignInIntent())
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh account state if changed externally
        val refreshed = authManager.refreshAccount()
        editorViewModel.onSignInResult(refreshed)
    }
}
