package com.gpxami.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpxami.app.data.geocoding.AdminDivisionResolver
import com.gpxami.app.data.model.AdminDivisionConfig
import com.gpxami.app.data.model.GpxTrack
import com.gpxami.app.data.model.InterpolatedPoint
import com.gpxami.app.data.model.Wpt
import com.gpxami.app.data.model.WptRole
import com.gpxami.app.data.model.WptVisibilityMode
import com.gpxami.app.data.parser.GPXParser
import com.gpxami.app.data.preferences.UserPreferencesRepository
import com.gpxami.app.data.preferences.UserPreferencesRepositoryImpl
import com.gpxami.app.data.repository.GeocodingRepository
import com.gpxami.app.data.repository.GeocodingRepositoryImpl
import com.gpxami.app.export.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

sealed interface ExportState {
    object Idle : ExportState
    data class Exporting(val progress: Float, val currentFrame: Int, val totalFrames: Int) : ExportState
    data class Success(val videoUri: Uri) : ExportState
    data class Error(val message: String) : ExportState
}

data class MapUiState(
    val fullTrack: GpxTrack? = null,
    val track: GpxTrack? = null,
    val selectedRange: ClosedFloatingPointRange<Float> = 0f..1f,
    val progress: Float = 0.0f,
    val interpolatedPoint: InterpolatedPoint? = null,
    val isPlaying: Boolean = false,
    val speedMultiplier: Float = 2.0f, // Default 2x speed for exciting preview
    val showElevationProfile: Boolean = true, // Default enabled (bottom 1/5 overlay)
    val showWaypointLabels: Boolean = true, // Toggle display of waypoint text labels (default enabled)
    val mapStyle: com.gpxami.app.map.MapStyle = com.gpxami.app.map.MapStyle.OPEN_STREET_MAP, // OpenStreetMap default
    val mapRotation: Float = 0f, // Rotation in degrees (0 = North-up)
    val zoomOffset: Double = 0.0, // Zoom offset from optimal
    val cameraZoomTransitionOffset: Double = 0.0, // Dynamic transition offset (e.g. -0.8 during route completion zoom-out)
    val panOffsetX: Float = 0f, // User pan offset X in unrotated screen pixels
    val panOffsetY: Float = 0f, // User pan offset Y in unrotated screen pixels
    val focusPoint: Pair<Double, Double>? = null, // Explicit camera focus point (e.g. end point during scrubbing)
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportState: ExportState = ExportState.Idle,
    // [需求 1] WPT label 字型大小 (預設 20f)
    val wptLabelTextSize: Float = 20f,
    // [需求 2] 前進圓點大小 (預設 12f) 及顏色 (預設 #06B6D4)
    val markerRadius: Float = 12f,
    val markerColor: Int = 0xFF06B6D4.toInt(),
    // [需求 3] 路徑粗細 (預設 5f) 及顏色 (預設 #00F2FE)
    val trackWidth: Float = 5.0f,
    val trackColor: Int = 0xFF00F2FE.toInt(),
    // [需求 4] 影片左上角 title (預設為檔名, 可修改)
    val videoTitle: String = "示範路徑",
    // [需求: Title 可選字型大小]
    val titleTextSize: Float = 36f,
    // [需求: 讓輸出影片的地圖範圍與 UI 顯示地圖的範圍一致, WPT, TITLE 文字比例也一致]
    val uiViewportWidth: Float = 0f,
    val uiViewportHeight: Float = 0f,
    val uiDensity: Float = 2.75f,
    // [需求 6] 開啟 GPX 時預設目錄 (上上次的目錄)
    val initialPickerUri: Uri? = null,
    // [新需求 1] WPT 停留時間 (0-3 秒, 預設 2 秒)
    val wptPauseDurationSec: Float = 2.0f,
    // [新需求 2] WPT 顯示模式 (在停留期間才顯示 / 永遠顯示)
    val wptVisibilityMode: WptVisibilityMode = WptVisibilityMode.ALWAYS_SHOW,
    // [新需求 2] 即時 WPT 透明度 Map (WPT index -> alpha)
    val wptAlphaMap: Map<Int, Float> = emptyMap(),
    // [新需求 3] 於標題下方顯示一級行政區名稱
    val showAdminDivisionSubtitle: Boolean = false,
    // [新需求 3] 當前一級行政區名稱
    val currentAdminDivision: String = "",
    // [Start / End WPT Persistent Labels & Administrative Division Config]
    val adminDivisionConfig: AdminDivisionConfig = AdminDivisionConfig.LEVEL_1_ONLY,
    val startWpt: Wpt? = null,
    val endWpt: Wpt? = null,
    val startWptLabel: String = "起點",
    val endWptLabel: String = "迄點"
)

private const val PREFS_NAME = "gpxami_prefs"
private const val KEY_LAST_GPX_URI = "last_gpx_uri"
private const val KEY_LAST_VIDEO_TITLE = "last_video_title"
private const val KEY_FOLDER_HISTORY = "folder_history"
private const val KEY_WPT_LABEL_SIZE = "wpt_label_size"
private const val KEY_MARKER_RADIUS = "marker_radius"
private const val KEY_MARKER_COLOR = "marker_color"
private const val KEY_TRACK_WIDTH = "track_width"
private const val KEY_TRACK_COLOR = "track_color"
private const val KEY_TITLE_TEXT_SIZE = "title_text_size"
private const val KEY_WPT_PAUSE_DURATION = "wpt_pause_duration"
private const val KEY_WPT_VISIBILITY_MODE = "wpt_visibility_mode"
private const val KEY_SHOW_ADMIN_DIVISION_SUBTITLE = "show_admin_division_subtitle"

class MapAnimationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var playbackJob: Job? = null
    private val videoEncoder = VideoEncoder(application)
    private val adminDivisionResolver = AdminDivisionResolver(application)
    private val geocodingRepository: GeocodingRepository = GeocodingRepositoryImpl(application)
    private val userPreferencesRepository: UserPreferencesRepository = UserPreferencesRepositoryImpl(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Load persisted customization settings
        val savedWptSize = prefs.getFloat(KEY_WPT_LABEL_SIZE, 20f)
        val savedMarkerRadius = prefs.getFloat(KEY_MARKER_RADIUS, 12f)
        val savedMarkerColor = prefs.getInt(KEY_MARKER_COLOR, 0xFF06B6D4.toInt())
        val savedTrackWidth = prefs.getFloat(KEY_TRACK_WIDTH, 5.0f)
        val savedTrackColor = prefs.getInt(KEY_TRACK_COLOR, 0xFF00F2FE.toInt())
        val savedTitle = prefs.getString(KEY_LAST_VIDEO_TITLE, "示範路徑") ?: "示範路徑"
        val savedTitleSize = prefs.getFloat(KEY_TITLE_TEXT_SIZE, 36f)
        val savedWptPauseSec = prefs.getFloat(KEY_WPT_PAUSE_DURATION, 2.0f)
        val savedWptVisibilityModeStr = prefs.getString(KEY_WPT_VISIBILITY_MODE, WptVisibilityMode.ALWAYS_SHOW.name)
        val savedWptVisibilityMode = try {
            WptVisibilityMode.valueOf(savedWptVisibilityModeStr ?: WptVisibilityMode.ALWAYS_SHOW.name)
        } catch (_: Exception) {
            WptVisibilityMode.ALWAYS_SHOW
        }
        val savedShowAdminSubtitle = prefs.getBoolean(KEY_SHOW_ADMIN_DIVISION_SUBTITLE, false)
        val savedAdminDivisionConfig = userPreferencesRepository.getAdminDivisionConfig()

        // [需求 6] Determine initialPickerUri from folder history (上上次的目錄)
        val folderHistory = getFolderHistory()
        val initialDirUri = if (folderHistory.size >= 2) {
            Uri.parse(folderHistory[folderHistory.size - 2])
        } else if (folderHistory.isNotEmpty()) {
            Uri.parse(folderHistory.last())
        } else null

        _uiState.update {
            it.copy(
                wptLabelTextSize = savedWptSize,
                markerRadius = savedMarkerRadius,
                markerColor = savedMarkerColor,
                trackWidth = savedTrackWidth,
                trackColor = savedTrackColor,
                videoTitle = savedTitle,
                titleTextSize = savedTitleSize,
                initialPickerUri = initialDirUri,
                wptPauseDurationSec = savedWptPauseSec,
                wptVisibilityMode = savedWptVisibilityMode,
                showAdminDivisionSubtitle = savedShowAdminSubtitle,
                adminDivisionConfig = savedAdminDivisionConfig
            )
        }

        // [需求 5] 開啟 APP 時預設開啟上次的 gpx 檔
        loadInitialTrack()
    }

    /**
     * [需求 5] Loads the last opened GPX file, with fallback to persistent local cache or demo track.
     */
    private fun loadInitialTrack() {
        val lastUriStr = prefs.getString(KEY_LAST_GPX_URI, null)
        val savedTitle = prefs.getString(KEY_LAST_VIDEO_TITLE, null)
        if (lastUriStr != null) {
            val uri = Uri.parse(lastUriStr)
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val app = getApplication<Application>()
                try {
                    app.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                var parseResult = GPXParser.parseFromUri(app.contentResolver, uri)

                // If URI permission expired or file moved, fall back to persistent cached copy
                if (parseResult.isFailure) {
                    val cachedFile = File(app.filesDir, "cached_last_route.gpx")
                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        try {
                            parseResult = GPXParser.parse(cachedFile.inputStream())
                        } catch (_: Exception) {}
                    }
                }

                parseResult.onSuccess { track ->
                    val initialPoint = track.interpolate(0.0f)
                    val effectiveTitle = savedTitle ?: track.name
                    _uiState.update {
                        it.copy(
                            fullTrack = track,
                            track = track,
                            selectedRange = 0f..1f,
                            progress = 0.0f,
                            interpolatedPoint = initialPoint,
                            videoTitle = effectiveTitle,
                            isLoading = false
                        )
                    }
                    onTrackLoaded(track, initialPoint)
                    startPlayback()
                }.onFailure {
                    // Fall back to demo track if last file cannot be opened
                    loadDemoTrack()
                }
            }
        } else {
            loadDemoTrack()
        }
    }

    private fun onTrackLoaded(track: GpxTrack, initialPoint: InterpolatedPoint) {
        updateEndpoints(track)
        if (_uiState.value.showAdminDivisionSubtitle) {
            viewModelScope.launch {
                adminDivisionResolver.preloadForTrack(track)
                val div = adminDivisionResolver.resolve(initialPoint.lat, initialPoint.lon, _uiState.value.adminDivisionConfig)
                _uiState.update { it.copy(currentAdminDivision = div) }
            }
        }
    }

    /**
     * Loads the built-in scenic mountain climb GPX track from assets.
     */
    fun loadDemoTrack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val inputStream = getApplication<Application>().assets.open("demo_route.gpx")
                val result = GPXParser.parse(inputStream)
                result.onSuccess { track ->
                    val initialPoint = track.interpolate(0.0f)
                    _uiState.update {
                        it.copy(
                            fullTrack = track,
                            track = track,
                            selectedRange = 0f..1f,
                            progress = 0.0f,
                            interpolatedPoint = initialPoint,
                            videoTitle = "示範路徑",
                            isLoading = false
                        )
                    }
                    onTrackLoaded(track, initialPoint)
                    startPlayback()
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to parse demo track: ${error.localizedMessage}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error opening demo route: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Loads a user-selected GPX file from Storage Access Framework (SAF).
     * [需求 4] 預設以檔名為 Title
     * [需求 5] 儲存為下次開啟時的預設檔案
     * [需求 6] 記錄資料夾歷程以便開啟上上次目錄
     */
    fun loadGpxFromUri(uri: Uri) {
        viewModelScope.launch {
            pausePlayback()
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val app = getApplication<Application>()
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val rawFileName = queryFileName(app.contentResolver, uri) ?: "GPX Route"
            val fileBaseName = rawFileName.replace(Regex("(?i)\\.gpx$"), "")

            val result = GPXParser.parseFromUri(app.contentResolver, uri)

            result.onSuccess { track ->
                val initialPoint = track.interpolate(0.0f)

                // [需求 5] Persist last URI & Title to SharedPreferences
                prefs.edit()
                    .putString(KEY_LAST_GPX_URI, uri.toString())
                    .putString(KEY_LAST_VIDEO_TITLE, fileBaseName)
                    .apply()

                // Cache file locally to survive system reboots / permission expiry
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        File(app.filesDir, "cached_last_route.gpx").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {}

                // [需求 6] Record folder in directory history (上上次的目錄)
                recordFolderHistory(uri)

                _uiState.update {
                    it.copy(
                        fullTrack = track,
                        track = track,
                        selectedRange = 0f..1f,
                        progress = 0.0f,
                        interpolatedPoint = initialPoint,
                        videoTitle = fileBaseName,
                        isLoading = false,
                        errorMessage = null
                    )
                }
                onTrackLoaded(track, initialPoint)
                startPlayback()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "無法讀取 GPX 檔案: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Starts or resumes route animation playback with 60fps pacing.
     * Uses [CameraViewportHelper] to ensure consistent initial framing between Play and Export:
     * - If start point is within viewport, retains current zoom & pan.
     * - If start point is outside viewport, auto-centers on start point with optimal zoom.
     * - Pauses for 2.0 seconds at each intermediate WPT along the route.
     * - Gracefully zooms out to Fit Bounds of the active route at route completion.
     */
    fun startPlayback() {
        val currentTrack = _uiState.value.track ?: return
        playbackJob?.cancel()

        // 1. Starting playback always initiates from the active track's start point
        val startPt = currentTrack.points.first()
        val initialPoint = currentTrack.interpolate(0.0f)

        // 2. [核心修正]: 透過共用函式判定有效起點是否位於當前地圖可視範圍內
        val vpWidth = if (_uiState.value.uiViewportWidth > 0f) _uiState.value.uiViewportWidth else 1080f
        val vpHeight = if (_uiState.value.uiViewportHeight > 0f) _uiState.value.uiViewportHeight else 607.5f

        val initialCam = com.gpxami.app.map.CameraViewportHelper.determineInitialCamera(
            track = currentTrack,
            viewportWidth = vpWidth,
            viewportHeight = vpHeight,
            currentZoomOffset = _uiState.value.zoomOffset,
            currentPanX = _uiState.value.panOffsetX,
            currentPanY = _uiState.value.panOffsetY,
            mapRotation = _uiState.value.mapRotation,
            showElevationProfile = _uiState.value.showElevationProfile,
            focusPoint = null,
            currentProgress = 0.0f
        )

        if (!initialCam.isStartInViewport) {
            // 起點不在地圖可視範圍內: 自動將地圖中心平移至該起點位置，並自動設定為最適地圖縮放比例 (Optimal Zoom Level)
            _uiState.update {
                it.copy(
                    isPlaying = true,
                    zoomOffset = 0.0,
                    panOffsetX = 0f,
                    panOffsetY = 0f,
                    focusPoint = null,
                    progress = 0.0f,
                    interpolatedPoint = initialPoint,
                    cameraZoomTransitionOffset = 0.0
                )
            }
        } else {
            // 起點在範圍內: 保留使用者當下的地圖視角與縮放比例，從起點開始播放
            _uiState.update {
                it.copy(
                    isPlaying = true,
                    focusPoint = null,
                    progress = 0.0f,
                    interpolatedPoint = initialPoint,
                    cameraZoomTransitionOffset = 0.0
                )
            }
        }

        playbackJob = viewModelScope.launch {
            // Target animation duration: 30 seconds for entire track at 1x speed
            val nominalTrackDuration = 30.0f
            val frameIntervalMs = 16L // ~60 fps

            var lastTime = System.nanoTime()

            // 擷取有效路徑上的中途 WPT (2% 到 98% 區間)
            val wptProgressList = currentTrack.waypoints.mapIndexed { i, wpt ->
                Pair(i, currentTrack.findClosestProgress(wpt.lat, wpt.lon))
            }
            val pauseDurationSec = _uiState.value.wptPauseDurationSec
            val visibilityMode = _uiState.value.wptVisibilityMode

            val wptsOnTrack = wptProgressList
                .map { it.second }
                .filter { it in 0.02f..0.98f }
                .sorted()
            val distinctWpts = mutableListOf<Float>()
            for (wp in wptsOnTrack) {
                if (distinctWpts.none { kotlin.math.abs(it - wp) < 0.015f }) {
                    distinctWpts.add(wp)
                }
            }

            val visitedWpts = mutableSetOf<Float>()
            var currentProgress = 0.0f

            // 連續沿著路徑點行進，不跳躍；每當抵達有效路徑上的 WPT 時精確停留 pauseDurationSec 秒 (若為 0 則不暫停)
            while (isActive && _uiState.value.isPlaying && currentProgress < 1.0f) {
                delay(frameIntervalMs)

                val now = System.nanoTime()
                val deltaSec = (now - lastTime) / 1_000_000_000.0f
                lastTime = now

                val speed = _uiState.value.speedMultiplier
                val step = (deltaSec * speed) / nominalTrackDuration
                val nextProgress = (currentProgress + step).coerceAtMost(1.0f)

                // 檢查是否途經未拜訪的 WPT (當 pauseDurationSec > 0 時才執行暫停停留)
                if (pauseDurationSec > 0f) {
                    val nextWpt = distinctWpts.firstOrNull { it in currentProgress..nextProgress && it !in visitedWpts }
                    if (nextWpt != null) {
                        visitedWpts.add(nextWpt)
                        currentProgress = nextWpt
                        val wptInterp = currentTrack.interpolate(nextWpt)
                        val matchingIndices = wptProgressList.filter { kotlin.math.abs(it.second - nextWpt) < 0.015f }.map { it.first }

                        // [WPT 停留機制]: 鏡頭與畫面保持在該 WPT 停留 pauseDurationSec 秒，平滑淡入與淡出
                        var elapsedPause = 0f
                        val fadeDuration = kotlin.math.min(0.35f, pauseDurationSec / 2f)
                        var pauseLastTime = System.nanoTime()

                        while (isActive && _uiState.value.isPlaying && elapsedPause < pauseDurationSec) {
                            val alpha = when {
                                fadeDuration <= 0f -> 1.0f
                                elapsedPause < fadeDuration -> (elapsedPause / fadeDuration).coerceIn(0f, 1f)
                                elapsedPause > pauseDurationSec - fadeDuration -> ((pauseDurationSec - elapsedPause) / fadeDuration).coerceIn(0f, 1f)
                                else -> 1.0f
                            }
                            val alphaMap = if (visibilityMode == WptVisibilityMode.ONLY_DURING_PAUSE) {
                                matchingIndices.associateWith { alpha }
                            } else {
                                emptyMap()
                            }

                            val curDiv = if (_uiState.value.showAdminDivisionSubtitle) {
                                adminDivisionResolver.resolveSync(wptInterp.lat, wptInterp.lon, _uiState.value.adminDivisionConfig)
                            } else ""

                            _uiState.update {
                                it.copy(
                                    progress = nextWpt,
                                    interpolatedPoint = wptInterp,
                                    focusPoint = null,
                                    cameraZoomTransitionOffset = 0.0,
                                    wptAlphaMap = alphaMap,
                                    currentAdminDivision = if (curDiv.isNotEmpty()) curDiv else it.currentAdminDivision
                                )
                            }

                            delay(frameIntervalMs)
                            val nowP = System.nanoTime()
                            elapsedPause += (nowP - pauseLastTime) / 1_000_000_000.0f
                            pauseLastTime = nowP
                        }

                        _uiState.update { it.copy(wptAlphaMap = emptyMap()) }
                        lastTime = System.nanoTime()
                        continue
                    }
                }

                currentProgress = nextProgress
                val interpolated = currentTrack.interpolate(currentProgress)

                val moveAlphaMap = if (visibilityMode == WptVisibilityMode.ONLY_DURING_PAUSE) {
                    if (pauseDurationSec == 0f) {
                        // Edge Case (Pause Duration = 0s): 經過 WPT 時進行平滑淡入淡出，不阻礙渲染迴圈
                        val map = mutableMapOf<Int, Float>()
                        for ((idx, wptP) in wptProgressList) {
                            val dist = kotlin.math.abs(currentProgress - wptP)
                            val window = 0.012f
                            if (dist < window) {
                                val a = (1.0f - dist / window).coerceIn(0f, 1f)
                                if (a > 0.005f) map[idx] = a
                            }
                        }
                        map
                    } else {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }

                val curDiv = if (_uiState.value.showAdminDivisionSubtitle) {
                    adminDivisionResolver.resolveSync(interpolated.lat, interpolated.lon, _uiState.value.adminDivisionConfig)
                } else ""

                _uiState.update {
                    it.copy(
                        progress = currentProgress,
                        interpolatedPoint = interpolated,
                        focusPoint = null,
                        cameraZoomTransitionOffset = 0.0,
                        wptAlphaMap = moveAlphaMap,
                        currentAdminDivision = if (curDiv.isNotEmpty()) curDiv else it.currentAdminDivision
                    )
                }
            }

            // [片尾全覽縮放 (Fit Bounds)]: 2.0 秒平滑過渡至子路徑 (若有設定新起迄點) 或完整路徑的 Bounding Box 全覽
            if (isActive && _uiState.value.isPlaying && _uiState.value.progress >= 1.0f) {
                val endPt = currentTrack.points.last()
                val centerLat = currentTrack.bounds.centerLat
                val centerLon = currentTrack.bounds.centerLon
                val zoomOutDurationSec = 2.0f
                var elapsedSec = 0f
                var transitionLastTime = System.nanoTime()

                val initialZoomOffset = _uiState.value.zoomOffset
                val initialPanX = _uiState.value.panOffsetX
                val initialPanY = _uiState.value.panOffsetY

                while (isActive && _uiState.value.isPlaying && elapsedSec < zoomOutDurationSec) {
                    delay(frameIntervalMs)
                    val now = System.nanoTime()
                    val deltaSec = (now - transitionLastTime) / 1_000_000_000.0f
                    transitionLastTime = now
                    elapsedSec += deltaSec

                    val t = (elapsedSec / zoomOutDurationSec).coerceIn(0f, 1f)
                    // Cubic ease-in-out
                    val ease = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

                    val curLat = endPt.lat + (centerLat - endPt.lat) * ease
                    val curLon = endPt.lon + (centerLon - endPt.lon) * ease
                    // 縮放從使用者的當前縮放平滑過渡至最適全覽縮放 (Fit Bounds)
                    val zoomOffsetTransition = -(1.0 + initialZoomOffset) * ease.toDouble()
                    val curPanX = initialPanX * (1f - ease)
                    val curPanY = initialPanY * (1f - ease)

                    _uiState.update {
                        it.copy(
                            focusPoint = Pair(curLat, curLon),
                            panOffsetX = curPanX,
                            panOffsetY = curPanY,
                            cameraZoomTransitionOffset = zoomOffsetTransition,
                            wptAlphaMap = emptyMap()
                        )
                    }
                }

                // Finish playback at Fit Bounds overview
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        focusPoint = Pair(centerLat, centerLon),
                        panOffsetX = 0f,
                        panOffsetY = 0f,
                        cameraZoomTransitionOffset = -(1.0 + initialZoomOffset),
                        wptAlphaMap = emptyMap()
                    )
                }
            }
        }
    }

    /**
     * Pauses the animation playback.
     */
    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    /**
     * Toggles play/pause state.
     */
    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    /**
     * Seeks to a specific progress position (0.0 .. 1.0) along the track.
     */
    fun seekTo(progress: Float) {
        val track = _uiState.value.track ?: return
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        val interpolated = track.interpolate(clampedProgress)

        val alphaMap = if (_uiState.value.wptVisibilityMode == WptVisibilityMode.ONLY_DURING_PAUSE) {
            val map = mutableMapOf<Int, Float>()
            for (i in track.waypoints.indices) {
                val wptP = track.findClosestProgress(track.waypoints[i].lat, track.waypoints[i].lon)
                val dist = kotlin.math.abs(clampedProgress - wptP)
                val window = 0.012f
                if (dist < window) {
                    val a = (1.0f - dist / window).coerceIn(0f, 1f)
                    if (a > 0.005f) map[i] = a
                }
            }
            map
        } else emptyMap()

        val curDiv = if (_uiState.value.showAdminDivisionSubtitle) {
            adminDivisionResolver.resolveSync(interpolated.lat, interpolated.lon)
        } else ""

        _uiState.update {
            it.copy(
                progress = clampedProgress,
                interpolatedPoint = interpolated,
                focusPoint = null,
                cameraZoomTransitionOffset = 0.0,
                wptAlphaMap = alphaMap,
                currentAdminDivision = if (curDiv.isNotEmpty()) curDiv else it.currentAdminDivision
            )
        }
    }

    /**
     * Sets playback speed multiplier (1x, 2x, 5x, 10x).
     */
    fun setSpeedMultiplier(multiplier: Float) {
        _uiState.update { it.copy(speedMultiplier = multiplier) }
    }

    /**
     * Toggles elevation profile bottom 1/5 overlay visibility.
     */
    fun toggleElevationProfile(show: Boolean? = null) {
        _uiState.update {
            it.copy(showElevationProfile = show ?: !it.showElevationProfile)
        }
    }

    private var lastAdjustedHandle: String = "START"

    /**
     * Slices the active route according to the user's selected time/progress range (0f .. 1f).
     * Maintains camera focus on the end point if the user was adjusting the end point (迄點),
     * and focuses on the start point if the user was adjusting the start point (起點).
     */
    fun setTimeRange(
        range: ClosedFloatingPointRange<Float>,
        focusStart: Boolean = false,
        focusEnd: Boolean = false
    ) {
        val full = _uiState.value.fullTrack ?: return
        val start = range.start.coerceIn(0f, 0.999f)
        val end = range.endInclusive.coerceIn(start + 0.001f, 1f)

        if (focusStart) {
            lastAdjustedHandle = "START"
        } else if (focusEnd) {
            lastAdjustedHandle = "END"
        }

        val sliced = full.sliceRange(start, end)
        updateEndpoints(sliced)
        pausePlayback()

        val shouldFocusStart = (lastAdjustedHandle == "START")
        val shouldFocusEnd = (lastAdjustedHandle == "END")

        val focusPt = when {
            shouldFocusEnd && sliced.points.isNotEmpty() -> {
                val pt = sliced.points.last()
                Pair(pt.lat, pt.lon)
            }
            shouldFocusStart && sliced.points.isNotEmpty() -> {
                val pt = sliced.points.first()
                Pair(pt.lat, pt.lon)
            }
            else -> null
        }

        val initialPoint = if (shouldFocusEnd && sliced.points.isNotEmpty()) {
            sliced.interpolate(1.0f)
        } else {
            sliced.interpolate(0.0f)
        }

        _uiState.update {
            it.copy(
                selectedRange = start..end,
                track = sliced,
                progress = if (shouldFocusEnd) 1.0f else 0.0f,
                interpolatedPoint = initialPoint,
                focusPoint = focusPt,
                panOffsetX = if (focusEnd || focusStart) 0f else it.panOffsetX,
                panOffsetY = if (focusEnd || focusStart) 0f else it.panOffsetY
            )
        }
    }

    /**
     * Sets the route start point using a specific Waypoint (<wpt>).
     * Auto-expands the end boundary if the waypoint is beyond the current end point.
     */
    fun setStartFromWaypoint(wpt: com.gpxami.app.data.model.GpxWaypoint) {
        val full = _uiState.value.fullTrack ?: return
        val p = full.findClosestProgress(wpt.lat, wpt.lon)
        var curEnd = _uiState.value.selectedRange.endInclusive
        if (p >= curEnd) {
            curEnd = (p + 0.05f).coerceAtMost(1.0f)
        }
        val newStart = p.coerceAtMost(curEnd - 0.001f)
        lastAdjustedHandle = "START"
        setTimeRange(newStart..curEnd, focusStart = true, focusEnd = false)
    }

    /**
     * Sets the route end point (destination) using a specific Waypoint (<wpt>).
     * Auto-expands the start boundary if the waypoint is before the current start point.
     */
    fun setEndFromWaypoint(wpt: com.gpxami.app.data.model.GpxWaypoint) {
        val full = _uiState.value.fullTrack ?: return
        val p = full.findClosestProgress(wpt.lat, wpt.lon)
        var curStart = _uiState.value.selectedRange.start
        if (p <= curStart) {
            curStart = (p - 0.05f).coerceAtLeast(0.0f)
        }
        val newEnd = p.coerceAtLeast(curStart + 0.001f)
        lastAdjustedHandle = "END"
        setTimeRange(curStart..newEnd, focusStart = false, focusEnd = true)
    }

    /**
     * Resets the time range back to the 100% full track.
     */
    fun resetTimeRange() {
        val full = _uiState.value.fullTrack ?: return
        pausePlayback()
        lastAdjustedHandle = "START"
        updateEndpoints(full)
        val initialPoint = full.interpolate(0.0f)
        _uiState.update {
            it.copy(
                selectedRange = 0f..1f,
                track = full,
                progress = 0.0f,
                interpolatedPoint = initialPoint,
                focusPoint = null
            )
        }
    }

    /**
     * Switches between OpenStreetMap (default) and OpenTopoMap.
     */
    fun setMapStyle(style: com.gpxami.app.map.MapStyle) {
        _uiState.update { it.copy(mapStyle = style) }
    }

    /**
     * Sets map rotation angle in degrees.
     */
    fun setMapRotation(degrees: Float) {
        val normalized = (degrees % 360f + 360f) % 360f
        _uiState.update { it.copy(mapRotation = normalized) }
    }

    /**
     * Resets map rotation to North-up (0 degrees).
     */
    fun resetMapRotation() {
        _uiState.update { it.copy(mapRotation = 0f) }
    }

    /**
     * Rotates map by relative degrees (e.g. +45° or -45°).
     */
    fun rotateMapBy(degrees: Float) {
        setMapRotation(_uiState.value.mapRotation + degrees)
    }

    /**
     * Toggles whether waypoint (<wpt>) text name labels are displayed on the map.
     */
    fun toggleWaypointLabels() {
        _uiState.update { it.copy(showWaypointLabels = !it.showWaypointLabels) }
    }

    fun setShowWaypointLabels(show: Boolean) {
        _uiState.update { it.copy(showWaypointLabels = show) }
    }

    /**
     * Adjusts map zoom offset (relative to optimal zoom).
     */
    fun adjustZoom(delta: Double) {
        _uiState.update {
            it.copy(zoomOffset = (it.zoomOffset + delta).coerceIn(-3.0, 4.0))
        }
    }

    /**
     * Sets exact zoom offset.
     */
    fun setZoomOffset(offset: Double) {
        _uiState.update {
            it.copy(zoomOffset = offset.coerceIn(-3.0, 4.0))
        }
    }

    /**
     * Resets zoom offset back to optimal fit.
     */
    fun resetZoom() {
        _uiState.update { it.copy(zoomOffset = 0.0) }
    }

    /**
     * Interactive pan / move map by [dx] and [dy] pixels (in unrotated canvas coordinates).
     */
    fun onPan(dx: Float, dy: Float) {
        _uiState.update {
            it.copy(
                panOffsetX = it.panOffsetX + dx,
                panOffsetY = it.panOffsetY + dy,
                focusPoint = null // Manual pan overrides explicit focus
            )
        }
    }

    /**
     * Resets pan offsets back to center on the active route/marker.
     */
    fun resetPan() {
        _uiState.update {
            it.copy(
                panOffsetX = 0f,
                panOffsetY = 0f,
                focusPoint = null
            )
        }
    }

    /**
     * Resets all viewport modifications (rotation, zoom, pan).
     */
    fun resetView() {
        _uiState.update {
            it.copy(
                mapRotation = 0f,
                zoomOffset = 0.0,
                panOffsetX = 0f,
                panOffsetY = 0f,
                focusPoint = null
            )
        }
    }

    /**
     * Launches the off-screen MP4 video export pipeline with active range, rotation, zoom, pan, and map style.
     */
    fun exportVideo(config: VideoEncoder.ExportConfig) {
        val track = _uiState.value.track ?: return

        pausePlayback()
        _uiState.update { it.copy(exportState = ExportState.Exporting(0f, 0, config.durationSeconds * config.fps)) }

        val exportConfig = config.copy(
            showElevationProfile = _uiState.value.showElevationProfile,
            showWaypointLabels = _uiState.value.showWaypointLabels,
            zoomOffset = _uiState.value.zoomOffset,
            rotationDegrees = _uiState.value.mapRotation,
            mapStyle = _uiState.value.mapStyle,
            panOffsetX = _uiState.value.panOffsetX,
            panOffsetY = _uiState.value.panOffsetY,
            videoTitle = _uiState.value.videoTitle,
            titleTextSize = _uiState.value.titleTextSize,
            wptLabelTextSize = _uiState.value.wptLabelTextSize,
            markerRadius = _uiState.value.markerRadius,
            markerColor = _uiState.value.markerColor,
            trackWidth = _uiState.value.trackWidth,
            trackColor = _uiState.value.trackColor,
            uiViewportWidth = _uiState.value.uiViewportWidth,
            uiViewportHeight = _uiState.value.uiViewportHeight,
            uiDensity = _uiState.value.uiDensity,
            wptPauseDurationSec = _uiState.value.wptPauseDurationSec,
            wptVisibilityMode = _uiState.value.wptVisibilityMode,
            showAdminDivisionSubtitle = _uiState.value.showAdminDivisionSubtitle,
            startWpt = _uiState.value.startWpt,
            endWpt = _uiState.value.endWpt,
            adminDivisionConfig = _uiState.value.adminDivisionConfig
        )

        viewModelScope.launch {
            val result = videoEncoder.exportTrackVideo(
                track = track,
                config = exportConfig,
                onProgress = { progress, currentFrame, totalFrames ->
                    _uiState.update {
                        it.copy(exportState = ExportState.Exporting(progress, currentFrame, totalFrames))
                    }
                }
            )

            result.onSuccess { uri ->
                _uiState.update { it.copy(exportState = ExportState.Success(uri)) }
            }.onFailure { err ->
                _uiState.update { it.copy(exportState = ExportState.Error(err.localizedMessage ?: "Unknown export error")) }
            }
        }
    }

    /**
     * [需求 4] Updates the title displayed on screen and in exported video.
     */
    fun setVideoTitle(newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            prefs.edit().putString(KEY_LAST_VIDEO_TITLE, trimmed).apply()
            _uiState.update { it.copy(videoTitle = trimmed) }
        }
    }

    /**
     * [需求: title 可選字型大小]
     */
    fun setTitleTextSize(size: Float) {
        prefs.edit().putFloat(KEY_TITLE_TEXT_SIZE, size).apply()
        _uiState.update { it.copy(titleTextSize = size) }
    }

    /**
     * [需求: 讓輸出影片的地圖範圍與 UI 顯示地圖的範圍一致, WPT, TITLE 文字比例也一致]
     */
    fun setUiViewportSize(width: Float, height: Float, density: Float) {
        if (width > 0f && height > 0f) {
            _uiState.update {
                if (it.uiViewportWidth != width || it.uiViewportHeight != height || it.uiDensity != density) {
                    it.copy(uiViewportWidth = width, uiViewportHeight = height, uiDensity = density)
                } else it
            }
        }
    }

    /**
     * [需求 1] Sets WPT label font size (options e.g. 14f, 20f, 26f, 32f).
     */
    fun setWptLabelTextSize(size: Float) {
        prefs.edit().putFloat(KEY_WPT_LABEL_SIZE, size).apply()
        _uiState.update { it.copy(wptLabelTextSize = size) }
    }

    /**
     * [需求 2] Sets progress marker dot radius (options e.g. 8f, 12f, 16f, 22f).
     */
    fun setMarkerRadius(radius: Float) {
        prefs.edit().putFloat(KEY_MARKER_RADIUS, radius).apply()
        _uiState.update { it.copy(markerRadius = radius) }
    }

    /**
     * [需求 2] Sets progress marker dot color.
     */
    fun setMarkerColor(color: Int) {
        prefs.edit().putInt(KEY_MARKER_COLOR, color).apply()
        _uiState.update { it.copy(markerColor = color) }
    }

    /**
     * [需求 3] Sets route path stroke width (options e.g. 3f, 5f, 8f, 12f).
     */
    fun setTrackWidth(width: Float) {
        prefs.edit().putFloat(KEY_TRACK_WIDTH, width).apply()
        _uiState.update { it.copy(trackWidth = width) }
    }

    /**
     * [需求 3] Sets route path stroke color.
     */
    fun setTrackColor(color: Int) {
        prefs.edit().putInt(KEY_TRACK_COLOR, color).apply()
        _uiState.update { it.copy(trackColor = color) }
    }

    /**
     * [新需求 1] Sets WPT Pause Duration (0 to 3 seconds).
     */
    fun setWptPauseDuration(seconds: Float) {
        val clamped = seconds.coerceIn(0.0f, 3.0f)
        prefs.edit().putFloat(KEY_WPT_PAUSE_DURATION, clamped).apply()
        _uiState.update { it.copy(wptPauseDurationSec = clamped) }
    }

    /**
     * [新需求 2] Sets WPT Visibility Mode: ONLY_DURING_PAUSE or ALWAYS_SHOW.
     */
    fun setWptVisibilityMode(mode: WptVisibilityMode) {
        prefs.edit().putString(KEY_WPT_VISIBILITY_MODE, mode.name).apply()
        _uiState.update { it.copy(wptVisibilityMode = mode) }
    }

    /**
     * [新需求 3] Toggles display of Administrative Division subtitle below title.
     */
    fun setShowAdminDivisionSubtitle(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ADMIN_DIVISION_SUBTITLE, show).apply()
        val currentPt = _uiState.value.interpolatedPoint
        val config = _uiState.value.adminDivisionConfig
        val div = if (show && currentPt != null) {
            adminDivisionResolver.resolveSync(currentPt.lat, currentPt.lon, config)
        } else ""
        _uiState.update { it.copy(showAdminDivisionSubtitle = show, currentAdminDivision = div) }
        if (show) {
            val currentTrk = _uiState.value.track
            if (currentTrk != null) {
                viewModelScope.launch {
                    adminDivisionResolver.preloadForTrack(currentTrk)
                    if (currentPt != null) {
                        val resolved = adminDivisionResolver.resolve(currentPt.lat, currentPt.lon, config)
                        _uiState.update { it.copy(currentAdminDivision = resolved) }
                    }
                }
            }
        }
    }

    /**
     * Updates the administrative naming format (LEVEL_1_ONLY vs LEVEL_1_AND_2)
     * and refreshes the subtitle on the second line of the title immediately.
     */
    fun setAdminDivisionConfig(config: AdminDivisionConfig) {
        viewModelScope.launch {
            userPreferencesRepository.setAdminDivisionConfig(config)
        }
        val currentPt = _uiState.value.interpolatedPoint
        val updatedSubtitle = if (_uiState.value.showAdminDivisionSubtitle && currentPt != null) {
            adminDivisionResolver.resolveSync(currentPt.lat, currentPt.lon, config)
        } else _uiState.value.currentAdminDivision

        _uiState.update { state ->
            state.copy(
                adminDivisionConfig = config,
                currentAdminDivision = updatedSubtitle
            )
        }
        if (_uiState.value.showAdminDivisionSubtitle && currentPt != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolved = adminDivisionResolver.resolve(currentPt.lat, currentPt.lon, config)
                _uiState.update { it.copy(currentAdminDivision = resolved) }
            }
        }
    }

    /**
     * Updates Start and End waypoint models displaying their actual WPT label.
     */
    private fun updateEndpoints(track: GpxTrack) {
        if (track.points.isEmpty()) {
            _uiState.update {
                it.copy(
                    startWpt = null,
                    endWpt = null,
                    startWptLabel = "起點",
                    endWptLabel = "迄點"
                )
            }
            return
        }

        val firstPt = track.points.first()
        val lastPt = track.points.last()

        val allWaypoints = if (track.waypoints.isNotEmpty()) track.waypoints else (_uiState.value.fullTrack?.waypoints ?: emptyList())
        val matchingStart = allWaypoints.minByOrNull { wpt ->
            GpxTrack.distanceMeters(wpt.lat, wpt.lon, firstPt.lat, firstPt.lon)
        }?.takeIf { wpt ->
            GpxTrack.distanceMeters(wpt.lat, wpt.lon, firstPt.lat, firstPt.lon) < 500.0
        }

        val matchingEnd = allWaypoints.minByOrNull { wpt ->
            GpxTrack.distanceMeters(wpt.lat, wpt.lon, lastPt.lat, lastPt.lon)
        }?.takeIf { wpt ->
            GpxTrack.distanceMeters(wpt.lat, wpt.lon, lastPt.lat, lastPt.lon) < 500.0
        }

        val startAdminSync = geocodingRepository.resolveAdminDivisionSync(firstPt.lat, firstPt.lon)
        val endAdminSync = geocodingRepository.resolveAdminDivisionSync(lastPt.lat, lastPt.lon)

        val initialStartWpt = Wpt(
            lat = firstPt.lat,
            lon = firstPt.lon,
            elevation = firstPt.elevation,
            name = matchingStart?.name ?: "起點",
            desc = matchingStart?.desc,
            role = WptRole.START,
            adminLevel1 = startAdminSync.level1,
            adminLevel2 = startAdminSync.level2
        )

        val initialEndWpt = Wpt(
            lat = lastPt.lat,
            lon = lastPt.lon,
            elevation = lastPt.elevation,
            name = matchingEnd?.name ?: "迄點",
            desc = matchingEnd?.desc,
            role = WptRole.END,
            adminLevel1 = endAdminSync.level1,
            adminLevel2 = endAdminSync.level2
        )

        _uiState.update {
            it.copy(
                startWpt = initialStartWpt,
                endWpt = initialEndWpt,
                startWptLabel = initialStartWpt.formatDisplayLabel(),
                endWptLabel = initialEndWpt.formatDisplayLabel()
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val startAdmin = geocodingRepository.resolveAdminDivision(firstPt.lat, firstPt.lon)
            val endAdmin = geocodingRepository.resolveAdminDivision(lastPt.lat, lastPt.lon)

            _uiState.update { current ->
                val updatedStart = current.startWpt?.copy(
                    adminLevel1 = startAdmin.level1,
                    adminLevel2 = startAdmin.level2
                )
                val updatedEnd = current.endWpt?.copy(
                    adminLevel1 = endAdmin.level1,
                    adminLevel2 = endAdmin.level2
                )
                current.copy(
                    startWpt = updatedStart,
                    endWpt = updatedEnd,
                    startWptLabel = updatedStart?.formatDisplayLabel() ?: "起點",
                    endWptLabel = updatedEnd?.formatDisplayLabel() ?: "迄點"
                )
            }
        }
    }

    private fun getFolderHistory(): List<String> {
        val raw = prefs.getString(KEY_FOLDER_HISTORY, null) ?: return emptyList()
        return raw.split(";").filter { it.isNotBlank() }
    }

    private fun saveFolderHistory(history: List<String>) {
        prefs.edit().putString(KEY_FOLDER_HISTORY, history.joinToString(";")).apply()
    }

    /**
     * [需求 6] Records folder history and maintains default directory pointing to second-to-last directory (上上次的目錄).
     */
    private fun recordFolderHistory(uri: Uri) {
        try {
            val folderUri = extractFolderUri(uri) ?: return
            val folderStr = folderUri.toString()
            val history = getFolderHistory().toMutableList()
            history.remove(folderStr)
            history.add(folderStr)
            while (history.size > 10) {
                history.removeAt(0)
            }
            saveFolderHistory(history)

            // Requirement 6: "開啟GPX時, 預設開上上次的目錄"
            val targetPickerUri = if (history.size >= 2) {
                Uri.parse(history[history.size - 2])
            } else {
                Uri.parse(history.last())
            }
            _uiState.update { it.copy(initialPickerUri = targetPickerUri) }
        } catch (_: Exception) {}
    }

    private fun extractFolderUri(uri: Uri): Uri? {
        return try {
            if (DocumentsContract.isDocumentUri(getApplication(), uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val sep = if (docId.contains('/')) "/" else if (docId.contains("%2F")) "%2F" else null
                if (sep != null) {
                    val parentDocId = docId.substringBeforeLast(sep)
                    DocumentsContract.buildDocumentUriUsingTree(uri, parentDocId)
                } else {
                    uri
                }
            } else {
                uri
            }
        } catch (_: Exception) {
            uri
        }
    }

    private fun queryFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /**
     * Dismisses the export modal / snackbar.
     */
    fun dismissExportDialog() {
        _uiState.update { it.copy(exportState = ExportState.Idle) }
    }

    /**
     * Clears any active error message.
     */
    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
    }
}
