package com.mp3ext.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Encodes decoded PCM audio to AAC (.m4a) using Android native MediaCodec + MediaMuxer,
 * or writes uncompressed WAV (.wav).
 */
class AudioEncoder(private val context: Context) {

    /**
     * Encodes PCM audio to AAC (.m4a) using Android's native hardware/software MediaCodec.
     */
    suspend fun encodeToM4a(
        pcmAudio: DecodedAudio,
        outputFile: File,
        bitrate: Int = 192000,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val sampleRate = pcmAudio.sampleRate
        val channels = pcmAudio.channelCount
        val totalSamples = pcmAudio.samples.size
        val totalFrames = totalSamples / channels

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioTrackIndex = -1
        var muxerStarted = false

        try {
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L
            var sampleCursor = 0
            var isInputEOS = false
            var isOutputEOS = false
            var framesSubmitted = 0L

            // Reusable byte buffer for converting short to byte
            val pcmByteBuffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)

            while (!isOutputEOS) {
                coroutineContext.ensureActive()

                // Feed input samples to encoder
                if (!isInputEOS) {
                    val inIndex = encoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inIndex)!!
                        inputBuffer.clear()
                        pcmByteBuffer.clear()

                        val shortsRemaining = totalSamples - sampleCursor
                        val shortsToRead = minOf(shortsRemaining, inputBuffer.capacity() / 2)

                        if (shortsToRead > 0) {
                            for (i in 0 until shortsToRead) {
                                pcmByteBuffer.putShort(pcmAudio.samples[sampleCursor + i])
                            }
                            sampleCursor += shortsToRead
                            pcmByteBuffer.flip()
                            inputBuffer.put(pcmByteBuffer)

                            val ptsUs = (framesSubmitted.toDouble() / sampleRate * 1_000_000L).toLong()
                            val framesInChunk = shortsToRead / channels
                            framesSubmitted += framesInChunk

                            encoder.queueInputBuffer(inIndex, 0, shortsToRead * 2, ptsUs, 0)

                            val progress = (sampleCursor.toFloat() / totalSamples).coerceIn(0f, 1f)
                            onProgress(progress * 0.9f)
                        } else {
                            val ptsUs = (framesSubmitted.toDouble() / sampleRate * 1_000_000L).toLong()
                            encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        }
                    }
                }

                // Dequeue encoded packets and feed muxer
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex >= 0 -> {
                        val encodedBuffer = encoder.getOutputBuffer(outIndex)
                        if (encodedBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, encodedBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw RuntimeException("格式重複變更")
                        }
                        val newFormat = encoder.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
            }

            onProgress(1.0f)
            outputFile
        } finally {
            try {
                encoder.stop()
                encoder.release()
            } catch (_: Exception) {}
            try {
                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Exports PCM audio directly to standard uncompressed WAV format.
     */
    suspend fun encodeToWav(
        pcmAudio: DecodedAudio,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val sampleRate = pcmAudio.sampleRate
        val channels = pcmAudio.channelCount
        val totalSamples = pcmAudio.samples.size
        val dataSize = totalSamples * 2L
        val totalSize = 36 + dataSize

        FileOutputStream(outputFile).use { fos ->
            // Write WAV Header (44 bytes)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF chunk descriptor
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalSize.toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt sub-chunk
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1.toShort()) // AudioFormat 1 = PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * 2) // ByteRate
            header.putShort((channels * 2).toShort()) // BlockAlign
            header.putShort(16.toShort()) // BitsPerSample

            // data sub-chunk
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize.toInt())

            fos.write(header.array())

            // Write PCM data in chunks
            val chunkSize = 4096
            val byteBuffer = ByteBuffer.allocate(chunkSize * 2).order(ByteOrder.LITTLE_ENDIAN)
            var cursor = 0

            while (cursor < totalSamples) {
                coroutineContext.ensureActive()
                byteBuffer.clear()
                val count = minOf(chunkSize, totalSamples - cursor)
                for (i in 0 until count) {
                    byteBuffer.putShort(pcmAudio.samples[cursor + i])
                }
                fos.write(byteBuffer.array(), 0, count * 2)
                cursor += count

                val progress = (cursor.toFloat() / totalSamples).coerceIn(0f, 1f)
                onProgress(progress)
            }
        }

        outputFile
    }

    /**
     * Copies a processed temporary file to the user's selected Document Uri.
     */
    suspend fun exportToUri(sourceFile: File, destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destinationUri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
            true
        } ?: false
    }
}
