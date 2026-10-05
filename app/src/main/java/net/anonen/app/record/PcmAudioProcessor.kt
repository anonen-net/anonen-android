package net.anonen.app.record

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object PcmAudioProcessor {
    const val TARGET_SAMPLE_RATE = 16000

    private const val BYTES_PER_SAMPLE = 2
    private const val FRAME_MS = 30
    private const val FIR_HALF = 16.0
    private const val MIN_REMOTE_ASR_SAMPLES = TARGET_SAMPLE_RATE
    private const val SHORT_RECORDING_PAD_SAMPLES = TARGET_SAMPLE_RATE * 5 / 4

    private const val FULL_SCALE = 32768.0
    private const val TARGET_RMS_DBFS = -16.0
    private const val MAX_NORMALIZE_GAIN = 10.0
    private const val PEAK_CEILING = 0.95

    fun postProcessSpeech(
        speechPcm16k: ByteArray,
        eq: SpectralMatchEq? = SpectralMatchEq(),
    ): ByteArray? {
        if (speechPcm16k.isEmpty()) return null
        val afterEq =
            if (eq != null) {
                eq.process(speechPcm16k.toShortSamples()).toPcmBytes()
            } else {
                speechPcm16k
            }
        return padShortRecording(normalizeLoudness(afterEq))
    }

    fun prepareForAsr(
        pcmData: ByteArray,
        inputSampleRate: Int,
        vad: VoiceActivityDetector,
        eq: SpectralMatchEq? = SpectralMatchEq(),
    ): ByteArray? {
        val evenPcm =
            if (pcmData.size % BYTES_PER_SAMPLE == 0) {
                pcmData
            } else {
                pcmData.copyOf(pcmData.size - (pcmData.size % BYTES_PER_SAMPLE))
            }
        if (evenPcm.isEmpty()) return null

        val resampled = resamplePcm16Mono(evenPcm, inputSampleRate, TARGET_SAMPLE_RATE)
        val vadFiltered = trimSilence(resampled, TARGET_SAMPLE_RATE, vad)
        if (vadFiltered.isEmpty()) return null
        val afterEq =
            if (eq != null) {
                eq.process(vadFiltered.toShortSamples()).toPcmBytes()
            } else {
                vadFiltered
            }
        return padShortRecording(normalizeLoudness(afterEq))
    }

    fun normalizeLoudness(pcmData: ByteArray): ByteArray {
        val samples = pcmData.toShortSamples()
        if (samples.isEmpty()) return pcmData

        var sumSquares = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sumSquares += value * value
        }
        val rms = sqrt(sumSquares / samples.size)
        if (rms <= 0.0) return pcmData

        val targetRms = FULL_SCALE * 10.0.pow(TARGET_RMS_DBFS / 20.0)
        val gain = (targetRms / rms).coerceIn(1.0, MAX_NORMALIZE_GAIN)
        if (gain <= 1.0) return pcmData

        val limit = FULL_SCALE * PEAK_CEILING
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (limit * tanh(samples[i] * gain / limit)).roundToShort()
        }
        return out.toPcmBytes()
    }

    fun resamplePcm16Mono(
        pcmData: ByteArray,
        inputSampleRate: Int,
        outputSampleRate: Int,
    ): ByteArray {
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(outputSampleRate > 0) { "outputSampleRate must be positive" }

        val samples = pcmData.toShortSamples()
        if (samples.isEmpty()) return ByteArray(0)
        if (inputSampleRate == outputSampleRate) return samples.toPcmBytes()

        val outputCount =
            max(1, (samples.size.toLong() * outputSampleRate / inputSampleRate).toInt())
        val output = ShortArray(outputCount)
        if (inputSampleRate > outputSampleRate) {
            downsample(samples, inputSampleRate, outputSampleRate, output)
        } else {
            upsample(samples, inputSampleRate, outputSampleRate, output)
        }
        return output.toPcmBytes()
    }

    fun trimSilence(
        pcmData: ByteArray,
        sampleRate: Int,
        vad: VoiceActivityDetector,
    ): ByteArray {
        val samples = pcmData.toShortSamples()
        if (samples.isEmpty()) return ByteArray(0)

        val frameSamples = max(1, sampleRate * FRAME_MS / 1000)
        val frames = samples.toPaddedFrames(frameSamples)
        val kept = SmoothedVad(vad).filter(frames)
        return kept.flattenSamples().toPcmBytes()
    }

    private fun downsample(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int,
        output: ShortArray,
    ) {
        val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()

        val cutoff = 0.45 / ratio
        for (i in output.indices) {
            val center = i * ratio
            val first = ceil(center - FIR_HALF).toInt()
            val last = floor(center + FIR_HALF).toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (j in first..last) {
                val distance = j - center
                val weight = sinc(2.0 * cutoff * distance) * hann(distance)
                acc += input[j.coerceIn(input.indices)] * weight
                weightSum += weight
            }
            output[i] = if (weightSum != 0.0) (acc / weightSum).roundToShort() else 0
        }
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val px = PI * x
        return sin(px) / px
    }

    private fun hann(distance: Double): Double {
        if (distance <= -FIR_HALF || distance >= FIR_HALF) return 0.0
        return 0.5 + 0.5 * cos(PI * distance / FIR_HALF)
    }

    private fun upsample(
        input: ShortArray,
        inputSampleRate: Int,
        outputSampleRate: Int,
        output: ShortArray,
    ) {
        val ratio = inputSampleRate.toDouble() / outputSampleRate.toDouble()
        for (i in output.indices) {
            val pos = i * ratio
            val left = floor(pos).toInt().coerceIn(input.indices)
            val right = min(input.lastIndex, left + 1)
            val fraction = pos - left
            output[i] = (input[left] * (1.0 - fraction) + input[right] * fraction).roundToShort()
        }
    }

    private fun padShortRecording(pcmData: ByteArray): ByteArray {
        val sampleCount = pcmData.size / BYTES_PER_SAMPLE
        if (sampleCount == 0 || sampleCount >= MIN_REMOTE_ASR_SAMPLES) return pcmData

        val padded = pcmData.copyOf(SHORT_RECORDING_PAD_SAMPLES * BYTES_PER_SAMPLE)
        return padded
    }

    private fun ShortArray.toPaddedFrames(frameSamples: Int): List<ShortArray> {
        val frames = ArrayList<ShortArray>((size + frameSamples - 1) / frameSamples)
        var start = 0
        while (start < size) {
            val end = min(size, start + frameSamples)
            val frame = ShortArray(frameSamples)
            copyInto(frame, endIndex = end, startIndex = start)
            frames += frame
            start = end
        }
        return frames
    }

    private fun List<ShortArray>.flattenSamples(): ShortArray {
        val total = sumOf { it.size }
        val samples = ShortArray(total)
        var offset = 0
        for (frame in this) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        return samples
    }

    private fun ByteArray.toShortSamples(): ShortArray {
        val sampleCount = size / BYTES_PER_SAMPLE
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = this[i * 2].toInt() and 0xFF
            val hi = this[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    private fun ShortArray.toPcmBytes(): ByteArray {
        val bytes = ByteArray(size * BYTES_PER_SAMPLE)
        for (i in indices) {
            val value = this[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun Double.roundToShort(): Short =
        roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
