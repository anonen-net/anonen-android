package net.anonen.app.record

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class StreamingDownsampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private val ratio = inputRate.toDouble() / outputRate.toDouble()
    private val cutoff = 0.45 / ratio

    private var inputConsumed = 0L
    private var outputProduced = 0L
    private val carry = ShortArray(FIR_HALF * 2)
    private var carryLen = 0

    val isPassthrough: Boolean get() = inputRate == outputRate

    fun process(input: ShortArray): ShortArray {
        if (isPassthrough) return input
        if (input.isEmpty()) return ShortArray(0)

        val combined = ShortArray(carryLen + input.size)
        carry.copyInto(combined, 0, 0, carryLen)
        input.copyInto(combined, carryLen)
        val combinedStart = inputConsumed - carryLen

        val maxCount = (input.size.toDouble() / ratio + 2).toInt()
        val output = ShortArray(maxCount)
        var outIdx = 0

        while (true) {
            val center = (outputProduced + outIdx) * ratio
            val localCenter = center - combinedStart
            if (localCenter + FIR_HALF >= combined.size) break
            val first = ceil(localCenter - FIR_HALF).toInt()
            val last = floor(localCenter + FIR_HALF).toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (j in first..last) {
                val distance = j - localCenter
                val weight = sinc(2.0 * cutoff * distance) * hann(distance)
                acc += combined[j.coerceIn(0, combined.lastIndex)] * weight
                weightSum += weight
            }
            output[outIdx] = if (weightSum != 0.0) (acc / weightSum).roundToShort() else 0
            outIdx++
        }

        outputProduced += outIdx
        inputConsumed += input.size
        val newCarryLen = min(combined.size, FIR_HALF * 2)
        combined.copyInto(carry, 0, combined.size - newCarryLen, combined.size)
        carryLen = newCarryLen

        return output.copyOf(outIdx)
    }

    fun finish(): ShortArray {
        if (isPassthrough || carryLen == 0) return ShortArray(0)
        val expectedTotal = max(1L, inputConsumed * outputRate / inputRate)
        val remaining = (expectedTotal - outputProduced).toInt().coerceAtLeast(0)
        if (remaining == 0) return ShortArray(0)
        val combinedStart = inputConsumed - carryLen
        val output = ShortArray(remaining)
        for (i in 0 until remaining) {
            val center = (outputProduced + i) * ratio
            val localCenter = center - combinedStart.toDouble()
            val first = ceil(localCenter - FIR_HALF).toInt()
            val last = floor(localCenter + FIR_HALF).toInt()
            var acc = 0.0
            var weightSum = 0.0
            for (j in first..last) {
                val distance = j - localCenter
                val weight = sinc(2.0 * cutoff * distance) * hann(distance)
                acc += carry[j.coerceIn(0, carryLen - 1)] * weight
                weightSum += weight
            }
            output[i] = if (weightSum != 0.0) (acc / weightSum).roundToShort() else 0
        }
        outputProduced += remaining
        return output
    }

    companion object {
        private const val FIR_HALF = 16

        private fun sinc(x: Double): Double {
            if (x == 0.0) return 1.0
            val px = PI * x
            return sin(px) / px
        }

        private fun hann(distance: Double): Double {
            if (distance <= -FIR_HALF || distance >= FIR_HALF) return 0.0
            return 0.5 + 0.5 * cos(PI * distance / FIR_HALF)
        }

        private fun Double.roundToShort(): Short =
            roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
