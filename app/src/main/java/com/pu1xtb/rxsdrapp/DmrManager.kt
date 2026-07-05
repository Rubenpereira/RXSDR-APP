package com.pu1xtb.rxsdrapp

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.concurrent.thread

class DmrSlotState {
    var active: Boolean = false
    var type: String = "-"
    var src: String = "-"
    var tgt: String = "-"
    var lastActiveTime: Long = 0
    var isVoice: Boolean = true

    fun copyFrom(other: DmrSlotState) {
        this.active = other.active
        this.type = other.type
        this.src = other.src
        this.tgt = other.tgt
        this.lastActiveTime = other.lastActiveTime
        this.isVoice = other.isVoice
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DmrSlotState) return false
        return this.active == other.active &&
               this.type == other.type &&
               this.src == other.src &&
               this.tgt == other.tgt &&
               this.isVoice == other.isVoice
    }
}

class DmrManager(private val context: Context, private val audioSink: AudioSink, private val onStateChanged: (String) -> Unit) {
    private var process: Process? = null
    private var stdinStream: BufferedOutputStream? = null
    private var udpSocket: DatagramSocket? = null
    
    @Volatile private var running = false
    private var dsdThread: Thread? = null
    private var udpThread: Thread? = null
    private var stdinThread: Thread? = null
    private var timerThread: Thread? = null
    
    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<FloatArray>(150)
    // Fila LIMITADA: se a UI nao drenar, descarta o mais antigo (nunca cresce sem fim)
    private val pendingLogs = java.util.concurrent.LinkedBlockingQueue<String>(200)
    
    // Configurable polarity
    @Volatile var inverted: Boolean = false

    // Configurable PCM rate
    @Volatile var pcmHz: Int = 16150
    
    @Volatile var listenMode: String = "both"
    @Volatile var logEnabled: Boolean = false
    private var dcBlockLastInput = 0f
    private var dcBlockLastOutput = 0f

    // Buffer de saida aumentado para evitar jitter
    private var outputFloatBuffer = FloatArray(16384)

    // Continuidade do resample 32k->48k entre blocos de audio consecutivos
    // (evita o "degrau" periodico na borda de cada bloco - ver stdinThread)
    private var resamplePrevLastSample: Float = 0f
    private var resampleHasPrev: Boolean = false

    // Priming anti-rajada: quando a voz comeca depois de ociosidade, injeta
    // 100 ms de silencio antes para criar folga no AudioTrack (jitter buffer)
    private var lastAudioWriteMs = 0L
    private val primeSilence = FloatArray(3200) // 100 ms @ 32 kHz

    // Slot states
    private val slot1 = DmrSlotState()
    private val slot2 = DmrSlotState()
    private val lastSlot1 = DmrSlotState()
    private val lastSlot2 = DmrSlotState()

    // Remove codigos ANSI de cor (ex.: ESC[31m) das linhas mostradas no log da janela
    private val ansiRegex = Regex("\u001B\\[[0-9;]*m")

