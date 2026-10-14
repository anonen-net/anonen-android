package net.anonen.app.record

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SpectralMatchEq(
    private val target: DoubleArray = DEFAULT_TARGET,
) {
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / N) }

    fun process(samples: ShortArray): ShortArray {
        if (samples.size < N) return samples
        val gain = computeGain(samples)
        return wolaFilter(samples, gain)
    }

    private fun computeGain(x: ShortArray): DoubleArray {
        val own = smoothLog(ltas(x))
        var maxV = 0.0
        for (v in own) if (v > maxV) maxV = v
        if (maxV <= 0.0) return DoubleArray(BINS) { 1.0 }
        val minGain = dbToLinear(MIN_GAIN_DB)
        val maxGain = dbToLinear(MAX_GAIN_DB)
        return DoubleArray(BINS) { k ->
            (target[k] / (own[k] / maxV + EPS)).coerceIn(minGain, maxGain)
        }
    }

    private fun ltas(x: ShortArray): DoubleArray {
        val voiced = voicedConcat(x)
        val psd = DoubleArray(BINS)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var count = 0
        var i = 0
        while (i + N <= voiced.size) {
            for (k in 0 until N) {
                re[k] = voiced[i + k] * window[k]
                im[k] = 0.0
            }
            Fft.transform(re, im, inverse = false)
            for (k in 0 until BINS) psd[k] += re[k] * re[k] + im[k] * im[k]
            count++
            i += HOP
        }
        if (count > 0) for (k in psd.indices) psd[k] /= count
        return DoubleArray(BINS) { sqrt(psd[it]) }
    }

    private fun voicedConcat(x: ShortArray): ShortArray {
        val frameLen = TARGET_SAMPLE_RATE * 30 / 1000
        val frameCount = x.size / frameLen
        if (frameCount == 0) return x
        val rms = DoubleArray(frameCount)
        var maxRms = 0.0
        for (f in 0 until frameCount) {
            var sum = 0.0
            val base = f * frameLen
            for (k in 0 until frameLen) {
                val v = x[base + k].toDouble()
                sum += v * v
            }
            rms[f] = sqrt(sum / frameLen)
            if (rms[f] > maxRms) maxRms = rms[f]
        }
        val thr = maxOf(maxRms / 8.0, FULL_SCALE * dbToLinear(-50.0))
        var voicedFrames = 0
        for (f in 0 until frameCount) if (rms[f] >= thr) voicedFrames++
        if (voicedFrames * frameLen < N) return x
        val out = ShortArray(voicedFrames * frameLen)
        var pos = 0
        for (f in 0 until frameCount) {
            if (rms[f] >= thr) {
                val base = f * frameLen
                x.copyInto(out, destinationOffset = pos, startIndex = base, endIndex = base + frameLen)
                pos += frameLen
            }
        }
        return out
    }

    private fun smoothLog(y: DoubleArray): DoubleArray {
        val out = DoubleArray(BINS)
        val lo = OCT_FRAC_DOWN
        val hi = OCT_FRAC_UP
        for (i in 0 until BINS) {
            if (i == 0) {
                out[0] = y[0]
                continue
            }
            val fLo = i * lo
            val fHi = i * hi
            var sum = 0.0
            var n = 0
            var k = maxOf(1, kotlin.math.floor(fLo).toInt())
            val kEnd = minOf(BINS - 1, kotlin.math.ceil(fHi).toInt())
            while (k <= kEnd) {
                sum += y[k]
                n++
                k++
            }
            out[i] = if (n > 0) sum / n else y[i]
        }
        return out
    }

    private fun wolaFilter(
        x: ShortArray,
        gain: DoubleArray,
    ): ShortArray {
        val gainFull = DoubleArray(N) { k -> if (k <= N / 2) gain[k] else gain[N - k] }
        val out = ShortArray(x.size)
        val acc = DoubleArray(N)
        val norm = DoubleArray(N)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var i = 0
        while (i < x.size) {
            for (k in 0 until N) {
                val idx = i + k
                re[k] = if (idx < x.size) x[idx] * window[k] else 0.0
                im[k] = 0.0
            }
            Fft.transform(re, im, inverse = false)
            for (k in 0 until N) {
                re[k] *= gainFull[k]
                im[k] *= gainFull[k]
            }
            Fft.transform(re, im, inverse = true)

            for (k in 0 until N) {
                acc[k] += re[k] * window[k]
                norm[k] += window[k] * window[k]
            }
            for (k in 0 until HOP) {
                val idx = i + k
                if (idx >= x.size) break
                val y = if (norm[k] > 1e-9) acc[k] / norm[k] else acc[k]
                out[idx] = y.roundToShortValue()
            }
            acc.copyInto(acc, destinationOffset = 0, startIndex = HOP, endIndex = N)
            acc.fill(0.0, fromIndex = N - HOP, toIndex = N)
            norm.copyInto(norm, destinationOffset = 0, startIndex = HOP, endIndex = N)
            norm.fill(0.0, fromIndex = N - HOP, toIndex = N)
            i += HOP
        }
        return out
    }

    private fun dbToLinear(db: Double): Double = Math.exp(db / 20.0 * LN10)

    private fun Double.roundToShortValue(): Short =
        roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    companion object {
        const val N = 512
        private const val HOP = 256
        private const val BINS = N / 2 + 1
        private const val TARGET_SAMPLE_RATE = 16000
        private const val FULL_SCALE = 32768.0
        private const val MIN_GAIN_DB = -12.0
        private const val MAX_GAIN_DB = 12.0
        private const val EPS = 1e-6
        private val LN10 = ln(10.0)
        private val OCT_FRAC_DOWN = Math.pow(2.0, -1.0 / 3.0)
        private val OCT_FRAC_UP = Math.pow(2.0, 1.0 / 3.0)

        private val DEFAULT_TARGET =
            doubleArrayOf(
                0.01248, 0.01460, 0.02706, 0.13919, 0.28310, 0.25344,
                0.26398, 0.26976, 0.34036, 0.56342, 0.82473, 1.00000,
                0.92364, 0.94955, 0.88018, 0.88262, 0.68724, 0.59792,
                0.63673, 0.66855, 0.59044, 0.55317, 0.49948, 0.42944,
                0.34237, 0.32460, 0.30052, 0.26415, 0.21633, 0.17498,
                0.17017, 0.15688, 0.15254, 0.14989, 0.15444, 0.15872,
                0.15625, 0.15248, 0.14924, 0.14148, 0.13741, 0.13457,
                0.12942, 0.11902, 0.11533, 0.11020, 0.10561, 0.09772,
                0.09142, 0.08923, 0.08477, 0.07739, 0.07060, 0.06246,
                0.06053, 0.05183, 0.04581, 0.04089, 0.03890, 0.03877,
                0.03742, 0.03528, 0.03430, 0.03402, 0.03407, 0.03356,
                0.03297, 0.03245, 0.03215, 0.03125, 0.03019, 0.03013,
                0.03008, 0.02965, 0.02968, 0.02953, 0.02924, 0.02838,
                0.02807, 0.02742, 0.02716, 0.02675, 0.02660, 0.02658,
                0.02650, 0.02600, 0.02550, 0.02503, 0.02494, 0.02439,
                0.02407, 0.02382, 0.02345, 0.02318, 0.02275, 0.02217,
                0.02183, 0.02192, 0.02176, 0.02130, 0.02077, 0.02062,
                0.02067, 0.02025, 0.01977, 0.01951, 0.01937, 0.01932,
                0.01895, 0.01874, 0.01855, 0.01836, 0.01806, 0.01793,
                0.01790, 0.01788, 0.01764, 0.01771, 0.01778, 0.01792,
                0.01809, 0.01817, 0.01829, 0.01840, 0.01864, 0.01886,
                0.01923, 0.01997, 0.02005, 0.02009, 0.02003, 0.01994,
                0.01975, 0.01958, 0.01947, 0.01938, 0.01936, 0.01930,
                0.01922, 0.01933, 0.01932, 0.01931, 0.01936, 0.01942,
                0.01933, 0.01921, 0.01907, 0.01872, 0.01856, 0.01840,
                0.01834, 0.01805, 0.01802, 0.01788, 0.01774, 0.01773,
                0.01777, 0.01774, 0.01761, 0.01754, 0.01756, 0.01747,
                0.01717, 0.01707, 0.01698, 0.01689, 0.01673, 0.01663,
                0.01653, 0.01640, 0.01616, 0.01609, 0.01610, 0.01612,
                0.01604, 0.01595, 0.01597, 0.01590, 0.01584, 0.01572,
                0.01561, 0.01545, 0.01539, 0.01531, 0.01519, 0.01499,
                0.01481, 0.01459, 0.01439, 0.01412, 0.01402, 0.01381,
                0.01360, 0.01326, 0.01315, 0.01289, 0.01266, 0.01231,
                0.01200, 0.01190, 0.01146, 0.01089, 0.01053, 0.01028,
                0.01020, 0.01011, 0.01002, 0.00992, 0.00986, 0.00986,
                0.00981, 0.00977, 0.00972, 0.00965, 0.00965, 0.00955,
                0.00945, 0.00937, 0.00930, 0.00930, 0.00912, 0.00894,
                0.00880, 0.00880, 0.00868, 0.00852, 0.00833, 0.00825,
                0.00825, 0.00823, 0.00819, 0.00818, 0.00818, 0.00818,
                0.00822, 0.00826, 0.00830, 0.00832, 0.00832, 0.00832,
                0.00833, 0.00832, 0.00827, 0.00827, 0.00817, 0.00799,
                0.00776, 0.00755, 0.00755, 0.00736, 0.00724, 0.00713,
                0.00695, 0.00695, 0.00672, 0.00648, 0.00638,
            )
    }
}
