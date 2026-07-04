package com.pu1xtb.rxsdrapp

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliente do protocolo rtl_tcp (o mesmo do driver Android "RTL-SDR driver"
 * marto.rtl_tcp_andro e do rtl_tcp de PC).
 */
class RtlTcpClient(private val listener: Listener) {

    interface Listener {
        fun onConnected(tunerName: String, gainCount: Int)
        fun onIq(buf: ByteArray, len: Int)
        fun onDisconnected(reason: String)
    }

    companion object {
        const val CMD_FREQ = 0x01
        const val CMD_SAMPLERATE = 0x02
        const val CMD_GAIN_MODE = 0x03
        const val CMD_GAIN = 0x04
        const val CMD_PPM = 0x05
        const val CMD_AGC = 0x08
        const val CMD_DIRECT_SAMPLING = 0x09
        const val BLOCK_BYTES = 32768  // 16384 amostras I/Q

        private val TUNERS = arrayOf(
            "DESCONHECIDO", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"
        )

        // O rtl_tcp SO aceita ganhos exatos da tabela do tuner (em decimos de dB);
        // valores fora da tabela sao ignorados silenciosamente pelo dongle.
        private val GAINS_R820T = intArrayOf(0,9,14,27,37,77,87,125,144,157,166,197,207,
            229,254,280,297,328,338,364,372,386,402,421,434,439,445,480,496)
        private val GAINS_E4000 = intArrayOf(-10,15,40,65,90,115,140,165,190,215,240,290,340,420)
        private val GAINS_FC0012 = intArrayOf(-99,-40,71,179,192)
        private val GAINS_FC0013 = intArrayOf(-99,-73,-65,-63,-60,-58,-54,58,61,63,65,67,
            68,70,71,179,181,182,184,186,188,191,197)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var cmdExec: ExecutorService? = null
    private val running = AtomicBoolean(false)
    val bytesReceived = AtomicLong(0)
    @Volatile var tunerName: String = ""
        private set

    val isConnected: Boolean get() = running.get()

    fun connect(host: String, port: Int) {
        if (running.getAndSet(true)) return
        cmdExec = Executors.newSingleThreadExecutor { r -> Thread(r, "rtltcp-cmd") }
        Thread({
            var reason = "Conexao encerrada"
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                s.receiveBufferSize = 1 shl 18
                socket = s
                output = s.getOutputStream()
                val din = DataInputStream(s.getInputStream())

                // Cabecalho: "RTL0" + tipo do tuner (u32 BE) + qtde de ganhos (u32 BE)
                val header = ByteArray(12)
                din.readFully(header)
                var tuner = "?"
                var gains = 0
                if (header[0] == 'R'.code.toByte() && header[1] == 'T'.code.toByte() &&
                    header[2] == 'L'.code.toByte()) {
                    val type = ((header[4].toInt() and 0xFF) shl 24) or
                            ((header[5].toInt() and 0xFF) shl 16) or
                            ((header[6].toInt() and 0xFF) shl 8) or
                            (header[7].toInt() and 0xFF)
                    gains = ((header[8].toInt() and 0xFF) shl 24) or
                            ((header[9].toInt() and 0xFF) shl 16) or
                            ((header[10].toInt() and 0xFF) shl 8) or
                            (header[11].toInt() and 0xFF)
                    tuner = TUNERS.getOrElse(type) { "?" }
                }
                tunerName = tuner
                listener.onConnected(tuner, gains)

                val buf = ByteArray(BLOCK_BYTES)
                while (running.get()) {
                    din.readFully(buf)
                    bytesReceived.addAndGet(BLOCK_BYTES.toLong())
                    listener.onIq(buf, BLOCK_BYTES)
                }
            } catch (e: Exception) {
                reason = e.message ?: e.javaClass.simpleName
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                output = null
                listener.onDisconnected(reason)
            }
        }, "rtltcp-rx").start()
    }

    fun close() {
        running.set(false)
        try { cmdExec?.shutdown() } catch (_: Exception) {}
        cmdExec = null
        try { socket?.close() } catch (_: Exception) {}
    }

    /**
     * Envia comando em thread dedicada. CRITICO: no Android, escrever no
     * socket a partir da thread principal lanca NetworkOnMainThreadException
     * e o comando nunca chega ao dongle.
     */
    fun cmd(code: Int, value: Long) {
        val exec = cmdExec ?: return
        try {
            exec.execute {
                val o = output ?: return@execute
                try {
                    val b = ByteBuffer.allocate(5)
                    b.put(code.toByte())
                    b.putInt((value and 0xFFFFFFFFL).toInt())
                    o.write(b.array())
                    o.flush()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun setFrequency(hz: Long) = cmd(CMD_FREQ, hz)
    fun setSampleRate(sps: Int) = cmd(CMD_SAMPLERATE, sps.toLong())
    fun setGainMode(manual: Boolean) = cmd(CMD_GAIN_MODE, if (manual) 1L else 0L)
    fun setGainTenths(tenths: Int) {
        val table = when (tunerName) {
            "E4000" -> GAINS_E4000
            "FC0012" -> GAINS_FC0012
            "FC0013" -> GAINS_FC0013
            else -> GAINS_R820T
        }
        var best = table[0]
        for (g in table) if (Math.abs(g - tenths) < Math.abs(best - tenths)) best = g
        cmd(CMD_GAIN, best.toLong())
    }
    fun setPpm(ppm: Int) = cmd(CMD_PPM, ppm.toLong())
    fun setAgc(on: Boolean) = cmd(CMD_AGC, if (on) 1L else 0L)
    fun setDirectSampling(mode: Int) = cmd(CMD_DIRECT_SAMPLING, mode.toLong())
}
