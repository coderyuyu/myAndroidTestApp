package com.mp3ext.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mp3ext.audio.AudioFormatType
import com.mp3ext.audio.AudioMetadata
import com.mp3ext.audio.PlaybackTrack
import com.mp3ext.audio.PlayerState
import com.mp3ext.audio.ProcessingState
import com.mp3ext.audio.formatDuration
import com.mp3ext.ui.theme.CyanPrimary
import com.mp3ext.ui.theme.EmeraldSuccess
import com.mp3ext.ui.theme.IndigoAccent
import com.mp3ext.ui.theme.RoseError
import com.mp3ext.ui.theme.SlateBorder
import com.mp3ext.ui.theme.SlateDarkBackground
import com.mp3ext.ui.theme.SlateDarkSurface
import com.mp3ext.ui.theme.SlateDarkSurfaceVariant
import com.mp3ext.ui.theme.TextPrimaryDark
import com.mp3ext.ui.theme.TextSecondaryDark
import com.mp3ext.ui.theme.VioletAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val metadata by viewModel.metadata.collectAsState()
    val targetDurationSec by viewModel.targetDurationSec.collectAsState()
    val crossfadeMs by viewModel.crossfadeMs.collectAsState()
    val processingState by viewModel.processingState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val processedFile by viewModel.processedFile.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFileSelected(it) }
    }

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(selectedFormat.mimeType)
    ) { destUri: Uri? ->
        destUri?.let { viewModel.saveProcessedAudio(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(listOf(CyanPrimary, IndigoAccent))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = SlateDarkBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "MP3 智慧長度延長",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark
                                )
                            )
                            Text(
                                text = "無縫循環與智慧拼接處理器",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextSecondaryDark
                                )
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateDarkBackground
                )
            )
        },
        containerColor = SlateDarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. File Selection Card
            FileSelectCard(
                metadata = metadata,
                onSelectFile = { filePickerLauncher.launch("audio/*") }
            )

            // 2. Metadata Information Card
            if (metadata != null) {
                AudioInfoCard(metadata = metadata!!)

                // 3. Target Duration Setting
                TargetDurationCard(
                    targetDurationSec = targetDurationSec,
                    metadata = metadata!!,
                    onTargetDurationChanged = { viewModel.setTargetDuration(it) },
                    onQuickAction = { viewModel.applyQuickAction(it) }
                )

                // 4. Advanced Seamless Settings
                AdvancedSettingsCard(
                    crossfadeMs = crossfadeMs,
                    onCrossfadeChanged = { viewModel.setCrossfadeMs(it) },
                    selectedFormat = selectedFormat,
                    onFormatSelected = { viewModel.setExportFormat(it) }
                )

                // 5. Action Execution Button
                ProcessActionButton(
                    processingState = processingState,
                    onStart = { viewModel.startProcessing() }
                )

                // 6. Playback Controller
                AudioPlayerCard(
                    playerState = playerState,
                    hasProcessed = processedFile != null,
                    metadata = metadata!!,
                    onTogglePlay = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) },
                    onSwitchTrack = { viewModel.switchPlaybackTrack(it) }
                )

                // 7. Export / Save Card
                if (processedFile != null) {
                    ExportCard(
                        metadata = metadata!!,
                        format = selectedFormat,
                        onExportClick = {
                            val defaultName = "extended_${metadata!!.fileName.substringBeforeLast('.')}.${selectedFormat.extension}"
                            exportLauncher.launch(defaultName)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FileSelectCard(
    metadata: AudioMetadata?,
    onSelectFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
            .clickable { onSelectFile() },
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyanPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (metadata == null) "點擊選取 MP3 音訊檔案" else "已選取音訊檔案",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark
                    )
                )
                Text(
                    text = if (metadata == null) "支援 MP3, AAC, M4A, WAV 等格式" else metadata.fileName,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onSelectFile,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, Brush.linearGradient(listOf(CyanPrimary, IndigoAccent)))
            ) {
                Text(text = if (metadata == null) "選取" else "更換", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AudioInfoCard(metadata: AudioMetadata) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "音訊規格資訊",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark
                    )
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoBadge(label = "原始長度", value = metadata.durationFormatted, highlight = true)
                InfoBadge(label = "取樣頻率", value = "${metadata.sampleRate / 1000.0} kHz")
                InfoBadge(label = "聲道", value = if (metadata.channelCount == 1) "單聲道" else "立體聲")
                InfoBadge(label = "檔案大小", value = metadata.fileSizeFormatted)
            }
        }
    }
}