    private val srcRegex = Regex("""(?:SRC|RID|Source)\s*[= ]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val tgtRegex = Regex("""(?:TGT|TG|Target)\s*[= ]\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun start() {
        if (running) return
        running = true
        
        try {
            val binaryPath = "${context.applicationInfo.nativeLibraryDir}/libdsd-fme.so"
            Log.d("DmrManager", "Starting DMR process: $binaryPath (inverted=$inverted)")
            
            val args = mutableListOf(
                binaryPath,
                "-fs",
                "-V", "3",
                "-i", "-",
                "-o", "udp:127.0.0.1:40123"
            )
            if (inverted) {
                args.add("-xr")
            }
            
            val pb = ProcessBuilder(args)
            pb.directory(context.cacheDir)
            pb.redirectErrorStream(true)
            
            process = pb.start()
            stdinStream = BufferedOutputStream(process!!.outputStream, 65536)

            // dsd-fme imprime "FEC ERR"/CACH/etc incondicionalmente (nao depende do -V),
            // entao em sinal fraco ele gera MUITAS linhas por segundo. Se o pipe padrao do
            // Linux (64KB) enche porque a thread leitora nao drena rapido o suficiente, o
            // proprio processo dsd-fme trava no write() dele - e como e o mesmo loop que
            // decodifica e manda audio por UDP, isso "prende"/pica o audio. Aumentamos o
            // buffer do pipe para reduzir muito a chance disso acontecer.
            try {
                val fis = process!!.inputStream as? java.io.FileInputStream
                val fd = fis?.fd
                if (fd != null) {
                    // F_SETPIPE_SZ (1031 no Linux) nao existe em android.system.OsConstants
                    // (so expoe um subconjunto POSIX), entao usamos o valor numerico direto.
                    val F_SETPIPE_SZ = 1031
                    android.system.Os.fcntlInt(fd, F_SETPIPE_SZ, 1048576)
                }
            } catch (e: Exception) {
                Log.w("DmrManager", "Nao foi possivel aumentar o buffer do pipe do dsd-fme", e)
            }

            // Thread to read and parse dsd-fme logs.
            // Prioridade elevada de verdade (nao Thread.MAX_PRIORITY, que o Android ignora em
            // grande parte) para garantir que essa thread SEMPRE drena o pipe rapido, mesmo
            // sob carga de UI/GC - e assim nunca deixa o dsd-fme bloquear escrevendo log.
            dsdThread = thread(name = "DmrLogReader") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        parseLogLine(line)
                        if (logEnabled) {
                            // So aqui (log ligado) limpa os codigos ANSI de cor;
                            // com log desligado a thread fica o mais leve possivel
                            val clean = ansiRegex.replace(line, "").trim()
                            if (clean.isNotEmpty() && !pendingLogs.offer(clean)) {
                                pendingLogs.poll()
                                pendingLogs.offer(clean)
                            } 
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e("DmrManager", "Error reading dsd-fme output", e)
                }
            }
            
            // Thread to write audio to dsd-fme stdin asynchronously with high priority
            stdinThread = thread(name = "DmrStdinWriter") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val writeBuffer = ShortArray(16384)
                val byteBuf = ByteBuffer.allocate(writeBuffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                
                while (running) {
                    try {
                        val samples = audioQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        val stream = stdinStream ?: continue
                        
                        val len = samples.size
                        if (len <= 0) continue
                        var writeCount = 0

                        // Termina a amostra que ficou pendente do bloco ANTERIOR (ela
                        // precisava da 1a amostra deste bloco novo para interpolar certo).
                        if (resampleHasPrev) {
                            val s1 = resamplePrevLastSample
                            val s2 = samples[0]
                            val interp = s1 + (s2 - s1) * (1f / 3f)
                            val output = interp - dcBlockLastInput + 0.995f * dcBlockLastOutput
                            dcBlockLastInput = interp
                            dcBlockLastOutput = output
                            val scaled = output * 20000f
                            writeBuffer[writeCount++] = scaled.coerceIn(-32000f, 31000f).toInt().toShort()
                        }

                        // A ultima amostra de saida deste bloco (frac=1/3) precisaria da
                        // 1a amostra do PROXIMO bloco, que ainda nao chegou - entao ela fica
                        // pendente e e completada na proxima iteracao (acima).
                        val outLen = ((len * 3) / 2) - 1

                        // Resample from 32000 Hz to 48000 Hz using linear interpolation
                        for (i in 0 until outLen) {
                            val srcPos = (i * 2.0f) / 3.0f
                            val srcIdx = srcPos.toInt()
                            val frac = srcPos - srcIdx
                            val s1 = samples[srcIdx]
                            val s2 = if (srcIdx + 1 < len) samples[srcIdx + 1] else s1
                            val interp = s1 + (s2 - s1) * frac
                            
                            // DC blocker (high-pass filter at ~50 Hz)
                            val output = interp - dcBlockLastInput + 0.995f * dcBlockLastOutput
                            dcBlockLastInput = interp
                            dcBlockLastOutput = output
                            
                            val scaled = output * 20000f
                            writeBuffer[writeCount++] = scaled.coerceIn(-32000f, 31000f).toInt().toShort()
                        }

                        resamplePrevLastSample = samples[len - 1]
                        resampleHasPrev = true
                        
                        byteBuf.clear()
                        for (i in 0 until writeCount) {
                            byteBuf.putShort(writeBuffer[i])
                        }
                        
                        stream.write(byteBuf.array(), 0, writeCount * 2)
                        stream.flush()
                    } catch (e: Exception) {
                        if (running) Log.e("DmrManager", "Error in StdinWriter", e)
                    }
                }
            }
            
            // Thread to receive UDP PCM audio from dsd-fme
            udpThread = thread(name = "DmrUdpReceiver") {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val sock = DatagramSocket(40123)
                sock.soTimeout = 1000
                try {
                    sock.receiveBufferSize = 1024 * 1024 // 1 MB para evitar drops
                } catch (_: Exception) {}
                udpSocket = sock
                
                val buf = ByteArray(32768)
                val packet = DatagramPacket(buf, buf.size)
                while (running) {
                    try {
                        sock.receive(packet)
                        val len = packet.length
                        if (len > 0) {
                            processDecodedAudio(buf, len)
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        break
                    }
                }
            }

            // Thread for checking timeouts and flushing logs periodically
            timerThread = thread(name = "DmrTimeoutChecker") {
                while (running) {
                    try {
                        Thread.sleep(250)
                        checkTimeoutAndNotify()
                        flushPendingLogs()
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {}
                }
            }
            
        } catch (e: Exception) {
            Log.e("DmrManager", "Failed to start DMR decoder", e)
            stop()
        }
    }

    private fun parseLogLine(line: String) {
        var slotNum = 0
        val lineLower = line.lowercase()
        
        if (lineLower.contains("[slot1]") || lineLower.contains("slot 1") || lineLower.contains("slot1")) {
            slotNum = 1
        } else if (lineLower.contains("[slot2]") || lineLower.contains("slot 2") || lineLower.contains("slot2")) {
            slotNum = 2
        }
        
        if (slotNum > 0) {
            val state = if (slotNum == 1) slot1 else slot2
            synchronized(state) {
                state.active = true
                state.lastActiveTime = System.currentTimeMillis()
                
                val srcMatch = srcRegex.find(line)
                if (srcMatch != null) {
                    state.src = srcMatch.groupValues[1]
                }
                
                val tgtMatch = tgtRegex.find(line)
                if (tgtMatch != null) {
                    state.tgt = tgtMatch.groupValues[1]
                }
                
                if (lineLower.contains("group call")) {
                    state.type = "Grupo"
                } else if (lineLower.contains("priv call") || lineLower.contains("private")) {
                    state.type = "Privado"
                } else if (lineLower.contains("data")) {
                    state.type = "Dados"
                } else if (lineLower.contains("voice")) {
                    state.type = "Voz"
                }
                
                if (lineLower.contains("voice frame") || lineLower.contains("[voice]") || 
                    (lineLower.contains("voice") && !lineLower.contains("synthesis"))) {
                    state.isVoice = true
                } else if (lineLower.contains("data frame") || lineLower.contains("[data]") || lineLower.contains("data")) {
                    state.isVoice = false
                }
            }
        }
        // NAO chamar checkTimeoutAndNotify() aqui: rodava a cada linha de log do
        // dsd-fme e pesava a thread que drena o pipe (risco de bloquear o dsd-fme
        // escrevendo log = audio picotado). O timerThread ja notifica a cada 250 ms.
    }

    private fun checkTimeoutAndNotify() {
        val now = System.currentTimeMillis()
        var changed = false
        
        synchronized(slot1) {
            if (slot1.active && (now - slot1.lastActiveTime > 3500)) {
                slot1.active = false
                slot1.type = "-"
                slot1.src = "-"
                slot1.tgt = "-"
                changed = true
            }
        }
        synchronized(slot2) {
            if (slot2.active && (now - slot2.lastActiveTime > 3500)) {
                slot2.active = false
                slot2.type = "-"
                slot2.src = "-"
                slot2.tgt = "-"
                changed = true
            }
        }
        
        synchronized(this) {
            if (changed || slot1 != lastSlot1 || slot2 != lastSlot2) {
                lastSlot1.copyFrom(slot1)
                lastSlot2.copyFrom(slot2)
                
                // Build JSON string
                val json = """{"ts1":{"active":${slot1.active},"type":"${slot1.type}","src":"${slot1.src}","tgt":"${slot1.tgt}","isVoice":${slot1.isVoice}},"ts2":{"active":${slot2.active},"type":"${slot2.type}","src":"${slot2.src}","tgt":"${slot2.tgt}","isVoice":${slot2.isVoice}}}"""
                onStateChanged(json)
            }
        }
    }

    private fun flushPendingLogs() {
        if (pendingLogs.isEmpty()) return
        val list = mutableListOf<String>()
        while (true) {
            val line = pendingLogs.poll() ?: break
            list.add(line)
            if (list.size >= 50) break
        }
        if (list.isNotEmpty()) {
            val jsonArray = org.json.JSONArray()
            for (line in list) {
                jsonArray.put(line)
            }
            val logJson = JSONObject().apply {
                put("logs", jsonArray)
            }.toString()
            onStateChanged(logJson)
        }
    }

    /**
     * Feeds raw discriminator FM audio (float, 32000 Hz) to the DMR decoder.
     * We queue it for the writer thread to handle dsd-fme stdin.
     */
    fun feedAudio(samples: FloatArray, len: Int) {
        if (!running) return
        // Copy samples because the buffer is reused in DspEngine
        val copy = samples.copyOf(len)
        if (!audioQueue.offer(copy)) {
            audioQueue.poll()
            audioQueue.offer(copy)
        }
    }

    private fun processDecodedAudio(bytes: ByteArray, len: Int) {
        val totalSamples = len / 2
        if (totalSamples <= 0) return
        
        // Detect channels dynamically based on packet size (typically 20ms frames)
        // 8000 Hz 20ms Mono = 160 samples. 8000 Hz 20ms Stereo = 320 samples.
        val channels = if (totalSamples > 240) 2 else 1
        val numFrames = if (channels == 2) totalSamples / 2 else totalSamples
        
        // Convert bytes to Short PCM samples
        val shortBuf = ShortArray(totalSamples)
        ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuf)
        
        val monoBuf = ShortArray(numFrames)
        if (channels == 2) {
            val mode = listenMode
            for (i in 0 until numFrames) {
                val left = shortBuf[i * 2].toFloat()
                val right = shortBuf[i * 2 + 1].toFloat()
                val sample = when (mode) {
                    "ts1" -> left
                    "ts2" -> right
                    else -> (left + right) / 2f
                }
                monoBuf[i] = sample.coerceIn(-32768f, 32767f).toInt().toShort()
            }
        } else {
            // Mono stream: use samples directly
            System.arraycopy(shortBuf, 0, monoBuf, 0, totalSamples)
        }
        
        // Resample from (pcmHz / 2.0) to 32000 Hz dynamically
        val currentPcmHz = pcmHz.toFloat() / 2f
        val ratio = currentPcmHz / 32000.0f
        val outLen = (numFrames * 32000.0f / currentPcmHz).toInt()
        
        if (outLen <= 0) return
        if (outputFloatBuffer.size < outLen) {
            outputFloatBuffer = FloatArray(outLen + 1024)
        }
        
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s1 = if (srcIdx < numFrames) monoBuf[srcIdx].toFloat() / 32768.0f else 0f
            val s2 = if (srcIdx + 1 < numFrames) monoBuf[srcIdx + 1].toFloat() / 32768.0f else s1
            
            var sample = s1 + (s2 - s1) * frac
            sample *= 2.8f // Ganho de volume
            
            outputFloatBuffer[i] = sample.coerceIn(-1.0f, 1.0f)
        }
        
        // Se o AudioTrack ficou ocioso (>500 ms sem voz), injeta 100 ms de
        // silencio primeiro: cria uma folga que absorve a chegada em rajadas
        // dos pacotes UDP do dsd-fme sem underrun (sem isso, cada inicio de
        // transmissao toca "colado" no vazio e qualquer jitter vira picote).
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAudioWriteMs > 500) {
            audioSink.write(primeSilence, primeSilence.size, 1.0f)
        }
        lastAudioWriteMs = nowMs

        // Escreve o audio resampleado para o AudioSink
        audioSink.write(outputFloatBuffer, outLen, 1.0f)
    }

    fun stop() {
        running = false
        
        try {
            stdinStream?.close()
        } catch (_: Exception) {}
        stdinStream = null
        
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null
        
        try {
            dsdThread?.interrupt()
            dsdThread?.join(500)
        } catch (_: Exception) {}
        dsdThread = null
        
        try {
            udpThread?.interrupt()
            udpThread?.join(500)
        } catch (_: Exception) {}
        udpThread = null

        try {
            stdinThread?.interrupt()
            stdinThread?.join(500)
        } catch (_: Exception) {}
        stdinThread = null
        
        audioQueue.clear()
        pendingLogs.clear()

        try {
            timerThread?.interrupt()
            timerThread?.join(500)
        } catch (_: Exception) {}
        timerThread = null
        
        Log.d("DmrManager", "DmrManager stopped successfully")
    }
}
