package com.pu1xtb.rxsdrapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.math.sqrt

/** Saida de audio 32 kHz mono via AudioTrack. */
class AudioSink(private val sampleRate: Int = 32000) {
    private var track: AudioTrack? = null
    private var pcm = ShortArray(8192)
    @Volatile var level: Float = 0f   // RMS 0..1 para o medidor
        private set

    fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Buffer maior: capacidade extra nao aumenta a latencia do audio
        // analogico (o nivel de enchimento e que manda), mas da folga para as
        // rajadas de voz DMR do dsd-fme sem descartar amostras (picote).
        val bufSize = maxOf(minBuf * 4, 65536)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.play()
    }

    @Synchronized
    fun write(samples: FloatArray, len: Int, volume: Float) {
        val t = track ?: return
        if (pcm.size < len) pcm = ShortArray(len)
        var sumSq = 0f
        for (i in 0 until len) {
            // limitador suave: permite mais volume sem "serrar" o audio
            val v = Math.tanh((samples[i] * volume).toDouble()).toFloat()
            sumSq += v * v
            pcm[i] = (v * 32600f).toInt().toShort()
        }
        if (len > 0) level = sqrt(sumSq / len)
        // WRITE_NON_BLOCKING: se o AudioTrack momentaneamente nao consegue drenar
        // (troca de rota de audio, foco, hiccup do HAL), essa chamada NAO trava.
        // Isso e essencial no caminho do DMR: quem chama write() aqui e a mesma
        // thread que le os pacotes UDP do dsd-fme - se ela travar mesmo que por
        // pouco tempo, o buffer UDP do SO enche e derruba pacotes = audio picotado.
        // Um pequeno corte ocasional de amostra e muito melhor que travar a thread.
        t.write(pcm, 0, len, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun stop() {
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {}
        track = null
        level = 0f
    }
}
