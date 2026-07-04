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
        val bufSize = maxOf(minBuf * 3, 16384)
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
        t.write(pcm, 0, len)
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
