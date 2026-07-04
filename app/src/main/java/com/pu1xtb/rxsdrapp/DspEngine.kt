package com.pu1xtb.rxsdrapp

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor DSP: recebe blocos I/Q u8 do rtl_tcp, gera espectro (FFT),
 * demodula (AM/USB/LSB/CW/NFM/WFM) e toca o audio a 32 kHz.
 */
class DspEngine(private val ui: Ui) {

    interface Ui {
        fun onSpectrum(bins: ByteArray, centerHz: Long, sampleRate: Int, vfoHz: Long)
    }

    companion object {
        const val AUDIO_RATE = 32000
        const val FFT_SIZE = 4096
        const val SPEC_BINS = 2048
    }

    // ----- parametros controlados pela UI -----
    @Volatile var vfoHz: Long = 93_700_000L
    @Volatile var centerHz: Long = 93_950_000L
    @Volatile var mode: String = "WFM"
    @Volatile var bandwidthHz: Int = 180_000
    @Volatile var squelchDb: Float = -150f
    @Volatile var volume: Float = 1.0f
    @Volatile var calDb: Float = -30f
    @Volatile var sampleRate: Int = 1_024_000
    @Volatile var fftFps: Int = 15
    @Volatile private var dirty = true

    // ----- medidores lidos pela UI -----
    @Volatile var smeterDbm: Float = -130f
    @Volatile var squelchOpen: Boolean = true
    @Volatile var nrLevel: Float = 0f

    val audio = AudioSink(AUDIO_RATE)
    private val nr = NoiseReducer()

    private val queue = ArrayBlockingQueue<ByteArray>(16)
    private val pool = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var running = false
    private var thread: Thread? = null

    private val lut = FloatArray(256) { (it - 127.4f) / 128f }

    // ----- buffers de trabalho (usados so pela thread DSP) -----
    private var iBuf = FloatArray(16384)
    private var qBuf = FloatArray(16384)
    private var m1I = FloatArray(4096)
    private var m1Q = FloatArray(4096)
    private var m2I = FloatArray(1024)
    private var m2Q = FloatArray(1024)
    private var chI = FloatArray(1024)
    private var chQ = FloatArray(1024)
    private var audioBuf = FloatArray(1024)
    private var fmWork = FloatArray(4096)

