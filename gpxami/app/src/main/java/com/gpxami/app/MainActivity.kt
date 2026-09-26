package com.gpxami.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gpxami.app.ui.screens.MapAnimationScreen
import com.gpxami.app.ui.theme.BackgroundDark
import com.gpxami.app.ui.theme.GPXAmiTheme
import com.gpxami.app.ui.viewmodel.MapAnimationViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MapAnimationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle GPX file shared / opened via Intent
        intent?.data?.let { gpxUri ->
            viewModel.loadGpxFromUri(gpxUri)
        }

        setContent {
            GPXAmiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BackgroundDark
                ) {
                    MapAnimationScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { gpxUri ->
            viewModel.loadGpxFromUri(gpxUri)
        }
    }
}
