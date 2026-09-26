package com.mp3ext.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class PlaybackTrack {
    ORIGINAL,
    EXTENDED
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activeTrack: PlaybackTrack = PlaybackTrack.ORIGINAL,
    val isBuffering: Boolean = false
)

class AudioPlayerManager(private val context: Context) {

    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var originalUri: Uri? = null
    private var extendedFile: File? = null

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playerState.value = _playerState.value.copy(
                        isPlaying = isPlaying,
                        durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
                    )
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    val isBuffering = playbackState == Player.STATE_BUFFERING
                    val duration = player?.duration?.coerceAtLeast(0L) ?: 0L
                    _playerState.value = _playerState.value.copy(
                        isBuffering = isBuffering,
                        durationMs = duration
                    )
                    if (playbackState == Player.STATE_ENDED) {
                        _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = 0L)
                        stopProgressUpdates()
                    }
                }
            })
        }
    }

    fun setSources(original: Uri?, extended: File?) {
        this.originalUri = original
        this.extendedFile = extended
    }

    fun switchTrack(track: PlaybackTrack) {
        val targetMediaItem: MediaItem? = when (track) {
            PlaybackTrack.ORIGINAL -> originalUri?.let { MediaItem.fromUri(it) }
            PlaybackTrack.EXTENDED -> extendedFile?.let { MediaItem.fromUri(Uri.fromFile(it)) }
        }

        if (targetMediaItem != null) {
            player?.stop()
            player?.setMediaItem(targetMediaItem)
            player?.prepare()
            _playerState.value = _playerState.value.copy(
                activeTrack = track,
                currentPositionMs = 0L,
                durationMs = 0L
            )
        }
    }

    fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
            } else {
                if (p.playbackState == Player.STATE_IDLE) {
                    p.prepare()
                }
                p.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive) {
                player?.let { p ->
                    _playerState.value = _playerState.value.copy(
                        currentPositionMs = p.currentPosition.coerceAtLeast(0L),
                        durationMs = p.duration.coerceAtLeast(0L)
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressUpdates()
        player?.release()
        player = null
    }
}
