package com.mp3ext.audio

import android.net.Uri
import java.io.File

/**
 * Metadata of the analyzed audio file.
 */
data class AudioMetadata(
    val fileName: String,
    val fileSize: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val durationMs: Long,
    val mimeType: String,
    val bitrate: Int = 0
) {
    val durationFormatted: String
        get() = formatDuration(durationMs)

    val fileSizeFormatted: String
        get() = when {
            fileSize >= 1024 * 1024 -> String.format("%.2f MB", fileSize / (1024.0 * 1024.0))
            fileSize >= 1024 -> String.format("%.1f KB", fileSize / 1024.0)
            else -> "$fileSize Bytes"
        }
}

/**
 * Decoded raw 16-bit PCM audio.
 */
data class DecodedAudio(
    val sampleRate: Int,
    val channelCount: Int,
    val samples: ShortArray,
    val durationMs: Long
) {
    val totalFrames: Long
        get() = if (channelCount > 0) samples.size.toLong() / channelCount else 0L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedAudio
        return sampleRate == other.sampleRate &&
                channelCount == other.channelCount &&
                samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channelCount
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/**
 * Configuration options for the seamless extension algorithm.
 */
data class AudioExtensionConfig(
    val targetDurationSec: Double,
    val crossfadeMs: Int = 1000,
    val introPercent: Float = 0.10f,
    val outroPercent: Float = 0.10f,
    val useZeroCrossingAlignment: Boolean = true
)

/**
 * Processing state for the UI.
 */
sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class Decoding(val progress: Float, val statusMessage: String = "正在解碼原始 MP3...") : ProcessingState()
    data class Extending(val progress: Float, val statusMessage: String = "正在分析波形與平滑拼接...") : ProcessingState()
    data class Encoding(val progress: Float, val statusMessage: String = "正在編碼輸出音訊...") : ProcessingState()
    data class Success(val outputFile: File, val durationMs: Long, val format: AudioFormatType) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

/**
 * Supported export audio formats.
 */
enum class AudioFormatType(val extension: String, val mimeType: String, val displayName: String, val description: String) {
    M4A_AAC("m4a", "audio/mp4", "AAC / M4A (原生推薦)", "採用 Android 原生硬體加速編碼，高音質、體積小、相容度最高"),
    WAV("wav", "audio/x-wav", "WAV (無損 PCM)", "未壓縮無損音訊，適用於所有專業音訊與剪輯軟體")
}

/**
 * Helper to format duration milliseconds into mm:ss or hh:mm:ss.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
