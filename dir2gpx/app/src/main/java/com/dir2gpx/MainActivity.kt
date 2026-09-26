package com.dir2gpx

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.dir2gpx.ui.screen.MainScreen
import com.dir2gpx.ui.theme.Dir2GpxTheme

/**
 * Single-activity entry point for Dir2GPX.
 *
 * Handles:
 * - Edge-to-edge display setup
 * - Intent-based URL reception from share actions
 * - Compose UI tree initialization with Material 3 theme
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared URL from intent (if launched via share sheet)
        val sharedUrl = extractSharedUrl(intent)

        setContent {
            Dir2GpxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(initialUrl = sharedUrl)
                }
            }
        }
    }

    /**
     * Extracts a Google Maps URL from a share intent.
     */
    private fun extractSharedUrl(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_SEND) return ""
        if (intent.type != "text/plain") return ""

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return ""

        // Extract URL from shared text (may contain additional text)
        val urlRegex = Regex("""https?://[^\s]+""")
        return urlRegex.find(sharedText)?.value ?: sharedText
    }
}
