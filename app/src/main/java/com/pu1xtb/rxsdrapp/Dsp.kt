package com.pu1xtb.rxsdrapp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Utilitarios DSP: projeto de filtros FIR e decimadores. */
object DspUtil {
    /** FIR passa-baixa (sinc janelado com Blackman). cutoff em Hz, fs em Hz. */
    fun lowpassTaps(numTaps: Int, fs: Double, cutoff: Double): FloatArray {
        val taps = FloatArray(numTaps)
        val fc = cutoff / fs
        val m = numTaps - 1
        var sum = 0.0
        for (i in 0 until numTaps) {
            val x = i - m / 2.0
            val sinc = if (x == 0.0) 2.0 * PI * fc else sin(2.0 * PI * fc * x) / x
            val win = 0.42 - 0.5 * cos(2.0 * PI * i / m) + 0.08 * cos(4.0 * PI * i / m)
            val v = sinc * win
            taps[i] = v.toFloat()
            sum += v
        }
        // normaliza ganho DC = 1
        for (i in 0 until numTaps) taps[i] = (taps[i] / sum).toFloat()
        return taps
    }
}

/** Decimador FIR complexo (I/Q) com linha de atraso interna. */
class FirDecimator(private val taps: FloatArray, private val factor: Int) {
    private val hist = taps.size - 1
    private var bufI = FloatArray(0)
    private var bufQ = FloatArray(0)
    private val histI = FloatArray(hist)
    private val histQ = FloatArray(hist)
    private var phase = 0  // amostras de entrada ate a proxima saida

    fun process(inI: FloatArray, inQ: FloatArray, len: Int, outI: FloatArray, outQ: FloatArray): Int {
        val total = hist + len
        if (bufI.size < total) { bufI = FloatArray(total); bufQ = FloatArray(total) }
        System.arraycopy(histI, 0, bufI, 0, hist)
        System.arraycopy(histQ, 0, bufQ, 0, hist)
        System.arraycopy(inI, 0, bufI, hist, len)
        System.arraycopy(inQ, 0, bufQ, hist, len)
        var outN = 0
        var i = phase
        val nt = taps.size
        while (i < len) {
            // saida no instante de entrada i -> janela bufI[i .. i+nt-1]
            var accI = 0f
            var accQ = 0f
            val base = i
            for (k in 0 until nt) {
                val t = taps[k]
                accI += t * bufI[base + k]
                accQ += t * bufQ[base + k]
            }
            outI[outN] = accI
            outQ[outN] = accQ
            outN++
            i += factor
        }
        phase = i - len
        // guarda historico (ultimas hist amostras)
        System.arraycopy(bufI, len, histI, 0, hist)
        System.arraycopy(bufQ, len, histQ, 0, hist)
        return outN
    }

    fun reset() {
        java.util.Arrays.fill(histI, 0f)
        java.util.Arrays.fill(histQ, 0f)
        phase = 0
    }
}

/** Decimador FIR real (para audio FM). */
class RealDecimator(private val taps: FloatArray, private val factor: Int) {
    private val hist = taps.size - 1
    private var buf = FloatArray(0)
    private val histBuf = FloatArray(hist)
    private var phase = 0

    fun process(inp: FloatArray, len: Int, out: FloatArray): Int {
        val total = hist + len
        if (buf.size < total) buf = FloatArray(total)
        System.arraycopy(histBuf, 0, buf, 0, hist)
        System.arraycopy(inp, 0, buf, hist, len)
        var outN = 0
        var i = phase
        val nt = taps.size
        while (i < len) {
            var acc = 0f
            val base = i
            for (k in 0 until nt) acc += taps[k] * buf[base + k]
            out[outN++] = acc
            i += factor
        }
        phase = i - len
        System.arraycopy(buf, len, histBuf, 0, hist)
        return outN
    }

    fun reset() {
        java.util.Arrays.fill(histBuf, 0f)
        phase = 0
    }
}

/** Filtro FIR complexo (passa-banda por deslocamento de passa-baixa), sem decimacao. */
class FirComplex(numTaps: Int, fs: Double, centerHz: Double, widthHz: Double) {
    private val tapsRe = FloatArray(numTaps)
    private val tapsIm = FloatArray(numTaps)
    private val isReal: Boolean
    private val hist = numTaps - 1
    private var bufI = FloatArray(0)
    private var bufQ = FloatArray(0)
    private val histI = FloatArray(hist)
    private val histQ = FloatArray(hist)

    init {
        val lp = DspUtil.lowpassTaps(numTaps, fs, widthHz / 2.0)
        if (centerHz == 0.0) {
            for (i in 0 until numTaps) { tapsRe[i] = lp[i]; tapsIm[i] = 0f }
            isReal = true
        } else {
            // Obs: o laco de filtragem percorre os taps em forma de correlacao,
            // por isso o deslocamento usa -w para a banda cair no lado correto.
            val w = 2.0 * PI * centerHz / fs
            for (i in 0 until numTaps) {
                tapsRe[i] = (lp[i] * cos(w * i)).toFloat()
                tapsIm[i] = (-lp[i] * sin(w * i)).toFloat()
            }
            isReal = false
        }
    }

    fun process(inI: FloatArray, inQ: FloatArray, len: Int, outI: FloatArray, outQ: FloatArray) {
        val total = hist + len
        if (bufI.size < total) { bufI = FloatArray(total); bufQ = FloatArray(total) }
        System.arraycopy(histI, 0, bufI, 0, hist)
        System.arraycopy(histQ, 0, bufQ, 0, hist)
        System.arraycopy(inI, 0, bufI, hist, len)
        System.arraycopy(inQ, 0, bufQ, hist, len)
        val nt = tapsRe.size
        if (isReal) {
            for (n in 0 until len) {
                var accI = 0f
                var accQ = 0f
                for (k in 0 until nt) {
                    val t = tapsRe[k]
                    accI += t * bufI[n + k]
                    accQ += t * bufQ[n + k]
                }
                outI[n] = accI
                outQ[n] = accQ
            }
        } else {
            for (n in 0 until len) {
                var accI = 0f
                var accQ = 0f
                for (k in 0 until nt) {
                    val tr = tapsRe[k]
                    val ti = tapsIm[k]
                    val xr = bufI[n + k]
                    val xq = bufQ[n + k]
                    accI += tr * xr - ti * xq
                    accQ += tr * xq + ti * xr
                }
                outI[n] = accI
                outQ[n] = accQ
            }
        }
        System.arraycopy(bufI, len, histI, 0, hist)
        System.arraycopy(bufQ, len, histQ, 0, hist)
    }
}
