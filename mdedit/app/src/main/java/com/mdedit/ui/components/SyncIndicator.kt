package com.mdedit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdedit.domain.model.SyncStatus
import com.mdedit.ui.theme.StatusError
import com.mdedit.ui.theme.StatusPending
import com.mdedit.ui.theme.StatusSynced
import com.mdedit.ui.theme.StatusSyncing

@Composable
fun SyncIndicator(
    status: SyncStatus,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false
) {
    val (icon, color, text) = when {
        status == SyncStatus.SYNCING -> {
            Triple(Icons.Default.CloudSync, StatusSyncing, "Syncing...")
        }
        status == SyncStatus.SYNCED -> {
            Triple(Icons.Default.CloudDone, StatusSynced, "Synced")
        }
        status == SyncStatus.PENDING_UPLOAD -> {
            Triple(Icons.Default.CloudUpload, StatusPending, if (isOnline) "Pending sync" else "Offline")
        }
        status == SyncStatus.ERROR -> {
            Triple(Icons.Default.Warning, StatusError, "Sync error")
        }
        else -> {
            Triple(Icons.Default.CloudOff, MaterialTheme.colorScheme.outline, "Local only")
        }
    }

    val rotation = if (status == SyncStatus.SYNCING) {
        val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing)
            ),
            label = "syncAngle"
        )
        angle
    } else 0f

    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = color,
            modifier = Modifier
                .size(16.dp)
                .rotate(rotation)
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
