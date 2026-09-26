package com.mp3ext.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mp3ext.audio.AudioExtensionConfig
import com.mp3ext.audio.AudioFormatType
import com.mp3ext.audio.AudioMetadata
import com.mp3ext.audio.AudioPlayerManager
import com.mp3ext.audio.AudioProcessor
import com.mp3ext.audio.PlaybackTrack
import com.mp3ext.audio.PlayerState
import com.mp3ext.audio.ProcessingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class QuickDurationAction {
    data object Plus30s : QuickDurationAction()
    data object Plus60s : QuickDurationAction()
    data object DoubleLength : QuickDurationAction()
    data object TripleLength : QuickDurationAction()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioProcessor = AudioProcessor(application)
    private val playerManager = AudioPlayerManager(application)

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    private val _metadata = MutableStateFlow<AudioMetadata?>(null)
    val metadata: StateFlow<AudioMetadata?> = _metadata.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    // Target Duration in Seconds
    private val _targetDurationSec = MutableStateFlow<Int>(60)
    val targetDurationSec: StateFlow<Int> = _targetDurationSec.asStateFlow()

    // Crossfade duration in ms (500 to 2000 ms, default 1000)
    private val _crossfadeMs = MutableStateFlow<Int>(1000)
    val crossfadeMs: StateFlow<Int> = _crossfadeMs.asStateFlow()

    // Export Format
    private val _selectedFormat = MutableStateFlow<AudioFormatType>(AudioFormatType.M4A_AAC)
    val selectedFormat: StateFlow<AudioFormatType> = _selectedFormat.asStateFlow()

    // Processing State
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    // Processed file
    private val _processedFile = MutableStateFlow<File?>(null)
    val processedFile: StateFlow<File?> = _processedFile.asStateFlow()

    // One-time UI events (Snackbars / Toast)
    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    fun onFileSelected(uri: Uri) {
        _selectedUri.value = uri
        viewModelScope.launch {
            try {
                _processingState.value = ProcessingState.Decoding(0f, "正在分析音訊中...")
                val meta = audioProcessor.getMetadata(uri)
                _metadata.value = meta

                // Set initial target duration to 2x original duration, or at least original + 30s
                val origSec = (meta.durationMs / 1000).toInt().coerceAtLeast(5)
                val defaultTarget = (origSec * 2).coerceAtMost(3600)
                _targetDurationSec.value = defaultTarget

                // Reset player for new file
                _processedFile.value = null
                playerManager.setSources(original = uri, extended = null)
                playerManager.switchTrack(PlaybackTrack.ORIGINAL)

                _processingState.value = ProcessingState.Idle
                _eventFlow.emit("已成功載入音訊：${meta.fileName}")
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("無法讀取此音訊：${e.localizedMessage ?: "未知錯誤"}")
                _eventFlow.emit("讀取音訊失敗：${e.localizedMessage}")
            }
        }
    }

    fun setTargetDuration(seconds: Int) {
        _targetDurationSec.value = seconds.coerceIn(5, 7200) // max 2 hours
    }

    fun setCrossfadeMs(ms: Int) {
        _crossfadeMs.value = ms.coerceIn(300, 3000)
    }

    fun setExportFormat(format: AudioFormatType) {
        _selectedFormat.value = format
    }

    fun applyQuickAction(action: QuickDurationAction) {
        val currentTarget = _targetDurationSec.value
        val origSec = ((_metadata.value?.durationMs ?: 30000L) / 1000).toInt()

        val newTarget = when (action) {
            QuickDurationAction.Plus30s -> currentTarget + 30
            QuickDurationAction.Plus60s -> currentTarget + 60
            QuickDurationAction.DoubleLength -> origSec * 2
            QuickDurationAction.TripleLength -> origSec * 3
        }
        setTargetDuration(newTarget)
    }

    fun startProcessing() {
        val uri = _selectedUri.value ?: return
        val targetSec = _targetDurationSec.value.toDouble()

        viewModelScope.launch {
            try {
                val config = AudioExtensionConfig(
                    targetDurationSec = targetSec,
                    crossfadeMs = _crossfadeMs.value,
                    useZeroCrossingAlignment = true
                )

                audioProcessor.processAudio(uri, config, _selectedFormat.value)
                    .collect { state ->
                        _processingState.value = state
                        if (state is ProcessingState.Success) {
                            _processedFile.value = state.outputFile
                            playerManager.setSources(original = uri, extended = state.outputFile)
                            playerManager.switchTrack(PlaybackTrack.EXTENDED)
                            _eventFlow.emit("音訊延長完成！已切換至延長後音訊試聽。")
                        } else if (state is ProcessingState.Error) {
                            _eventFlow.emit("處理失敗：${state.message}")
                        }
                    }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error(e.localizedMessage ?: "處理發生異常")
                _eventFlow.emit("處理發生異常：${e.localizedMessage}")
            }
        }
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
    }

    fun switchPlaybackTrack(track: PlaybackTrack) {
        playerManager.switchTrack(track)
    }

    fun saveProcessedAudio(destinationUri: Uri) {
        val file = _processedFile.value ?: return
        viewModelScope.launch {
            try {
                val success = audioProcessor.saveToDestination(file, destinationUri)
                if (success) {
                    _eventFlow.emit("音訊已成功儲存至目標路徑！")
                } else {
                    _eventFlow.emit("儲存失敗，請檢查儲存空間或存取權限。")
                }
            } catch (e: Exception) {
                _eventFlow.emit("儲存出錯：${e.localizedMessage}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
