package com.mp3ext.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioExtenderTest {

    private val extender = AudioExtender()

    private fun generateSyntheticSine(sampleRate: Int, channels: Int, durationSec: Double, freqHz: Double = 440.0): DecodedAudio {
        val totalFrames = (durationSec * sampleRate).toInt()
        val totalSamples = totalFrames * channels
        val pcm = ShortArray(totalSamples)

        for (f in 0 until totalFrames) {
            val t = f.toDouble() / sampleRate
            val sampleVal = (sin(2 * PI * freqHz * t) * 16000).toInt().toShort()
            for (ch in 0 until channels) {
                pcm[f * channels + ch] = sampleVal
            }
        }

        return DecodedAudio(
            sampleRate = sampleRate,
            channelCount = channels,
            samples = pcm,
            durationMs = (durationSec * 1000).toLong()
        )
    }

    private fun assertDurationWithinFivePercent(actualSec: Double, targetSec: Double) {
        val errorRatio = abs(actualSec - targetSec) / targetSec
        assertTrue(
            "實際長度 $actualSec 秒 與 目標長度 $targetSec 秒 之誤差率 ${(errorRatio * 100)}% 必須 <= 5%",
            errorRatio <= 0.05
        )
    }

    @Test
    fun testAudioExtensionDurationThreeX() = runBlocking {
        val sampleRate = 44100
        val channels = 2
        val input = generateSyntheticSine(sampleRate, channels, 5.0)

        val targetSec = 15.0
        val config = AudioExtensionConfig(
            targetDurationSec = targetSec,
            crossfadeMs = 800,
            introPercent = 0.10f,
            outroPercent = 0.10f,
            useZeroCrossingAlignment = true
        )

        val extended = extender.extendAudio(input, config)
        val actualSec = extended.samples.size.toDouble() / (sampleRate * channels)

        assertDurationWithinFivePercent(actualSec, targetSec)

        for (sample in extended.samples) {
            assertTrue(sample >= Short.MIN_VALUE && sample <= Short.MAX_VALUE)
        }
    }

    @Test
    fun testAudioExtensionDifficultBoundary() = runBlocking {
        // Challenging case: 30s original extended to 40s
        // Fixed loop repetition would normally jump to 50s+, requiring dynamic loop budgeting & micro-trim
        val sampleRate = 44100
        val channels = 2
        val input = generateSyntheticSine(sampleRate, channels, 30.0)

        val targetSec = 40.0
        val config = AudioExtensionConfig(
            targetDurationSec = targetSec,
            crossfadeMs = 1000,
            useZeroCrossingAlignment = true
        )

        val extended = extender.extendAudio(input, config)
        val actualSec = extended.samples.size.toDouble() / (sampleRate * channels)

        assertDurationWithinFivePercent(actualSec, targetSec)
    }

    @Test
    fun testAudioExtensionLongTarget() = runBlocking {
        // 45s original extended to 150s (2m 30s)
        val sampleRate = 44100
        val channels = 2
        val input = generateSyntheticSine(sampleRate, channels, 45.0)

        val targetSec = 150.0
        val config = AudioExtensionConfig(
            targetDurationSec = targetSec,
            crossfadeMs = 1000,
            useZeroCrossingAlignment = true
        )

        val extended = extender.extendAudio(input, config)
        val actualSec = extended.samples.size.toDouble() / (sampleRate * channels)

        assertDurationWithinFivePercent(actualSec, targetSec)
    }

    @Test
    fun testAudioExtensionShorterThanOriginal() = runBlocking {
        // 10s original trimmed to 8s
        val sampleRate = 44100
        val channels = 2
        val input = generateSyntheticSine(sampleRate, channels, 10.0)

        val targetSec = 8.0
        val config = AudioExtensionConfig(
            targetDurationSec = targetSec
        )

        val extended = extender.extendAudio(input, config)
        val actualSec = extended.samples.size.toDouble() / (sampleRate * channels)

        assertDurationWithinFivePercent(actualSec, targetSec)
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:45", formatDuration(45000L))
        assertEquals("02:30", formatDuration(150000L))
        assertEquals("01:05:20", formatDuration(3920000L))
    }
}
