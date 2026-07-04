package com.pu1xtb.rxsdrapp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** FFT radix-2 iterativa (Cooley-Tukey), in-place. n deve ser potencia de 2. */
class Fft(private val n: Int) {
    private val levels: Int = 31 - Integer.numberOfLeadingZeros(n)
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(Integer.bitCount(n) == 1) { "FFT: n deve ser potencia de 2" }
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n).toFloat()
            sinTable[i] = sin(2.0 * PI * i / n).toFloat()
        }
        for (i in 0 until n) {
            rev[i] = Integer.reverse(i) ushr (32 - levels)
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        // permutacao bit-reversal
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val tpre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tpim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
