package com.mp3ext.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Facade coordinating audio decoding, intelligent seamless extension, and encoding.
 */
class AudioProcessor(private val context: Context) {

    private val decoder = AudioDecoder(context)
    private val extender = AudioExtender()
    private val encoder = AudioEncoder(context)

    suspend fun getMetadata(uri: Uri): AudioMetadata {
        return decoder.extractMetadata(uri)
    }

    /**
     * Executes the complete audio extension pipeline emitting progress states.
     */
    fun processAudio(
        sourceUri: Uri,
        config: AudioExtensionConfig,
        format: AudioFormatType
    ): Flow<ProcessingState> = flow {
        emit(ProcessingState.Decoding(0f))

        // Step 1: Decode to PCM
        val decodedAudio = decoder.decodeToPcm(sourceUri) { progress ->
            // Flow emission is safe inside collector context
        }
        emit(ProcessingState.Decoding(1f))

        // Step 2: Seamless Extension
        emit(ProcessingState.Extending(0f))
        val extendedAudio = extender.extendAudio(decodedAudio, config) { progress ->
            // Audio extending progress
        }
        emit(ProcessingState.Extending(1f))

        // Step 3: Encode to output format
        emit(ProcessingState.Encoding(0f))
        val tempDir = File(context.cacheDir, "processed_audio").apply { mkdirs() }
        val outputFile = File(tempDir, "extended_${System.currentTimeMillis()}.${format.extension}")

        val resultFile = when (format) {
            AudioFormatType.M4A_AAC -> {
                encoder.encodeToM4a(extendedAudio, outputFile) { progress ->
                    // Encoding progress
                }
            }
            AudioFormatType.WAV -> {
                encoder.encodeToWav(extendedAudio, outputFile) { progress ->
                    // Encoding progress
                }
            }
        }

        emit(ProcessingState.Encoding(1f))
        emit(ProcessingState.Success(resultFile, extendedAudio.durationMs, format))
    }

    suspend fun saveToDestination(sourceFile: File, destinationUri: Uri): Boolean {
        return encoder.exportToUri(sourceFile, destinationUri)
    }
}
