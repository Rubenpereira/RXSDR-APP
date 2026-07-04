package com.pu1xtb.rxsdrapp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Redutor de ruido (NR) por subtracao espectral para HF.
 * FFT de 512 pontos, janela Hann, sobreposicao de 50% (COLA=1),
 * estimador de ruido por rastreamento de minimos e suavizacao
 * temporal dos ganhos para reduzir "musical noise".
 * Latencia: 256 amostras (8 ms a 32 kHz).
 */
class NoiseReducer {
    companion object {
        const val N = 512
        const val HOP = N / 2
    }

    private val fft = Fft(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / N)).toFloat() }
    private val frame = FloatArray(N)
    private val olaTail = FloatArray(HOP)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val noise = FloatArray(N / 2 + 1)
    private val smGain = FloatArray(N / 2 + 1) { 1f }

    /** Intensidade 0..1 (0 = desligado). */
    @Volatile var level: Float = 0f

    fun reset() {
        java.util.Arrays.fill(frame, 0f)
        java.util.Arrays.fill(olaTail, 0f)
        java.util.Arrays.fill(noise, 0f)
        java.util.Arrays.fill(smGain, 1f)
    }

    /** Processa in-place. len deve ser multiplo de 256 (512/256 no fluxo do app). */
    fun process(buf: FloatArray, len: Int) {
        val lv = level
        if (lv < 0.01f) return
        val k = 1.0f + 2.5f * lv                    // agressividade da subtracao
        val floor = (0.4f * (1f - lv)).coerceAtLeast(0.03f)  // ganho minimo por bin
        var pos = 0
        while (pos + HOP <= len) {
            // desloca o frame e entra meio frame novo
            System.arraycopy(frame, HOP, frame, 0, N - HOP)
            System.arraycopy(buf, pos, frame, N - HOP, HOP)
            for (i in 0 until N) {
                re[i] = frame[i] * win[i]
                im[i] = 0f
            }
            fft.transform(re, im)
            // ganho por bin (0..N/2), espelhado na parte negativa
            for (b in 0..N / 2) {
                val m = sqrt(re[b] * re[b] + im[b] * im[b])
                val nz = noise[b]
                // estimador de duas velocidades: desce rapido (pausas do sinal),
                // sobe devagar (nao confunde sinal continuo com ruido)
                noise[b] = if (m > nz) nz + 0.002f * (m - nz) else nz + 0.3f * (m - nz)
                var g = 1f - k * (noise[b] / (m + 1e-9f))
                if (g < floor) g = floor
                smGain[b] = smGain[b] * 0.6f + g * 0.4f
            }
            for (b in 0..N / 2) {
                val g = smGain[b]
                re[b] *= g
                im[b] *= g
                if (b in 1 until N / 2) {
                    re[N - b] *= g
                    im[N - b] *= g
                }
            }
            // IFFT via conjugado
            for (i in 0 until N) im[i] = -im[i]
            fft.transform(re, im)
            val inv = 1f / N
            for (i in 0 until HOP) {
                buf[pos + i] = olaTail[i] + re[i] * inv
                olaTail[i] = re[HOP + i] * inv
            }
            pos += HOP
        }
    }
}
