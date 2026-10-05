package com.gpxedt.app.ui.map.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Edit Mode Lock/Unlock Toggle Button (軌跡點地圖編輯防誤觸開關).
 *
 * When locked (false):
 *  - Displays closed lock icon [Icons.Default.Lock]
 *  - Neutral / muted container color indicating editing is locked to prevent accidental touches.
 *
 * When unlocked (true):
 *  - Displays open lock icon [Icons.Default.LockOpen]
 *  - Highlighted with accent/primary color indicating active editing mode.
 */
@Composable
fun EditModeToggleButton(
    isEditMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onToggle,
        shape = CircleShape,
        containerColor = if (isEditMode) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        },
        contentColor = if (isEditMode) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = if (isEditMode) Icons.Default.LockOpen else Icons.Default.Lock,
            contentDescription = if (isEditMode) {
                "Edit mode enabled (Tap to lock)"
            } else {
                "Edit mode locked (Tap to unlock)"
            },
            modifier = Modifier.size(24.dp)
        )
    }
}
