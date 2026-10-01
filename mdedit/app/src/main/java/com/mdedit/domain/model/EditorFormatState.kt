package com.mdedit.domain.model

data class EditorFormatState(
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isStrike: Boolean = false,
    val isBulletList: Boolean = false,
    val isNumberedList: Boolean = false,
    val headerLevel: Int = 0,
    val isBlockquote: Boolean = false,
    val isCodeBlock: Boolean = false
)