@Composable
private fun InfoBadge(label: String, value: String, highlight: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) CyanPrimary.copy(alpha = 0.12f) else SlateDarkSurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark, fontSize = 10.sp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = if (highlight) CyanPrimary else TextPrimaryDark,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetDurationCard(
    targetDurationSec: Int,
    metadata: AudioMetadata,
    onTargetDurationChanged: (Int) -> Unit,
    onQuickAction: (QuickDurationAction) -> Unit
) {
    val origSec = (metadata.durationMs / 1000).toInt()
    val multiplier = if (origSec > 0) String.format("%.1fx", targetDurationSec.toFloat() / origSec) else "1.0x"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = VioletAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "設定目標延長時長",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                    )
                }

                // Multiplier Chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = VioletAccent.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, VioletAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = multiplier,
                        color = VioletAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Big Clock Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateDarkSurfaceVariant)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatDuration(targetDurationSec * 1000L),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = CyanPrimary,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slider for Fine-tuning
            val minutes = targetDurationSec / 60
            val seconds = targetDurationSec % 60

            Text(
                text = "總時長調整：$minutes 分 $seconds 秒",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark)
            )

            Slider(
                value = targetDurationSec.toFloat(),
                onValueChange = { onTargetDurationChanged(it.toInt()) },
                valueRange = 10f..1800f, // 10s to 30 mins
                colors = SliderDefaults.colors(
                    thumbColor = CyanPrimary,
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = SlateBorder
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Shortcut Chips
            Text(
                text = "快速快捷增加：",
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark)
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickChip(text = "+30 秒") { onQuickAction(QuickDurationAction.Plus30s) }
                QuickChip(text = "+1 分鐘") { onQuickAction(QuickDurationAction.Plus60s) }
                QuickChip(text = "2x (雙倍原長)") { onQuickAction(QuickDurationAction.DoubleLength) }
                QuickChip(text = "3x (三倍原長)") { onQuickAction(QuickDurationAction.TripleLength) }
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        color = SlateDarkSurfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(imageVector = Icons.Default.MoreTime, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = text, style = MaterialTheme.typography.labelSmall.copy(color = TextPrimaryDark))
        }
    }
}

