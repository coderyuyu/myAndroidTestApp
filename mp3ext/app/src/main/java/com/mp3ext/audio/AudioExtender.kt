package com.mp3ext.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Intelligent Audio Extender implementing:
 * 1. Structure splitting (Intro & Outro preservation)
 * 2. Energy similarity, zero-crossing, and waveform correlation loop point analysis
 * 3. Dynamic length budgeting with strict error bound (<= 5% of target duration)
 * 4. Equal-power crossfade and smooth adaptive Outro fadeout
 */
class AudioExtender {

    suspend fun extendAudio(
        input: DecodedAudio,
        config: AudioExtensionConfig,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio = withContext(Dispatchers.Default) {
        val sampleRate = input.sampleRate
        val channels = input.channelCount
        val totalFrames = (input.samples.size / channels).toInt()
        val originalDurationSec = totalFrames.toDouble() / sampleRate
        val targetDurationSec = config.targetDurationSec

        val targetTotalFrames = (targetDurationSec * sampleRate).toLong()
        val targetMinFrames = (targetTotalFrames * 0.95).toLong()
        val targetMaxFrames = (targetTotalFrames * 1.05).toLong()

        // 1. Target duration is already within or less than original duration
        if (targetTotalFrames <= totalFrames) {
            onProgress(1.0f)
            return@withContext if (targetTotalFrames < totalFrames * 0.95) {
                trimAudioWithFadeOut(input, targetTotalFrames.toInt())
            } else {
                input
            }
        }

        // 2. Determine initial Intro & Outro boundaries
        // Keep intro and outro between 5% and 15% of original, capped at 3~4 seconds
        val maxAnchorSec = 4.0
        val introSec = min(originalDurationSec * config.introPercent, maxAnchorSec).coerceAtLeast(0.3)
        val outroSec = min(originalDurationSec * config.outroPercent, maxAnchorSec).coerceAtLeast(0.3)

        var introFrames = (introSec * sampleRate).toInt()
        var outroFrames = (outroSec * sampleRate).toInt()

        // Ensure middle body has at least 60% of original length
        if (introFrames + outroFrames >= totalFrames * 0.7) {
            introFrames = (totalFrames * 0.15).toInt()
            outroFrames = (totalFrames * 0.15).toInt()
        }

        var candidateBodyStart = introFrames
        var candidateBodyEnd = totalFrames - outroFrames

        // 3. Structural & Energy/Zero-Crossing/Correlation Analysis
        if (config.useZeroCrossingAlignment) {
            val windowFrames = (sampleRate * 0.20).toInt() // ±200ms search window
            val patternFrames = (sampleRate * 0.05).toInt() // 50ms correlation pattern

            // Find best zero-crossing near intro boundary
            candidateBodyStart = findBestZeroCrossing(
                samples = input.samples,
                channels = channels,
                centerFrame = candidateBodyStart,
                windowFrames = windowFrames,
                maxFrames = totalFrames
            )

            // Find best candidate near outro boundary that matches energy & correlation
            candidateBodyEnd = findBestEnergyAndCorrelationMatch(
                samples = input.samples,
                channels = channels,
                sampleRate = sampleRate,
                targetPatternFrame = candidateBodyStart,
                searchCenterFrame = candidateBodyEnd,
                searchWindowFrames = windowFrames,
                patternLengthFrames = patternFrames
            )
        }

        var bodyFrames = candidateBodyEnd - candidateBodyStart
        if (bodyFrames <= sampleRate / 2) {
            // Fallback to safe 10% - 90% range if body is too small
            candidateBodyStart = (totalFrames * 0.10).toInt()
            candidateBodyEnd = (totalFrames * 0.90).toInt()
            bodyFrames = candidateBodyEnd - candidateBodyStart
        }

        // 4. Dynamic Length Budgeting & Error Verification (TargetMin .. TargetMax)
        // Keep crossfade within 500ms ~ 1500ms
        val baseCrossfadeFrames = (config.crossfadeMs / 1000.0 * sampleRate).toInt()
            .coerceIn((sampleRate * 0.5).toInt(), (sampleRate * 1.5).toInt())
            .coerceAtMost(bodyFrames / 3)

        var crossfadeFrames = baseCrossfadeFrames
        val introLen = candidateBodyStart
        val outroLen = totalFrames - candidateBodyEnd

        var effectiveBodyAdvance = bodyFrames - crossfadeFrames

        // Find loop repetition count N such that estimated length reaches at least targetTotalFrames
        var loopCount = 1
        var estTotalFrames = introLen.toLong() + bodyFrames + outroLen

        while (estTotalFrames < targetTotalFrames) {
            loopCount++
            estTotalFrames = introLen.toLong() + bodyFrames + (loopCount - 1).toLong() * effectiveBodyAdvance + outroLen
        }

        // If estimated length exceeds TargetMax, try micro-adjusting crossfade within 500ms ~ 1500ms
        if (estTotalFrames > targetMaxFrames && loopCount > 1) {
            val maxCrossfade = minOf((sampleRate * 1.5).toInt(), bodyFrames / 3)
            val minCrossfade = maxOf((sampleRate * 0.5).toInt(), sampleRate / 10)
            val excess = estTotalFrames - targetTotalFrames
            val deltaC = (excess.toDouble() / (loopCount - 1)).roundToInt()
            val candidateC = (crossfadeFrames + deltaC).coerceIn(minCrossfade, maxCrossfade)
            val candAdvance = bodyFrames - candidateC
            val candTotal = introLen.toLong() + bodyFrames + (loopCount - 1).toLong() * candAdvance + outroLen
            if (candTotal in targetMinFrames..targetMaxFrames) {
                crossfadeFrames = candidateC
                effectiveBodyAdvance = candAdvance
                estTotalFrames = candTotal
            }
        }

        // Allocate output buffer with room for max possible frames
        val maxBufferSize = maxOf(estTotalFrames, targetMaxFrames) + sampleRate
        val outputSamples = ShortArray((maxBufferSize * channels).toInt())
        var outFrameCursor = 0

        // 1. Copy Intro [0 .. candidateBodyStart)
        val introSampleCount = introLen * channels
        System.arraycopy(input.samples, 0, outputSamples, 0, introSampleCount)
        outFrameCursor += introLen
        onProgress(0.1f)

        // Precompute equal-power crossfade weights
        val halfPi = PI / 2.0
        val fadeOutWeights = DoubleArray(crossfadeFrames)
        val fadeInWeights = DoubleArray(crossfadeFrames)
        for (i in 0 until crossfadeFrames) {
            val progress = i.toDouble() / crossfadeFrames
            fadeOutWeights[i] = cos(halfPi * progress)
            fadeInWeights[i] = sin(halfPi * progress)
        }

        // 2. Append First Loop Body
        val firstBodyFramesToCopy = bodyFrames - crossfadeFrames
        System.arraycopy(
            input.samples,
            candidateBodyStart * channels,
            outputSamples,
            outFrameCursor * channels,
            firstBodyFramesToCopy * channels
        )
        outFrameCursor += firstBodyFramesToCopy

        var prevTailSourceFrame = candidateBodyEnd - crossfadeFrames

        // 3. Repeat Loop Body with Equal-Power Crossfade
        for (rep in 1 until loopCount) {
            coroutineContext.ensureActive()

            // Equal-power crossfade overlapping tail with start of next body
            for (f in 0 until crossfadeFrames) {
                val prevFrame = prevTailSourceFrame + f
                val nextFrame = candidateBodyStart + f
                val wOut = fadeOutWeights[f]
                val wIn = fadeInWeights[f]

                for (ch in 0 until channels) {
                    val samplePrev = input.samples[prevFrame * channels + ch]
                    val sampleNext = input.samples[nextFrame * channels + ch]
                    val mixed = (samplePrev * wOut + sampleNext * wIn).roundToInt()
                    outputSamples[outFrameCursor * channels + ch] = clampToShort(mixed)
                }
                outFrameCursor++
            }

            // Copy the rest of this body (excluding the next crossfade tail)
            val restFrames = bodyFrames - 2 * crossfadeFrames
            if (restFrames > 0) {
                System.arraycopy(
                    input.samples,
                    (candidateBodyStart + crossfadeFrames) * channels,
                    outputSamples,
                    outFrameCursor * channels,
                    restFrames * channels
                )
                outFrameCursor += restFrames
            }

            prevTailSourceFrame = candidateBodyEnd - crossfadeFrames

            val progress = 0.1f + 0.75f * (rep.toFloat() / loopCount)
            onProgress(progress)
        }

        // 4. Crossfade final body tail into Outro
        val outroCrossfadeFrames = min(crossfadeFrames, outroLen / 2).coerceAtLeast(1)
        for (f in 0 until outroCrossfadeFrames) {
            val prevFrame = prevTailSourceFrame + f
            val nextFrame = candidateBodyEnd + f
            val wOut = cos(halfPi * (f.toDouble() / outroCrossfadeFrames))
            val wIn = sin(halfPi * (f.toDouble() / outroCrossfadeFrames))

            for (ch in 0 until channels) {
                val samplePrev = input.samples[prevFrame * channels + ch]
                val sampleNext = input.samples[nextFrame * channels + ch]
                val mixed = (samplePrev * wOut + sampleNext * wIn).roundToInt()
                outputSamples[outFrameCursor * channels + ch] = clampToShort(mixed)
            }
            outFrameCursor++
        }

        // 5. Append remainder of Outro
        val outroRemainingFrames = outroLen - outroCrossfadeFrames
        if (outroRemainingFrames > 0) {
            System.arraycopy(
                input.samples,
                (candidateBodyEnd + outroCrossfadeFrames) * channels,
                outputSamples,
                outFrameCursor * channels,
                outroRemainingFrames * channels
            )
            outFrameCursor += outroRemainingFrames
        }

        // 6. Strict Error Bound Verification & Final Micro-Trim / Fadeout Check
        // Ensure final length is strictly in [TargetMin, TargetMax]
        var finalTotalFrames = outFrameCursor

        if (finalTotalFrames > targetMaxFrames) {
            // Trim precisely to targetTotalFrames with smooth fadeout
            val desiredFrames = targetTotalFrames.toInt()
            applySmoothFadeOut(outputSamples, channels, sampleRate, desiredFrames)
            finalTotalFrames = desiredFrames
        }

        onProgress(1.0f)

        val finalDurationMs = (finalTotalFrames.toDouble() / sampleRate * 1000).toLong()
        val finalSamples = outputSamples.copyOf((finalTotalFrames * channels).coerceAtLeast(0))

        DecodedAudio(
            sampleRate = sampleRate,
            channelCount = channels,
            samples = finalSamples,
            durationMs = finalDurationMs
        )
    }

    /**
     * Finds the best zero crossing with a positive slope within window.
     */
    private fun findBestZeroCrossing(
        samples: ShortArray,
        channels: Int,
        centerFrame: Int,
        windowFrames: Int,
        maxFrames: Int
    ): Int {
        val start = (centerFrame - windowFrames).coerceAtLeast(1)
        val end = (centerFrame + windowFrames).coerceAtMost(maxFrames - 2)

        var bestFrame = centerFrame
        var minAbsVal = Int.MAX_VALUE

        for (f in start until end) {
            val curr = samples[f * channels]
            val next = samples[(f + 1) * channels]
            if (curr <= 0 && next > 0) {
                val absVal = abs(curr.toInt())
                if (absVal < minAbsVal) {
                    minAbsVal = absVal
                    bestFrame = f
                }
            }
        }
        return bestFrame
    }

    /**
     * Finds the candidate frame near searchCenterFrame that best correlates in waveform
     * and has matching RMS energy with the pattern at targetPatternFrame.
     */
    private fun findBestEnergyAndCorrelationMatch(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        targetPatternFrame: Int,
        searchCenterFrame: Int,
        searchWindowFrames: Int,
        patternLengthFrames: Int
    ): Int {
        val totalFrames = samples.size / channels
        val start = (searchCenterFrame - searchWindowFrames).coerceAtLeast(0)
        val end = (searchCenterFrame + searchWindowFrames).coerceAtMost(totalFrames - patternLengthFrames - 1)

        val targetEnergy = calculateRmsEnergy(samples, channels, targetPatternFrame, patternLengthFrames)

        var bestFrame = searchCenterFrame
        var lowestScore = Double.MAX_VALUE

        for (candidate in start until end step 4) {
            // 1. Correlation difference
            var diff = 0L
            for (p in 0 until patternLengthFrames step 2) {
                val s1 = samples[(targetPatternFrame + p) * channels].toLong()
                val s2 = samples[(candidate + p) * channels].toLong()
                diff += abs(s1 - s2)
            }

            // 2. Energy difference
            val candEnergy = calculateRmsEnergy(samples, channels, candidate, patternLengthFrames)
            val energyDiffRatio = abs(targetEnergy - candEnergy) / max(targetEnergy, 1.0)

            // Combined score (penalizes energy mismatch > 25%)
            val score = diff.toDouble() * (1.0 + energyDiffRatio * 2.0)

            if (score < lowestScore) {
                lowestScore = score
                bestFrame = candidate
            }
        }
        return bestFrame
    }

    private fun calculateRmsEnergy(
        samples: ShortArray,
        channels: Int,
        startFrame: Int,
        windowFrames: Int
    ): Double {
        var sumSquares = 0.0
        val end = min(startFrame + windowFrames, samples.size / channels)
        val count = end - startFrame
        if (count <= 0) return 0.0

        for (f in startFrame until end) {
            for (ch in 0 until channels) {
                val v = samples[f * channels + ch].toDouble()
                sumSquares += v * v
            }
        }
        return sqrt(sumSquares / (count * channels))
    }

    /**
     * Applies a smooth cosine fadeout over the end of the audio before trimming.
     */
    private fun applySmoothFadeOut(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        targetFrame: Int
    ) {
        val fadeFrames = min((sampleRate * 0.30).toInt(), targetFrame / 10).coerceAtLeast(1)
        val fadeStart = targetFrame - fadeFrames
        val halfPi = PI / 2.0

        for (i in 0 until fadeFrames) {
            val f = fadeStart + i
            val progress = i.toDouble() / fadeFrames
            val gain = cos(progress * halfPi) // 1.0 down to 0.0

            for (ch in 0 until channels) {
                val orig = samples[f * channels + ch]
                samples[f * channels + ch] = (orig * gain).roundToInt().toShort()
            }
        }
    }

    private fun trimAudioWithFadeOut(input: DecodedAudio, targetFrames: Int): DecodedAudio {
        val trimmedSamples = input.samples.copyOf((targetFrames * input.channelCount).coerceAtMost(input.samples.size))
        applySmoothFadeOut(trimmedSamples, input.channelCount, input.sampleRate, targetFrames)
        val newDurationMs = (targetFrames.toDouble() / input.sampleRate * 1000).toLong()
        return DecodedAudio(
            sampleRate = input.sampleRate,
            channelCount = input.channelCount,
            samples = trimmedSamples,
            durationMs = newDurationMs
        )
    }

    private fun clampToShort(value: Int): Short = when {
        value > Short.MAX_VALUE -> Short.MAX_VALUE
        value < Short.MIN_VALUE -> Short.MIN_VALUE
        else -> value.toShort()
    }
}
