package com.dir2gpx.model

/**
 * Sealed interface representing the UI state of the main screen.
 * Used by [com.dir2gpx.viewmodel.MainViewModel] as a `StateFlow<UiState>`.
 */
sealed interface UiState {

    /** Initial state — no conversion has been attempted. */
    data object Idle : UiState

    /** A conversion is in progress (URL expansion, parsing, GPX generation). */
    data object Loading : UiState

    /** Conversion succeeded — contains the generated GPX data for display and export. */
    data class Success(val gpxData: GpxData) : UiState

    /** Conversion failed — contains a user-friendly error message. */
    data class Error(val message: String) : UiState
}