@Composable
private fun AdvancedSettingsCard(
    crossfadeMs: Int,
    onCrossfadeChanged: (Int) -> Unit,
    selectedFormat: AudioFormatType,
    onFormatSelected: (AudioFormatType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = IndigoAccent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "進階拼接與編碼設定",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                    )
                }
                Text(
                    text = if (expanded) "收起 ▲" else "展開 ▼",
                    style = MaterialTheme.typography.labelSmall.copy(color = CyanPrimary)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    // Crossfade duration slider
                    Text(
                        text = "平滑交叉淡入淡出（Crossfade）：${crossfadeMs} ms",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark)
                    )
                    Slider(
                        value = crossfadeMs.toFloat(),
                        onValueChange = { onCrossfadeChanged(it.toInt()) },
                        valueRange = 300f..2500f,
                        steps = 21,
                        colors = SliderDefaults.colors(
                            thumbColor = IndigoAccent,
                            activeTrackColor = IndigoAccent,
                            inactiveTrackColor = SlateBorder
                        )
                    )
                    Text(
                        text = "數值越大銜接越平順；建議值 800ms ~ 1200ms。",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark, fontSize = 11.sp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Export Format Selection
                    Text(
                        text = "匯出音訊格式選擇：",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AudioFormatType.values().forEach { format ->
                        val isSelected = format == selectedFormat
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    if (isSelected) CyanPrimary else SlateBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onFormatSelected(format) },
                            color = if (isSelected) CyanPrimary.copy(alpha = 0.1f) else SlateDarkSurfaceVariant
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = format.displayName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = if (isSelected) CyanPrimary else TextPrimaryDark,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = CyanPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = format.description,
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark, fontSize = 11.sp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessActionButton(
    processingState: ProcessingState,
    onStart: () -> Unit
) {
    val isBusy = processingState is ProcessingState.Decoding ||
            processingState is ProcessingState.Extending ||
            processingState is ProcessingState.Encoding

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onStart,
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                contentColor = SlateDarkBackground,
                disabledContainerColor = SlateBorder,
                disabledContentColor = TextSecondaryDark
            )
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = SlateDarkBackground,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                val statusText = when (processingState) {
                    is ProcessingState.Decoding -> processingState.statusMessage
                    is ProcessingState.Extending -> processingState.statusMessage
                    is ProcessingState.Encoding -> processingState.statusMessage
                    else -> "正在處理音訊中..."
                }
                Text(text = statusText, fontWeight = FontWeight.Bold)
            } else {
                Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "開始智慧延長音訊", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Progress bar for long processing
        if (isBusy) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = CyanPrimary,
                trackColor = SlateDarkSurfaceVariant
            )
        }

        // Error message card
        if (processingState is ProcessingState.Error) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = RoseError.copy(alpha = 0.15f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RoseError.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = RoseError)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = processingState.message,
                        color = RoseError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerCard(
    playerState: PlayerState,
    hasProcessed: Boolean,
    metadata: AudioMetadata,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSwitchTrack: (PlaybackTrack) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Track Switch Tabs: [ 原始音訊 ] vs [ 延長後音訊 ]
            TabRow(
                selectedTabIndex = if (playerState.activeTrack == PlaybackTrack.ORIGINAL) 0 else 1,
                containerColor = SlateDarkSurfaceVariant,
                contentColor = CyanPrimary,
                indicator = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[if (playerState.activeTrack == PlaybackTrack.ORIGINAL) 0 else 1])
                            .height(3.dp)
                            .background(CyanPrimary)
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
            ) {
                Tab(
                    selected = playerState.activeTrack == PlaybackTrack.ORIGINAL,
                    onClick = { onSwitchTrack(PlaybackTrack.ORIGINAL) },
                    text = {
                        Text(
                            text = "原始音訊 (${metadata.durationFormatted})",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp)
                        )
                    }
                )
                Tab(
                    selected = playerState.activeTrack == PlaybackTrack.EXTENDED,
                    enabled = hasProcessed,
                    onClick = { onSwitchTrack(PlaybackTrack.EXTENDED) },
                    text = {
                        Text(
                            text = if (hasProcessed) "延長後音訊 (${formatDuration(playerState.durationMs)})" else "延長後音訊 (未生成)",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp)
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback progress slider
            val currentPos = playerState.currentPositionMs.toFloat()
            val totalDuration = playerState.durationMs.coerceAtLeast(1L).toFloat()

            var isDraggingSlider by remember { mutableStateOf(false) }
            var dragPos by remember { mutableFloatStateOf(0f) }

            Slider(
                value = if (isDraggingSlider) dragPos else currentPos.coerceIn(0f, totalDuration),
                onValueChange = {
                    isDraggingSlider = true
                    dragPos = it
                },
                onValueChangeFinished = {
                    onSeek(dragPos.toLong())
                    isDraggingSlider = false
                },
                valueRange = 0f..totalDuration,
                colors = SliderDefaults.colors(
                    thumbColor = CyanPrimary,
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = SlateBorder
                )
            )

            // Current Time & Total Time Labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(if (isDraggingSlider) dragPos.toLong() else playerState.currentPositionMs),
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark, fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = formatDuration(playerState.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark, fontFamily = FontFamily.Monospace)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Play / Pause Circle Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CyanPrimary, IndigoAccent)))
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "暫停" else "播放",
                        tint = SlateDarkBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    metadata: AudioMetadata,
    format: AudioFormatType,
    onExportClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, EmeraldSuccess.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateDarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "延長音訊已就緒",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = EmeraldSuccess
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "格式：${format.displayName}。點擊下方按鈕將檔案儲存至手機音樂或下載資料夾。",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onExportClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldSuccess,
                    contentColor = Color.White
                )
            ) {
                Icon(imageVector = Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "儲存 / 匯出延長音訊檔案", fontWeight = FontWeight.Bold)
            }
        }
    }
}
