package com.mp3ext.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes audio files (MP3, AAC, WAV, etc.) into 16-bit PCM samples using Android's native MediaExtractor and MediaCodec.
 */
class AudioDecoder(private val context: Context) {

    /**
     * Inspects audio file metadata without full decoding.
     */
    suspend fun extractMetadata(uri: Uri): AudioMetadata = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var fileName = "unknown_audio"
        var fileSize = 0L

        // Query file name and size from ContentResolver
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}

        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("無法開啟檔案: $uri")

        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || trackFormat == null) {
                throw IllegalArgumentException("所選檔案中找不到有效的音訊軌道。")
            }

            val sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val bitrate = if (trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } else 0

            AudioMetadata(
                fileName = fileName,
                fileSize = fileSize,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationMs = durationUs / 1000,
                mimeType = mime,
                bitrate = bitrate
            )
        } finally {
            extractor.release()
            pfd.close()
        }
    }

    /**
     * Decodes the entire audio stream into 16-bit PCM samples with progress callback.
     */
    suspend fun decodeToPcm(
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("無法開啟檔案: $uri")

        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || trackFormat == null) {
                throw IllegalArgumentException("所選檔案中找不到音訊軌道。")
            }

            extractor.selectTrack(audioTrackIndex)
            val mimeType = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channelCount = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 1L

            decoder = MediaCodec.createDecoderByType(mimeType)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val pcmBytesOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val timeoutUs = 10000L

            var lastReportTime = 0L

            while (!isOutputEOS) {
                coroutineContext.ensureActive()

                // Feed input buffers
                if (!isInputEOS) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()

                            if (durationUs > 0) {
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime > 100) {
                                    val progress = (sampleTime.toFloat() / durationUs).coerceIn(0f, 1f)
                                    onProgress(progress)
                                    lastReportTime = now
                                }
                            }
                        }
                    }
                }

                // Retrieve output buffers
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmBytesOut.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }

            onProgress(1.0f)

            // Convert byte array to ShortArray (16-bit PCM, Little Endian)
            val allBytes = pcmBytesOut.toByteArray()
            val shortCount = allBytes.size / 2
            val shortArray = ShortArray(shortCount)
            ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortArray)

            val totalFrames = if (channelCount > 0) shortCount / channelCount else 0
            val actualDurationMs = if (sampleRate > 0) (totalFrames.toDouble() / sampleRate * 1000).toLong() else 0L

            DecodedAudio(
                sampleRate = sampleRate,
                channelCount = channelCount,
                samples = shortArray,
                durationMs = actualDurationMs
            )
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            extractor.release()
            pfd.close()
        }
    }
}
