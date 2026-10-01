package com.mdedit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mdedit.domain.model.EditorFormatState

enum class ToolbarAction {
    BOLD,
    ITALIC,
    STRIKE,
    H1,
    H2,
    H3,
    BULLET_LIST,
    NUMBERED_LIST,
    CHECKLIST,
    BLOCKQUOTE,
    CODE_BLOCK,
    HORIZONTAL_RULE,
    UNDO,
    REDO,
    CLEAR_FORMAT
}

@Composable
fun EditorToolbar(
    formatState: EditorFormatState,
    onAction: (ToolbarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // History
            ToolbarIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                onClick = { onAction(ToolbarAction.UNDO) }
            )
            ToolbarIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                onClick = { onAction(ToolbarAction.REDO) }
            )

            ToolbarDivider()

            // Inline formatting
            ToolbarIconButton(
                icon = Icons.Default.FormatBold,
                contentDescription = "Bold",
                isActive = formatState.isBold,
                onClick = { onAction(ToolbarAction.BOLD) }
            )
            ToolbarIconButton(
                icon = Icons.Default.FormatItalic,
                contentDescription = "Italic",
                isActive = formatState.isItalic,
                onClick = { onAction(ToolbarAction.ITALIC) }
            )
            ToolbarIconButton(
                icon = Icons.Default.FormatStrikethrough,
                contentDescription = "Strikethrough",
                isActive = formatState.isStrike,
                onClick = { onAction(ToolbarAction.STRIKE) }
            )

            ToolbarDivider()

            // Headers
            ToolbarTextButton(
                text = "H1",
                isActive = formatState.headerLevel == 1,
                onClick = { onAction(ToolbarAction.H1) }
            )
            ToolbarTextButton(
                text = "H2",
                isActive = formatState.headerLevel == 2,
                onClick = { onAction(ToolbarAction.H2) }
            )
            ToolbarTextButton(
                text = "H3",
                isActive = formatState.headerLevel == 3,
                onClick = { onAction(ToolbarAction.H3) }
            )

            ToolbarDivider()

            // Lists & Blocks
            ToolbarIconButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Bullet List",
                isActive = formatState.isBulletList,
                onClick = { onAction(ToolbarAction.BULLET_LIST) }
            )
            ToolbarIconButton(
                icon = Icons.Default.FormatListNumbered,
                contentDescription = "Numbered List",
                isActive = formatState.isNumberedList,
                onClick = { onAction(ToolbarAction.NUMBERED_LIST) }
            )
            ToolbarIconButton(
                icon = Icons.Default.CheckBox,
                contentDescription = "Task Checklist",
                onClick = { onAction(ToolbarAction.CHECKLIST) }
            )
            ToolbarIconButton(
                icon = Icons.Default.FormatQuote,
                contentDescription = "Quote",
                isActive = formatState.isBlockquote,
                onClick = { onAction(ToolbarAction.BLOCKQUOTE) }
            )
            ToolbarIconButton(
                icon = Icons.Default.Code,
                contentDescription = "Code Block",
                isActive = formatState.isCodeBlock,
                onClick = { onAction(ToolbarAction.CODE_BLOCK) }
            )
            ToolbarIconButton(
                icon = Icons.Default.HorizontalRule,
                contentDescription = "Divider",
                onClick = { onAction(ToolbarAction.HORIZONTAL_RULE) }
            )

            ToolbarDivider()

            // Clear
            ToolbarIconButton(
                icon = Icons.Default.FormatClear,
                contentDescription = "Clear Formatting",
                onClick = { onAction(ToolbarAction.CLEAR_FORMAT) }
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ToolbarTextButton(
    text: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ToolbarDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    )
}