    private val fft = Fft(FFT_SIZE)
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE)
    private val specU8 = ByteArray(SPEC_BINS)
    private val specDb = FloatArray(SPEC_BINS)
    private var specInit = false
    private var fftDbOffset = 0f
    private var lastFftMs = 0L

    // filtros / estado de demodulacao
    private var dec1: FirDecimator? = null
    private var dec2: FirDecimator? = null
    private var chanFilter: FirComplex? = null
    private var fmAudioDec: RealDecimator? = null
    private var isWfm = false
    private var fmGain = 1f
    private var mixLastOffset = Long.MIN_VALUE
    private var mixC = 1f
    private var mixS = 0f
    private var mixStepC = 1f
    private var mixStepS = 0f
    private var dcI = 0f
    private var dcQ = 0f
    private var amDc = 0f
    private var agcEnv = 0.01f
    private var prevI = 0f
    private var prevQ = 0f
    private var bfoPhase = 0.0
    private var deemph = 0f
    private var deemphA = 0.34f
    private var sqOpen = true

    init {
        // janela Blackman-Harris de 4 termos
        val n = FFT_SIZE
        var coherentGain = 0.0
        for (i in 0 until n) {
            val a = 2.0 * PI * i / (n - 1)
            val w = 0.35875 - 0.48829 * cos(a) + 0.14128 * cos(2 * a) - 0.01168 * cos(3 * a)
            window[i] = w.toFloat()
            coherentGain += w
        }
        // normalizacao: seno em escala cheia -> 0 dBFS
        fftDbOffset = (-20.0 * log10(coherentGain / 2.0)).toFloat()
    }

    fun start() {
        if (running) return
        running = true
        dirty = true
        audio.start()
        thread = Thread({ loop() }, "dsp").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(700) } catch (_: InterruptedException) {} }
        thread = null
        audio.stop()
        queue.clear()
        smeterDbm = -130f
    }

    fun markDirty() { dirty = true }

    /** Chamado pela thread de rede; nunca bloqueia (descarta o bloco mais antigo). */
    fun submit(buf: ByteArray, len: Int) {
        var b = pool.poll()
        if (b == null || b.size != len) b = ByteArray(len)
        System.arraycopy(buf, 0, b, 0, len)
        if (!queue.offer(b)) {
            val old = queue.poll()
            if (old != null) pool.offer(old)
            queue.offer(b)
        }
    }

    private fun loop() {
        while (running) {
            val blk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            try {
                process(blk, blk.size)
            } catch (_: Exception) {
                // nunca derruba a thread DSP por causa de um bloco
            }
            if (pool.size < 20) pool.offer(blk)
        }
    }

    private fun rebuild() {
        val fs = sampleRate.toDouble()
        isWfm = (mode == "WFM")
        val bw = bandwidthHz.coerceIn(200, 250_000)
        if (isWfm) {
            val f1 = (sampleRate / 256_000).coerceAtLeast(1)   // 1.024M->4, 2.048M->8
            dec1 = FirDecimator(DspUtil.lowpassTaps(96, fs, 95_000.0), f1)
            fmAudioDec = RealDecimator(DspUtil.lowpassTaps(160, 256_000.0, 12_000.0), 8)
            dec2 = null
            chanFilter = null
            fmGain = (256_000.0 / (2.0 * PI * 75_000.0)).toFloat()
            deemphA = (1.0 - Math.exp(-1.0 / (AUDIO_RATE * 75e-6))).toFloat()
        } else {
            val f2 = (sampleRate / (8 * AUDIO_RATE)).coerceAtLeast(1) // 1.024M->4, 2.048M->8
            val midRate = fs / 8.0
            dec1 = FirDecimator(DspUtil.lowpassTaps(64, fs, 24_000.0), 8)
            dec2 = FirDecimator(DspUtil.lowpassTaps(176, midRate, 14_000.0), f2)
            fmAudioDec = null
            val center = when (mode) {
                "USB" -> bw / 2.0
                "LSB" -> -bw / 2.0
                else -> 0.0
            }
            val width = if (mode == "CW") maxOf(bw, 200).toDouble() else bw.toDouble()
            chanFilter = FirComplex(129, AUDIO_RATE.toDouble(), center, width)
            val dev = maxOf(bw * 0.35, 1500.0)
            fmGain = (AUDIO_RATE / (2.0 * PI * dev)).toFloat()
        }
        nr.reset()
        agcEnv = 0.01f
        amDc = 0f
        deemph = 0f
        prevI = 0f; prevQ = 0f
        mixLastOffset = Long.MIN_VALUE
        dirty = false
    }

    private fun process(blk: ByteArray, lenBytes: Int) {
        if (dirty) rebuild()
        val n = lenBytes / 2
        if (iBuf.size < n) { iBuf = FloatArray(n); qBuf = FloatArray(n) }
        // u8 -> float e remocao de DC (passa-alta lenta)
        var di = dcI; var dq = dcQ
        for (k in 0 until n) {
            var iv = lut[blk[2 * k].toInt() and 0xFF]
            var qv = lut[blk[2 * k + 1].toInt() and 0xFF]
            di += 0.00005f * (iv - di)
            dq += 0.00005f * (qv - dq)
            iv -= di; qv -= dq
            iBuf[k] = iv; qBuf[k] = qv
        }
        dcI = di; dcQ = dq

        // ----- espectro -----
        val now = System.currentTimeMillis()
        val interval = 1000L / fftFps.coerceIn(5, 30)
        if (now - lastFftMs >= interval && n >= FFT_SIZE) {
            lastFftMs = now
            val off = n - FFT_SIZE
            for (k in 0 until FFT_SIZE) {
                fftRe[k] = iBuf[off + k] * window[k]
                fftIm[k] = qBuf[off + k] * window[k]
            }
            fft.transform(fftRe, fftIm)
            val half = FFT_SIZE / 2
            for (b in 0 until SPEC_BINS) {
                // fftshift + reducao 4096 -> 2048 pegando o maior dos pares
                val src = (b * 2 + half) and (FFT_SIZE - 1)
                val src2 = (src + 1) and (FFT_SIZE - 1)
                val p1 = fftRe[src] * fftRe[src] + fftIm[src] * fftIm[src]
                val p2 = fftRe[src2] * fftRe[src2] + fftIm[src2] * fftIm[src2]
                val p = if (p1 > p2) p1 else p2
                var db = 10f * log10(p + 1e-12f) + fftDbOffset
                // suavizacao temporal leve
                db = if (specInit) specDb[b] * 0.5f + db * 0.5f else db
                specDb[b] = db
                var v = ((db + 130f) * (255f / 90f)).toInt()
                if (v < 0) v = 0
                if (v > 255) v = 255
                specU8[b] = v.toByte()
            }
            specInit = true
            ui.onSpectrum(specU8.copyOf(), centerHz, sampleRate, vfoHz)
        }

        // ----- mixer (translada o VFO para 0 Hz) -----
        val offset = vfoHz - centerHz
        if (offset != mixLastOffset) {
            mixLastOffset = offset
            val w = -2.0 * PI * offset / sampleRate
            mixStepC = cos(w).toFloat()
            mixStepS = sin(w).toFloat()
        }
        var c = mixC; var s = mixS
        for (k in 0 until n) {
            val iv = iBuf[k]; val qv = qBuf[k]
            iBuf[k] = iv * c - qv * s
            qBuf[k] = iv * s + qv * c
            val nc = c * mixStepC - s * mixStepS
            s = c * mixStepS + s * mixStepC
            c = nc
        }
        // renormaliza o oscilador
        val mag = sqrt(c * c + s * s)
        mixC = c / mag; mixS = s / mag

        val d1 = dec1 ?: return
        if (m1I.size < n) { m1I = FloatArray(n); m1Q = FloatArray(n) }
        val n1 = d1.process(iBuf, qBuf, n, m1I, m1Q)

        var audioN: Int
        if (isWfm) {
            // discriminador FM a 256 kHz
            if (fmWork.size < n1) fmWork = FloatArray(n1)
            var pi2 = prevI; var pq2 = prevQ
            for (k in 0 until n1) {
                val ci = m1I[k]; val cq = m1Q[k]
                val dr = ci * pi2 + cq * pq2
                val dj = cq * pi2 - ci * pq2
                fmWork[k] = atan2(dj, dr) * fmGain
                pi2 = ci; pq2 = cq
            }
            prevI = pi2; prevQ = pq2
            // potencia do canal (S-meter)
            var pw = 0f
            for (k in 0 until n1) pw += m1I[k] * m1I[k] + m1Q[k] * m1Q[k]
            updateSmeter(pw / n1)
            if (audioBuf.size < n1 / 8 + 2) audioBuf = FloatArray(n1 / 8 + 2)
            audioN = fmAudioDec!!.process(fmWork, n1, audioBuf)
            // de-enfase 75us
            var de = deemph
            for (k in 0 until audioN) {
                de += deemphA * (audioBuf[k] - de)
                audioBuf[k] = de * 1.4f
            }
            deemph = de
        } else {
            val d2 = dec2 ?: return
            if (m2I.size < n1) { m2I = FloatArray(n1); m2Q = FloatArray(n1) }
            val n2 = d2.process(m1I, m1Q, n1, m2I, m2Q)
            if (chI.size < n2) { chI = FloatArray(n2); chQ = FloatArray(n2) }
            chanFilter?.process(m2I, m2Q, n2, chI, chQ) ?: return
            var pw = 0f
            for (k in 0 until n2) pw += chI[k] * chI[k] + chQ[k] * chQ[k]
            updateSmeter(if (n2 > 0) pw / n2 else 0f)
            if (audioBuf.size < n2) audioBuf = FloatArray(n2)
            audioN = n2
            when (mode) {
                "AM" -> {
                    var dc = amDc
                    for (k in 0 until n2) {
                        val m = sqrt(chI[k] * chI[k] + chQ[k] * chQ[k])
                        dc += 0.002f * (m - dc)
                        audioBuf[k] = m - dc
                    }
                    amDc = dc
                    applyAgc(audioN)
                }
                "NFM" -> {
                    var pi2 = prevI; var pq2 = prevQ
                    for (k in 0 until n2) {
                        val ci = chI[k]; val cq = chQ[k]
                        val dr = ci * pi2 + cq * pq2
                        val dj = cq * pi2 - ci * pq2
                        audioBuf[k] = atan2(dj, dr) * fmGain * 1.3f
                        pi2 = ci; pq2 = cq
                    }
                    prevI = pi2; prevQ = pq2
                }
                "USB", "LSB" -> {
                    for (k in 0 until n2) audioBuf[k] = chI[k] * 2f
                    applyAgc(audioN)
                }
                "CW" -> {
                    var ph = bfoPhase
                    val w = 2.0 * PI * 700.0 / AUDIO_RATE
                    for (k in 0 until n2) {
                        audioBuf[k] = (chI[k] * cos(ph) - chQ[k] * sin(ph)).toFloat() * 2f
                        ph += w
                        if (ph > 2.0 * PI) ph -= 2.0 * PI
                    }
                    bfoPhase = ph
                    applyAgc(audioN)
                }
                else -> {
                    for (k in 0 until n2) audioBuf[k] = 0f
                }
            }
        }

        // ----- reducao de ruido (HF: AM/SSB/CW/NFM) -----
        if (!isWfm && nrLevel > 0.01f) {
            nr.level = nrLevel
            nr.process(audioBuf, audioN)
        }

        // ----- squelch -----
        if (squelchDb > -149f) {
            if (sqOpen && smeterDbm < squelchDb - 1f) sqOpen = false
            else if (!sqOpen && smeterDbm > squelchDb + 1f) sqOpen = true
        } else sqOpen = true
        squelchOpen = sqOpen
        if (!sqOpen) java.util.Arrays.fill(audioBuf, 0, audioN, 0f)

        audio.write(audioBuf, audioN, volume)
    }

    private fun updateSmeter(power: Float) {
        val db = 10f * log10(power + 1e-13f) + calDb
        // suavizacao: ataque rapido, queda lenta
        smeterDbm = if (db > smeterDbm) smeterDbm * 0.5f + db * 0.5f
                    else smeterDbm * 0.85f + db * 0.15f
    }

    private fun applyAgc(len: Int) {
        var env = agcEnv
        for (k in 0 until len) {
            val a = abs(audioBuf[k])
            env = if (a > env) a else env * 0.99985f
            if (env < 5e-4f) env = 5e-4f
            audioBuf[k] = audioBuf[k] * (0.55f / env)
        }
        agcEnv = env
    }
}
