package com.gpxami.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.GpxWaypoint
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.data.model.WptVisibilityMode
import com.gpxami.app.export.VideoEncoder
import com.gpxami.app.map.MapRenderer
import com.gpxami.app.map.MapStyle
import com.gpxami.app.ui.components.ElevationProfileView
import com.gpxami.app.ui.theme.*
import com.gpxami.app.ui.viewmodel.ExportState
import com.gpxami.app.ui.viewmodel.MapAnimationViewModel
import com.gpxami.app.ui.viewmodel.MapUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Storage Access Framework document picker configured specifically for .gpx files
 * with default initial URI pointing to second-to-last directory (上上次目錄) or Downloads.
 */
class OpenGpxDocumentContract(
    private val getInitialUri: () -> Uri? = { null }
) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/gpx+xml",
                    "application/gpx",
                    "text/xml",
                    "application/xml",
                    "application/octet-stream",
                    "*/*"
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val targetUri = getInitialUri()
                    ?: Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, targetUri)
            }
        }
        return intent
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapAnimationScreen(
    viewModel: MapAnimationViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // [需求 6] Storage Access Framework (SAF) document picker with default pointing to 上上次目錄
    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = OpenGpxDocumentContract { uiState.initialPickerUri }
    ) { uri: Uri? ->
        uri?.let { viewModel.loadGpxFromUri(it) }
    }

    var showExportSettingsDialog by remember { mutableStateOf(false) }
    var showEditTitleDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = CyanNeon,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "GPXAmi",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    // Open GPX File Button
                    FilledTonalButton(
                        onClick = {
                            gpxPickerLauncher.launch(
                                arrayOf(
                                    "application/gpx+xml",
                                    "application/gpx",
                                    "text/xml",
                                    "application/xml",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SurfaceDarkElevated,
                            contentColor = TextPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = CyanPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "開啟 GPX", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Load Demo Track Button
                    IconButton(
                        onClick = { viewModel.loadDemoTrack() },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "重新載入示範路徑"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDark,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // =========================================================================
            // 1. STRICT 16:9 ANIMATION & PREVIEW CONTAINER WITH GESTURES
            // =========================================================================
            val currentTrack = uiState.track
            val currentProgress = uiState.progress
            val currentInterpolated = uiState.interpolatedPoint
            val currentRotation by rememberUpdatedState(uiState.mapRotation)

            val density = LocalDensity.current.density
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .aspectRatio(16f / 9f) // Strictly enforces 16:9 aspect ratio
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .onSizeChanged { intSize ->
                        if (intSize.width > 0 && intSize.height > 0) {
                            viewModel.setUiViewportSize(intSize.width.toFloat(), intSize.height.toFloat(), density)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, rotationChange ->
                            if (panChange.x != 0f || panChange.y != 0f) {
                                val rad = Math.toRadians(currentRotation.toDouble())
                                val cosTheta = cos(rad).toFloat()
                                val sinTheta = sin(rad).toFloat()
                                val canvasPanX = panChange.x * cosTheta + panChange.y * sinTheta
                                val canvasPanY = -panChange.x * sinTheta + panChange.y * cosTheta
                                viewModel.onPan(canvasPanX, canvasPanY)
                            }
                            if (zoomChange != 1.0f) {
                                val delta = ln(zoomChange.toDouble()) / ln(2.0)
                                viewModel.adjustZoom(delta)
                            }
                            if (rotationChange != 0f) {
                                viewModel.setMapRotation(currentRotation + rotationChange)
                            }
                        }
                    }
            ) {
                if (currentTrack != null && currentInterpolated != null) {
                    // LAYER A: High-Performance Canvas Map Layer
                    var tileEpoch by remember { mutableLongStateOf(0L) }
                    val mapRenderer = remember {
                        MapRenderer(context.cacheDir).apply {
                            onTileLoaded = {
                                tileEpoch = System.currentTimeMillis()
                            }
                        }
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (tileEpoch >= 0L) {
                            drawIntoCanvas { canvas ->
                                val optOverviewZoom = mapRenderer.calculateOptimalOverviewZoom(
                                    track = currentTrack,
                                    viewportWidth = size.width,
                                    viewportHeight = size.height,
                                    rotationDegrees = uiState.mapRotation,
                                    showElevationProfile = uiState.showElevationProfile
                                )
                                val optZoom = optOverviewZoom + 1.0 + uiState.zoomOffset + uiState.cameraZoomTransitionOffset
                                mapRenderer.renderMap(
                                    canvas = canvas.nativeCanvas,
                                    width = size.width,
                                    height = size.height,
                                    track = currentTrack,
                                    currentProgress = currentProgress,
                                    interpolatedPoint = currentInterpolated,
                                    autoFollowMarker = true,
                                    customZoom = optZoom,
                                    rotationDegrees = uiState.mapRotation,
                                    mapStyle = uiState.mapStyle,
                                    drawCompassOverlay = false,
                                    panOffsetX = uiState.panOffsetX,
                                    panOffsetY = uiState.panOffsetY,
                                    focusPoint = uiState.focusPoint,
                                    showWaypointLabels = uiState.showWaypointLabels,
                                    wptLabelTextSize = uiState.wptLabelTextSize * density,
                                    markerRadius = uiState.markerRadius * density,
                                    markerColor = uiState.markerColor,
                                    trackWidth = uiState.trackWidth * density,
                                    trackColor = uiState.trackColor,
                                    blackBorderWidth = MapRenderer.BLACK_BORDER_WIDTH * density,
                                    wptVisibilityMode = uiState.wptVisibilityMode,
                                    wptAlphaMap = uiState.wptAlphaMap,
                                    startWpt = uiState.startWpt,
                                    endWpt = uiState.endWpt,
                                    adminDivisionConfig = uiState.adminDivisionConfig
                                )
                            }
                        }
                    }

                    // LAYER B: Synchronized Bottom 1/5 Elevation Profile Overlay (Toggleable)
                    if (uiState.showElevationProfile) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.20f) // Strictly 20% (bottom 1/5) of the 16:9 frame
                        ) {
                            ElevationProfileView(
                                track = currentTrack,
                                currentProgress = currentProgress,
                                interpolatedPoint = currentInterpolated,
                                onScrub = { newProg ->
                                    viewModel.seekTo(newProg)
                                }
                            )
                        }
                    }

                    // LAYER C: Top-Left Route Title & Subtitle Badge [需求: Title 可選字型大小, 於標題下方顯示一級行政區名稱]
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC0F172A))
                            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                            .clickable { showEditTitleDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "修改影片標題",
                            tint = CyanNeon,
                            modifier = Modifier.size((uiState.titleTextSize * 0.55f).coerceIn(16f, 32f).dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uiState.videoTitle,
                                    color = TextPrimary,
                                    fontSize = uiState.titleTextSize.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.4f, %.4f",
                                        currentInterpolated.lat,
                                        currentInterpolated.lon
                                    ),
                                    color = TextMuted,
                                    fontSize = (uiState.titleTextSize * 0.32f).coerceIn(10f, 15f).sp
                                )
                            }
                            if (uiState.showAdminDivisionSubtitle && uiState.currentAdminDivision.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = uiState.currentAdminDivision,
                                    color = CyanNeon,
                                    fontSize = (uiState.titleTextSize * 0.8f).sp, // 80% of main Title's font size
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // LAYER D: Interactive North Compass Rose Badge (Top-Right)
                    // Tap to reset to North (0°), Drag horizontally to smoothly rotate the map
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC0F172A))
                            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    viewModel.rotateMapBy(dragAmount.x * 0.75f)
                                }
                            }
                            .clickable { viewModel.resetMapRotation() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Canvas(modifier = Modifier.size(22.dp)) {
                                val r = size.minDimension / 2f
                                drawIntoCanvas { canvas ->
                                    mapRenderer.drawNorthCompass(
                                        canvas = canvas.nativeCanvas,
                                        cx = r,
                                        cy = r,
                                        rotationDegrees = uiState.mapRotation,
                                        radius = r - 1f
                                    )
                                }
                            }
                            Text(
                                text = if (uiState.mapRotation.roundToInt() == 0) "正北" else "${uiState.mapRotation.roundToInt()}°",
                                color = if (uiState.mapRotation.roundToInt() == 0) CyanNeon else AmberAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // LAYER E: Floating Zoom & Reset View Controls (+ / - / Reset View)
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .background(Color(0xCC0F172A), RoundedCornerShape(6.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(6.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = { viewModel.adjustZoom(0.5) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "放大地圖",
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        HorizontalDivider(color = GlassBorder, modifier = Modifier.width(18.dp))
                        IconButton(
                            onClick = { viewModel.adjustZoom(-0.5) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "縮小地圖",
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        HorizontalDivider(color = GlassBorder, modifier = Modifier.width(18.dp))
                        IconButton(
                            onClick = { viewModel.rotateMapBy(45f) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "順時針旋轉 45°",
                                tint = if (uiState.mapRotation != 0f) AmberAccent else TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        HorizontalDivider(color = GlassBorder, modifier = Modifier.width(18.dp))
                        IconButton(
                            onClick = { viewModel.resetView() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterCenterFocus,
                                contentDescription = "視角重設",
                                tint = if (uiState.zoomOffset != 0.0 || uiState.mapRotation != 0f || uiState.panOffsetX != 0f || uiState.panOffsetY != 0f) CyanNeon else TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    // Loading or Empty Placeholder inside 16:9 container
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(color = CyanPrimary)
                        } else {
                            Text(
                                text = "請點選上方「開啟 GPX」或「載入示範路徑」",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 2. MAP STYLE SELECTION BAR
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "圖資:", color = TextMuted, fontSize = 11.sp)
                FilterChip(
                    selected = uiState.mapStyle == MapStyle.OPEN_STREET_MAP,
                    onClick = { viewModel.setMapStyle(MapStyle.OPEN_STREET_MAP) },
                    label = { Text("預設", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanDark,
                        selectedLabelColor = CyanNeon
                    )
                )
                FilterChip(
                    selected = uiState.mapStyle == MapStyle.OPEN_TOPO_MAP,
                    onClick = { viewModel.setMapStyle(MapStyle.OPEN_TOPO_MAP) },
                    label = { Text("等高線", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanDark,
                        selectedLabelColor = CyanNeon
                    )
                )
                FilterChip(
                    selected = uiState.showWaypointLabels,
                    onClick = { viewModel.toggleWaypointLabels() },
                    leadingIcon = {
                        Icon(
                            imageVector = if (uiState.showWaypointLabels) Icons.AutoMirrored.Filled.Label else Icons.AutoMirrored.Filled.LabelOff,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (uiState.showWaypointLabels) CyanNeon else TextMuted
                        )
                    },
                    label = { Text("航點標籤", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanDark,
                        selectedLabelColor = CyanNeon
                    )
                )
            }

            // =========================================================================
            // 2.5 VISUAL CUSTOMIZATION OPTIONS (WPT字體、前進圓點、路徑樣式)
            // =========================================================================
            VisualCustomizationCard(
                uiState = uiState,
                onSetWptTextSize = { viewModel.setWptLabelTextSize(it) },
                onSetWptPauseDuration = { viewModel.setWptPauseDuration(it) },
                onSetWptVisibilityMode = { viewModel.setWptVisibilityMode(it) },
                onSetShowAdminDivisionSubtitle = { viewModel.setShowAdminDivisionSubtitle(it) },
                onSetMarkerRadius = { viewModel.setMarkerRadius(it) },
                onSetMarkerColor = { viewModel.setMarkerColor(it) },
                onSetTrackWidth = { viewModel.setTrackWidth(it) },
                onSetTrackColor = { viewModel.setTrackColor(it) },
                onSetTitleTextSize = { viewModel.setTitleTextSize(it) },
                onSetAdminDivisionConfig = { viewModel.setAdminDivisionConfig(it) }
            )

            // =========================================================================
            // 3. PLAYBACK & SCRUBBING CONTROLS
            // =========================================================================
            PlaybackControlBar(
                uiState = uiState,
                onTogglePlay = { viewModel.togglePlayPause() },
                onSeek = { viewModel.seekTo(it) },
                onSelectSpeed = { viewModel.setSpeedMultiplier(it) },
                onToggleElevation = { viewModel.toggleElevationProfile() }
            )

            // =========================================================================
            // 4. TWO-POINT TIME RANGE SELECTOR SLIDER
            // =========================================================================
            if (uiState.fullTrack != null) {
                TimeRangeSelectorCard(
                    uiState = uiState,
                    onRangeChange = { range, isStart, isEnd ->
                        viewModel.setTimeRange(range, focusStart = isStart, focusEnd = isEnd)
                    },
                    onResetRange = { viewModel.resetTimeRange() },
                    onSetStartFromWaypoint = { viewModel.setStartFromWaypoint(it) },
                    onSetEndFromWaypoint = { viewModel.setEndFromWaypoint(it) }
                )
            }

            // =========================================================================
            // 5. ACTION BAR (EXPORT MP4 VIDEO)
            // =========================================================================
            Button(
                onClick = { showExportSettingsDialog = true },
                enabled = uiState.track != null && uiState.exportState is ExportState.Idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.MovieCreation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "匯出 16:9 MP4 影片",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // =========================================================================
            // 6. ROUTE TELEMETRY & STATS SUMMARY
            // =========================================================================
            uiState.track?.let { track ->
                RouteStatsCard(track = track, interpolated = uiState.interpolatedPoint)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // =========================================================================
    // EXPORT SETTINGS DIALOG
    // =========================================================================
    if (showExportSettingsDialog) {
        ExportSettingsDialog(
            uiState = uiState,
            onDismiss = { showExportSettingsDialog = false },
            onConfirmExport = { config ->
                showExportSettingsDialog = false
                viewModel.setVideoTitle(config.videoTitle)
                viewModel.exportVideo(config)
            }
        )
    }

    // =========================================================================
    // EDIT TITLE DIALOG [需求 4: 在影片左上角顯示 title, 預設為檔名, 可修改]
    // =========================================================================
    if (showEditTitleDialog) {
        var titleInput by remember(uiState.videoTitle) { mutableStateOf(uiState.videoTitle) }
        AlertDialog(
            onDismissRequest = { showEditTitleDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = CyanNeon,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "修改影片標題 (Title)",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "此標題將顯示於預覽與匯出影片左上角，預設為 GPX 檔案名稱：",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "標題字型大小：",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf(
                            Pair("小 (24)", 24f),
                            Pair("標準 (32)", 32f),
                            Pair("大 (40)", 40f),
                            Pair("特大 (48)", 48f)
                        ).forEach { (label, size) ->
                            val isSelected = kotlin.math.abs(uiState.titleTextSize - size) < 1f
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setTitleTextSize(size) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanDark,
                                    selectedLabelColor = CyanNeon
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = uiState.showAdminDivisionSubtitle,
                            onCheckedChange = { viewModel.setShowAdminDivisionSubtitle(it) },
                            colors = CheckboxDefaults.colors(checkedColor = CyanNeon, checkmarkColor = Color.Black)
                        )
                        Text(
                            text = "於標題下方顯示一級行政區名稱",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            viewModel.setVideoTitle(titleInput.trim())
                        }
                        showEditTitleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
                ) {
                    Text("確定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitleDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }

    // =========================================================================
    // GPX LOADING ERROR ALERT DIALOG
    // =========================================================================
    uiState.errorMessage?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissErrorMessage() },
            icon = {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = RoseAccent,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "開啟 GPX 失敗",
                    color = RoseAccent,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = errorMsg,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissErrorMessage() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
                ) {
                    Text("確定")
                }
            },
            containerColor = SurfaceDark
        )
    }

    // =========================================================================
    // EXPORT PROGRESS & COMPLETION DIALOG
    // =========================================================================
    when (val exportState = uiState.exportState) {
        is ExportState.Exporting -> {
            ExportProgressDialog(
                progress = exportState.progress,
                currentFrame = exportState.currentFrame,
                totalFrames = exportState.totalFrames
            )
        }
        is ExportState.Success -> {
            ExportSuccessDialog(
                videoUri = exportState.videoUri,
                onDismiss = { viewModel.dismissExportDialog() },
                onOpenVideo = { uri ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    viewModel.dismissExportDialog()
                }
            )
        }
        is ExportState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissExportDialog() },
                title = { Text(text = "匯出失敗", color = RoseAccent) },
                text = { Text(text = exportState.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissExportDialog() }) {
                        Text(text = "確定")
                    }
                }
            )
        }
        ExportState.Idle -> {}
    }
}

/**
 * Playback Control Bar with Play/Pause, Scrubber, Speed Multipliers, and Elevation Toggle.
 */
@Composable
private fun PlaybackControlBar(
    uiState: MapUiState,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleElevation: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Distance & Progress Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val currentDist = uiState.interpolatedPoint?.cumulativeDistanceKm ?: 0.0
                val totalDist = uiState.track?.totalDistanceKm ?: 0.0

                Text(
                    text = String.format(Locale.US, "%.1f km", currentDist),
                    color = CyanNeon,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = String.format(Locale.US, "總計 %.1f km", totalDist),
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Slider(
                value = uiState.progress,
                onValueChange = onSeek,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = CyanNeon,
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = DividerColor
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Play/Pause, Speed Chips, and Elevation Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Play / Pause Circle Button
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(42.dp)
                        .background(CyanPrimary, CircleShape)
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "暫停" else "播放",
                        tint = Color.Black,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Speed Selector Chips (1x, 2x, 5x, 10x)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val speeds = listOf(1.0f, 2.0f, 5.0f, 10.0f)
                    speeds.forEach { spd ->
                        val isSelected = uiState.speedMultiplier == spd
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) CyanPrimary else SurfaceDark)
                                .clickable { onSelectSpeed(spd) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "${spd.toInt()}x",
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Toggle Switch: Show Elevation Profile (顯示高度圖)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "高度圖",
                        color = if (uiState.showElevationProfile) CyanNeon else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = uiState.showElevationProfile,
                        onCheckedChange = { onToggleElevation() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanNeon,
                            checkedTrackColor = CyanDark,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = SurfaceDark
                        ),
                        modifier = Modifier.height(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * Detailed telemetry card displaying total ascent, descent, min/max altitude, and duration.
 */
@Composable
private fun RouteStatsCard(track: GpxTrack, interpolated: InterpolatedPoint?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "路徑數據統計",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(label = "總爬升", value = "+${track.totalAscent.roundToInt()} m", tint = EmeraldAccent)
                StatItem(label = "總下降", value = "-${track.totalDescent.roundToInt()} m", tint = AmberAccent)
                StatItem(label = "最高海拔", value = "${track.maxElevation.roundToInt()} m", tint = CyanNeon)
                StatItem(label = "最低海拔", value = "${track.minElevation.roundToInt()} m", tint = TextSecondary)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Export Settings Modal for customizing resolution and duration.
 */
/**
 * Export Settings Modal for customizing resolution, duration, and title.
 */
@Composable
private fun ExportSettingsDialog(
    uiState: MapUiState,
    onDismiss: () -> Unit,
    onConfirmExport: (VideoEncoder.ExportConfig) -> Unit
) {
    var titleInput by remember(uiState.videoTitle) { mutableStateOf(uiState.videoTitle) }
    var selectedResolution by remember { mutableStateOf("1080p") }
    var selectedFps by remember { mutableStateOf(30) }
    var selectedDuration by remember { mutableStateOf(15) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "匯出 16:9 MP4 影片設定",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // [匯出影片時顯示 1: 可編輯標題]
                Text(text = "影片標題 (左上角):", color = TextSecondary, fontSize = 12.sp)
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Resolution Selector
                Text(text = "影片解析度 (16:9):", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1080p (1920x1080)", "720p (1280x720)").forEach { res ->
                        val key = if (res.startsWith("1080p")) "1080p" else "720p"
                        val isSelected = selectedResolution == key
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedResolution = key },
                            label = { Text(res, fontSize = 11.sp) }
                        )
                    }
                }

                // Framerate
                Text(text = "幀率 (FPS):", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { fps ->
                        FilterChip(
                            selected = selectedFps == fps,
                            onClick = { selectedFps = fps },
                            label = { Text("$fps fps", fontSize = 11.sp) }
                        )
                    }
                }

                // [匯出影片時顯示 2: 影片長度選項 15秒, 25秒, 40秒]
                Text(text = "影片長度 (秒):", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 25, 40).forEach { sec ->
                        FilterChip(
                            selected = selectedDuration == sec,
                            onClick = { selectedDuration = sec },
                            label = { Text("${sec}秒", fontSize = 11.sp) }
                        )
                    }
                }

                // Export parameter overview
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDarkElevated, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "• 地圖圖資: ${uiState.mapStyle.displayName}",
                        color = TextPrimary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• 地圖視角: 旋轉 ${uiState.mapRotation.roundToInt()}°, 縮放 ${String.format(Locale.US, "%.1fx", 2.0.pow(uiState.zoomOffset))}",
                        color = CyanNeon,
                        fontSize = 11.sp
                    )
                    uiState.track?.let { trk ->
                        Text(
                            text = "• 匯出區間: 距離 ${String.format(Locale.US, "%.1f km", trk.totalDistanceKm)}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "• 影片標題 (左上角): ${titleInput.trim().ifEmpty { uiState.videoTitle }} (字體 ${uiState.titleTextSize.toInt()}sp)",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val wptPauseStr = if (uiState.wptPauseDurationSec <= 0.05f) "不停留 (連續移動)" else "每個WPT停留 ${uiState.wptPauseDurationSec.toInt()}秒"
                    Text(
                        text = "• 影片時長: ${selectedDuration}秒 (漸近開始 2秒, 漸近結束 2秒, $wptPauseStr)",
                        color = CyanNeon,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• 航點停留與顯示: 停留 ${uiState.wptPauseDurationSec.toInt()}秒 / ${uiState.wptVisibilityMode.displayName}",
                        color = CyanNeon,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• 樣式設定: WPT字體 ${uiState.wptLabelTextSize.toInt()}sp, 圓點 ${uiState.markerRadius.toInt()}dp, 路徑 ${uiState.trackWidth.toInt()}dp",
                        color = CyanNeon,
                        fontSize = 11.sp
                    )
                    Text(
                        text = if (uiState.showAdminDivisionSubtitle) "✓ 包含標題下方一級行政區名稱副標題 (80%字體)" else "• 未顯示行政區副標題",
                        color = if (uiState.showAdminDivisionSubtitle) EmeraldAccent else TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = if (uiState.showElevationProfile) "✓ 包含底部 1/5 同步高度圖與指北針" else "✗ 未勾選高度圖",
                        color = if (uiState.showElevationProfile) EmeraldAccent else AmberAccent,
                        fontSize = 11.sp
                    )
                    Text(
                        text = if (uiState.showWaypointLabels) "✓ 包含航點名稱標籤" else "• 僅標註航點紅點 (隱藏名稱標籤)",
                        color = if (uiState.showWaypointLabels) EmeraldAccent else TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val is1080 = selectedResolution == "1080p"
                    val effectiveTitle = titleInput.trim().ifEmpty { uiState.videoTitle }
                    val config = VideoEncoder.ExportConfig(
                        width = if (is1080) 1920 else 1280,
                        height = if (is1080) 1080 else 720,
                        fps = selectedFps,
                        durationSeconds = selectedDuration,
                        bitrate = if (is1080) 8_000_000 else 4_000_000,
                        showElevationProfile = uiState.showElevationProfile,
                        showWaypointLabels = uiState.showWaypointLabels,
                        zoomOffset = uiState.zoomOffset,
                        rotationDegrees = uiState.mapRotation,
                        mapStyle = uiState.mapStyle,
                        panOffsetX = uiState.panOffsetX,
                        panOffsetY = uiState.panOffsetY,
                        videoTitle = effectiveTitle,
                        titleTextSize = uiState.titleTextSize,
                        wptLabelTextSize = uiState.wptLabelTextSize,
                        markerRadius = uiState.markerRadius,
                        markerColor = uiState.markerColor,
                        trackWidth = uiState.trackWidth,
                        trackColor = uiState.trackColor,
                        uiViewportWidth = uiState.uiViewportWidth,
                        uiViewportHeight = uiState.uiViewportHeight,
                        uiDensity = uiState.uiDensity,
                        wptPauseDurationSec = uiState.wptPauseDurationSec,
                        wptVisibilityMode = uiState.wptVisibilityMode,
                        showAdminDivisionSubtitle = uiState.showAdminDivisionSubtitle
                    )
                    onConfirmExport(config)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
            ) {
                Text(text = "開始匯出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = TextSecondary)
            }
        },
        containerColor = SurfaceDark
    )
}

/**
 * Interactive two-point RangeSlider card for trimming GPX route to a custom time/distance range.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeSelectorCard(
    uiState: MapUiState,
    onRangeChange: (ClosedFloatingPointRange<Float>, Boolean, Boolean) -> Unit,
    onResetRange: () -> Unit,
    onSetStartFromWaypoint: (GpxWaypoint) -> Unit,
    onSetEndFromWaypoint: (GpxWaypoint) -> Unit
) {
    val fullTrack = uiState.fullTrack ?: return
    val activeTrack = uiState.track ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "時間區間剪裁 (Range Slicer)",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val isPartial = uiState.selectedRange.start > 0.001f || uiState.selectedRange.endInclusive < 0.999f
                if (isPartial) {
                    TextButton(
                        onClick = onResetRange,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("重設全路徑 (100%)", color = CyanNeon, fontSize = 11.sp)
                    }
                } else {
                    Text("全路徑", color = TextMuted, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Time & Distance Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val startStr = activeTrack.startTime?.let { timeFormat.format(java.util.Date(it)) } ?: "起點"
                val endStr = activeTrack.endTime?.let { timeFormat.format(java.util.Date(it)) } ?: "終點"

                Text(
                    text = "起點: $startStr",
                    color = EmeraldAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = String.format(Locale.US, "已選 %.1f km / 全程 %.1f km", activeTrack.totalDistanceKm, fullTrack.totalDistanceKm),
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Text(
                    text = "終點: $endStr",
                    color = RoseAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Two-point RangeSlider (Material 3) with distinct Start (Green) & End (Red) Thumbs
            RangeSlider(
                value = uiState.selectedRange,
                onValueChange = { newRange ->
                    val oldRange = uiState.selectedRange
                    val leftDiff = abs(newRange.start - oldRange.start)
                    val rightDiff = abs(newRange.endInclusive - oldRange.endInclusive)
                    val isStartAdjusted = leftDiff >= rightDiff && leftDiff > 0.00001f
                    val isEndAdjusted = rightDiff > leftDiff && rightDiff > 0.00001f
                    // Ensure left thumb cannot cross or equal right thumb (minimum 0.002f gap)
                    val clampedStart = newRange.start.coerceIn(0f, 0.998f)
                    val clampedEnd = newRange.endInclusive.coerceIn(clampedStart + 0.002f, 1f)
                    onRangeChange(clampedStart..clampedEnd, isStartAdjusted, isEndAdjusted)
                },
                valueRange = 0f..1f,
                startThumb = {
                    // Emerald Green thumb with crisp white ring for Start Point (起點)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(EmeraldAccent)
                            .border(2.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                },
                endThumb = {
                    // Rose Red thumb with crisp white ring for Destination End Point (迄點)
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(RoseAccent)
                            .border(2.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                },
                colors = SliderDefaults.colors(
                    activeTrackColor = CyanPrimary,
                    inactiveTrackColor = DividerColor
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // [操作問題 1: 可以用 WPT 選擇起迄點，依距離起點由近到遠升冪排序]
            if (fullTrack.waypoints.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val sortedWaypoints = remember(fullTrack) {
                    fullTrack.waypoints.map { wpt ->
                        wpt to fullTrack.findClosestDistanceKm(wpt.lat, wpt.lon)
                    }.sortedBy { it.second }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var showStartMenu by remember { mutableStateOf(false) }
                    var showEndMenu by remember { mutableStateOf(false) }

                    // 起點 WPT Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showStartMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldAccent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = EmeraldAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "起點 WPT ▼",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        DropdownMenu(
                            expanded = showStartMenu,
                            onDismissRequest = { showStartMenu = false },
                            modifier = Modifier.background(SurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("原路徑起點 (0.0 km)", color = TextPrimary, fontSize = 12.sp) },
                                onClick = {
                                    showStartMenu = false
                                    onRangeChange(0.0f..uiState.selectedRange.endInclusive, true, false)
                                }
                            )
                            sortedWaypoints.forEach { (wpt, distKm) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(wpt.name ?: "未命名航點", color = EmeraldAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(String.format(Locale.US, "%.1f km · %.0f m", distKm, wpt.elevation ?: 0.0), color = TextMuted, fontSize = 10.sp)
                                        }
                                    },
                                    onClick = {
                                        showStartMenu = false
                                        onSetStartFromWaypoint(wpt)
                                    }
                                )
                            }
                        }
                    }

                    // 迄點 WPT Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { showEndMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseAccent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = RoseAccent
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "迄點 WPT ▼",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        DropdownMenu(
                            expanded = showEndMenu,
                            onDismissRequest = { showEndMenu = false },
                            modifier = Modifier.background(SurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("原路徑終點 (${String.format(Locale.US, "%.1f km", fullTrack.totalDistanceKm)})", color = TextPrimary, fontSize = 12.sp) },
                                onClick = {
                                    showEndMenu = false
                                    onRangeChange(uiState.selectedRange.start..1.0f, false, true)
                                }
                            )
                            sortedWaypoints.forEach { (wpt, distKm) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(wpt.name ?: "未命名航點", color = RoseAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(String.format(Locale.US, "%.1f km · %.0f m", distKm, wpt.elevation ?: 0.0), color = TextMuted, fontSize = 10.sp)
                                        }
                                    },
                                    onClick = {
                                        showEndMenu = false
                                        onSetEndFromWaypoint(wpt)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exporting Progress Modal.
 */
@Composable
private fun ExportProgressDialog(progress: Float, currentFrame: Int, totalFrames: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "正在匯出 16:9 MP4 影片...",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = CyanNeon,
                    trackColor = SurfaceDarkElevated
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "幀數: $currentFrame / $totalFrames",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${(progress * 100).roundToInt()}%",
                        color = CyanNeon,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {},
        containerColor = SurfaceDark
    )
}

/**
 * Export Completed Dialog with Direct Action to Open Video in Android Video Player.
 */
@Composable
private fun ExportSuccessDialog(
    videoUri: Uri,
    onDismiss: () -> Unit,
    onOpenVideo: (Uri) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = EmeraldAccent,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "影片匯出成功！",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Text(
                text = "已將 16:9 高畫質 MP4 影片儲存至相簿（Movies/GPXAmi）。",
                color = TextSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = { onOpenVideo(videoUri) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
            ) {
                Text(text = "播放影片")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "關閉", color = TextSecondary)
            }
        },
        containerColor = SurfaceDark
    )
}

/**
 * Visual styling options card for:
 * [需求 1] 提供調整WPT label 字型大小的選項
 * [需求 2] 提供前進圓點大小及顏色選項
 * [需求 3] 提供路徑的粗細及顏色的選項
 */
@Composable
private fun VisualCustomizationCard(
    uiState: MapUiState,
    onSetWptTextSize: (Float) -> Unit,
    onSetWptPauseDuration: (Float) -> Unit,
    onSetWptVisibilityMode: (WptVisibilityMode) -> Unit,
    onSetShowAdminDivisionSubtitle: (Boolean) -> Unit,
    onSetMarkerRadius: (Float) -> Unit,
    onSetMarkerColor: (Int) -> Unit,
    onSetTrackWidth: (Float) -> Unit,
    onSetTrackColor: (Int) -> Unit,
    onSetTitleTextSize: (Float) -> Unit,
    onSetAdminDivisionConfig: (AdminDivisionConfig) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val markerColors = listOf(
        Pair("青色", android.graphics.Color.rgb(6, 182, 212)),
        Pair("亮紅", android.graphics.Color.rgb(239, 68, 68)),
        Pair("琥珀", android.graphics.Color.rgb(245, 158, 11)),
        Pair("翡翠", android.graphics.Color.rgb(16, 185, 129)),
        Pair("霓虹紫", android.graphics.Color.rgb(168, 85, 247)),
        Pair("純白", android.graphics.Color.rgb(255, 255, 255))
    )

    val trackColors = listOf(
        Pair("霓虹青", android.graphics.Color.rgb(0, 242, 254)),
        Pair("烈焰橘", android.graphics.Color.rgb(255, 107, 0)),
        Pair("鮮紅", android.graphics.Color.rgb(239, 68, 68)),
        Pair("螢光黃", android.graphics.Color.rgb(250, 204, 21)),
        Pair("翠綠", android.graphics.Color.rgb(16, 185, 129)),
        Pair("桃紅", android.graphics.Color.rgb(244, 63, 94))
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "樣式與動畫選項 (WPT停留/顯示、行政區、樣式)",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isExpanded) "收合" else "展開設定",
                        color = CyanNeon,
                        fontSize = 11.sp
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. WPT 停留秒數 (0–3 秒)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1. WPT 航點停留時間:",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (uiState.wptPauseDurationSec <= 0.05f) "不停留 (0秒)" else "${uiState.wptPauseDurationSec.toInt()} 秒",
                                color = CyanNeon,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(
                                Pair("0秒 (不停留)", 0f),
                                Pair("1秒", 1f),
                                Pair("2秒 (預設)", 2f),
                                Pair("3秒", 3f)
                            ).forEach { (label, sec) ->
                                val isSelected = kotlin.math.abs(uiState.wptPauseDurationSec - sec) < 0.1f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetWptPauseDuration(sec) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }

                        Slider(
                            value = uiState.wptPauseDurationSec,
                            onValueChange = { onSetWptPauseDuration(it.roundToInt().toFloat()) },
                            valueRange = 0f..3f,
                            steps = 2,
                            colors = SliderDefaults.colors(
                                thumbColor = CyanNeon,
                                activeTrackColor = CyanPrimary,
                                inactiveTrackColor = DividerColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = if (uiState.wptPauseDurationSec <= 0.05f)
                                "• 0秒：不於航點停留，沿路徑持續連續移動 (預覽與匯出皆生效)"
                            else
                                "• 於每個航點停留 ${uiState.wptPauseDurationSec.toInt()} 秒 (匯出時每個航點渲染 ${uiState.wptPauseDurationSec.toInt()} × FPS 幀)",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 2. WPT 顯示模式 (Visibility Mode & 淡入淡出)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "2. WPT 航點顯示模式 (淡入/淡出動畫):",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            WptVisibilityMode.values().forEach { mode ->
                                val isSelected = uiState.wptVisibilityMode == mode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetWptVisibilityMode(mode) },
                                    label = { Text(mode.displayName, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }

                        val modeHelpText = when (uiState.wptVisibilityMode) {
                            WptVisibilityMode.ALWAYS_SHOW ->
                                "• 永遠顯示：所有航點標記在整個動畫與匯出影片中始終保持 100% 可見度。"
                            WptVisibilityMode.ONLY_DURING_PAUSE ->
                                if (uiState.wptPauseDurationSec <= 0.05f)
                                    "• 停留期間顯示 (停留為0秒)：平時隱藏航點，當前進圓點行經航點時自動觸發平滑淡入與淡出過渡效果。"
                                else
                                    "• 停留期間顯示：平時隱藏航點，到達航點時平滑淡入 (0→1)，停留期間完全顯示，離開前平滑淡出 (1→0)。"
                        }
                        Text(
                            text = modeHelpText,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 3. 標題第二行：顯示行政區名稱 (一級行政區 vs 一級行政區 + 二級行政區)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "3. 於標題第二行顯示行政區名稱",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "字體為標題的 80% (${(uiState.titleTextSize * 0.8f).toInt()}sp) · 依國際規則自動反向解析",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = uiState.showAdminDivisionSubtitle,
                                onCheckedChange = onSetShowAdminDivisionSubtitle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyanNeon,
                                    checkedTrackColor = CyanDark,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceDark
                                ),
                                modifier = Modifier.height(26.dp)
                            )
                        }

                        if (uiState.showAdminDivisionSubtitle) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "標題第二行行政區格式:",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = uiState.adminDivisionConfig.displayName,
                                    color = CyanNeon,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                AdminDivisionConfig.values().forEach { config ->
                                    val isSelected = uiState.adminDivisionConfig == config
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onSetAdminDivisionConfig(config) },
                                        label = { Text(config.displayName, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CyanDark,
                                            selectedLabelColor = CyanNeon
                                        )
                                    )
                                }
                            }

                            val displayAdmin = if (uiState.currentAdminDivision.isNotEmpty())
                                uiState.currentAdminDivision
                            else
                                "定位中 / 載入中..."
                            Text(
                                text = "• 目前標題第二行預覽: $displayAdmin",
                                color = CyanNeon,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 4. [需求 1] WPT label 字型大小選項
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "4. WPT 航點標籤字型大小:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(
                                Pair("小 (14)", 14f),
                                Pair("標準 (20)", 20f),
                                Pair("大 (26)", 26f),
                                Pair("特大 (32)", 32f)
                            ).forEach { (label, size) ->
                                val isSelected = kotlin.math.abs(uiState.wptLabelTextSize - size) < 1f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetWptTextSize(size) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 5. [需求 2] 前進圓點大小及顏色選項
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "5. 前進圓點大小及顏色:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 大小
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "大小:", color = TextMuted, fontSize = 11.sp)
                            listOf(
                                Pair("小 (8)", 8f),
                                Pair("標準 (12)", 12f),
                                Pair("大 (16)", 16f),
                                Pair("特大 (22)", 22f)
                            ).forEach { (label, r) ->
                                val isSelected = kotlin.math.abs(uiState.markerRadius - r) < 1f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetMarkerRadius(r) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }

                        // 顏色
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(text = "顏色:", color = TextMuted, fontSize = 11.sp)
                            markerColors.forEach { (name, colorInt) ->
                                val isSelected = uiState.markerColor == colorInt
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) Color.White else GlassBorder,
                                            shape = CircleShape
                                        )
                                        .clickable { onSetMarkerColor(colorInt) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = name,
                                            tint = if (colorInt == android.graphics.Color.WHITE) Color.Black else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 6. [需求 3] 路徑的粗細及顏色選項
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "6. 路徑粗細及顏色:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 粗細
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "粗細:", color = TextMuted, fontSize = 11.sp)
                            listOf(
                                Pair("細 (3)", 3f),
                                Pair("標準 (5)", 5f),
                                Pair("粗 (8)", 8f),
                                Pair("特粗 (12)", 12f)
                            ).forEach { (label, w) ->
                                val isSelected = kotlin.math.abs(uiState.trackWidth - w) < 0.5f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetTrackWidth(w) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }

                        // 顏色
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text(text = "顏色:", color = TextMuted, fontSize = 11.sp)
                            trackColors.forEach { (name, colorInt) ->
                                val isSelected = uiState.trackColor == colorInt
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt))
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) Color.White else GlassBorder,
                                            shape = CircleShape
                                        )
                                        .clickable { onSetTrackColor(colorInt) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = name,
                                            tint = if (colorInt == android.graphics.Color.WHITE) Color.Black else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder)

                    // 7. [需求: Title 可選字型大小]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "7. 影片標題 (Title) 字型大小:",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(
                                Pair("小 (24)", 24f),
                                Pair("標準 (32)", 32f),
                                Pair("大 (40)", 40f),
                                Pair("特大 (48)", 48f)
                            ).forEach { (label, size) ->
                                val isSelected = kotlin.math.abs(uiState.titleTextSize - size) < 1f
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetTitleTextSize(size) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanDark,
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
