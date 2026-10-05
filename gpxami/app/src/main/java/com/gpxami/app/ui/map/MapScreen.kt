package com.gpxami.app.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gpxami.app.ui.screens.MapAnimationScreen
import com.gpxami.app.ui.viewmodel.MapAnimationViewModel

/**
 * Top-level Composable screen entry point for Map visualization with persistent Start/End labels
 * and administrative division configuration.
 */
@Composable
fun MapScreen(
    viewModel: MapAnimationViewModel,
    modifier: Modifier = Modifier
) {
    MapAnimationScreen(viewModel = viewModel, modifier = modifier)
}
