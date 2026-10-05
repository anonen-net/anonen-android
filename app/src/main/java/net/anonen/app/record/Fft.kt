package net.anonen.app.record

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object Fft {
    fun transform(
        re: DoubleArray,
        im: DoubleArray,
        inverse: Boolean,
    ) {
        val n = re.size
        require(n and (n - 1) == 0) { "size must be a power of two: $n" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val bR = re[b] * curR - im[b] * curI
                    val bI = re[b] * curI + im[b] * curR
                    re[b] = re[a] - bR
                    im[b] = im[a] - bI
                    re[a] += bR
                    im[a] += bI
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}
