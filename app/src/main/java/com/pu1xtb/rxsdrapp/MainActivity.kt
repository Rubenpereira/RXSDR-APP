package com.pu1xtb.rxsdrapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        const val REQ_USB = 1234
        const val DRIVER_PKG = "marto.rtl_tcp_andro"
        const val IF_OFFSET = 250_000L        // desloca o espigao DC para fora do VFO
        const val DIRECT_SAMPLING_LIMIT = 24_000_000L
    }

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var client: RtlTcpClient? = null
    private lateinit var engine: DspEngine

    // Padrao: ganho manual 28 dB. O modo automatico do tuner via rtl_tcp
    // e fraco em muitas versoes da librtlsdr (sensibilidade baixa).
    @Volatile private var hwAgc = false
    @Volatile private var gainTenths = 326
    @Volatile private var ppm = 0
    private var directSamplingMode = "AUTO" // "AUTO", "ON", "OFF"
    private var directSamplingActive = false
    private var lastBytes = 0L
    private var lastRateMs = 0L
    private var rxRate = 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = DspEngine(object : DspEngine.Ui {
            override fun onSpectrum(bins: ByteArray, centerHz: Long, sampleRate: Int, vfoHz: Long) {
                val b64 = Base64.encodeToString(bins, Base64.NO_WRAP)
                js("onSpec('" + b64 + "'," + centerHz + "," + sampleRate + "," + vfoHz + ")")
            }
            override fun onDmrAudio(samples: FloatArray, len: Int) {
                dmr?.feedAudio(samples, len)
            }
        })

        web = WebView(this)
        web.setBackgroundColor(0xFF0B0F14.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.addJavascriptInterface(JsBridge(), "Android")
        setContentView(web)
        goFullscreen()
        web.loadUrl("file:///android_asset/index.html")

        // status ~8x por segundo
        handler.postDelayed(object : Runnable {
            override fun run() {
                pushStatus()
                handler.postDelayed(this, 125)
            }
        }, 500)
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    private fun js(script: String) {
        handler.post { web.evaluateJavascript(script, null) }
    }

    private fun pushStatus() {
        val c = client
        val now = System.currentTimeMillis()
        val bytes = c?.bytesReceived?.get() ?: 0L
        if (lastRateMs > 0 && now > lastRateMs) {
            val dt = (now - lastRateMs) / 1000.0
            rxRate = rxRate * 0.5 + ((bytes - lastBytes) / dt) * 0.5
        }
        lastBytes = bytes
        lastRateMs = now
        val o = JSONObject()
        o.put("connected", c?.isConnected == true)
        o.put("tuner", c?.tunerName ?: "")
        o.put("smeter", engine.smeterDbm.toDouble())
        o.put("audio", engine.audio.level.toDouble())
        o.put("rate", rxRate)
        o.put("total", bytes)
        o.put("sq", engine.squelchOpen)
        o.put("center", engine.centerHz)
        o.put("ds", directSamplingActive)
        js("onStatus(" + o.toString() + ")")
    }

    // ------------------- conexao -------------------

    private fun doConnectTcp(host: String, port: Int) {
        doDisconnect()
        val cl = RtlTcpClient(object : RtlTcpClient.Listener {
            override fun onConnected(tunerName: String, gainCount: Int) {
                handler.post {
                    engine.start()
                    applyInitialSettings()
                    startRadioService()
                    js("onConnected(" + JSONObject.quote(tunerName) + ")")
                }
            }
            override fun onIq(buf: ByteArray, len: Int) {
                engine.submit(buf, len)
            }
            override fun onDisconnected(reason: String) {
                handler.post {
                    engine.stop()
                    js("onDisconnected(" + JSONObject.quote(reason) + ")")
                }
            }
        })
        client = cl
        cl.connect(host, port)
    }

    private fun applyInitialSettings() {
        val c = client ?: return
        c.setSampleRate(engine.sampleRate)
        directSamplingActive = false
        applyDirectSampling(engine.centerHz)
        c.setFrequency(engine.centerHz)
        c.setPpm(ppm)
        applyGain()
        engine.markDirty()
    }

    private fun applyGain() {
        val c = client ?: return
        if (directSamplingActive) {
            // ===== DIRECT SAMPLING (HF): tuner R820T bypassed =====
            // CMD_GAIN_MODE e CMD_GAIN nao tem efeito — o sinal vai
            // direto da antena ao ADC do RTL2832U.
            if (hwAgc) {
                // AGC digital do RTL2832U — unico controle de ganho HW
                // disponivel em direct sampling.
                c.setAgc(true)
                // Atenua por software (-15 dB) para compensar a sobre-amplificacao do AGC
                // de hardware, evitando estourar a cachoeira em vermelho.
                engine.softGainDb = -15f
            } else {
                // Ganho manual: mapeado para atenuacao por software (0.0 a 49.6 dB no slider
                // torna-se -49.6 dB a 0.0 dB de ganho real) para evitar saturacao digital.
                c.setAgc(false)
                engine.softGainDb = (gainTenths / 10f) - 49.6f
            }
        } else {
            // ===== MODO NORMAL: tuner R820T no caminho do sinal =====
            // AGC digital do RTL2832 fica sempre desligado (amplifica
            // ruido de forma excessiva e "avermelha" a cachoeira).
            c.setAgc(false)
            engine.softGainDb = 0f
            if (hwAgc) {
                // Auto-ganho do tuner: setGainMode(false) = automatico.
                c.setGainMode(false)
            } else {
                // Ganho manual do tuner: valor especifico em decimos de dB.
                c.setGainMode(true)
                c.setGainTenths(gainTenths)
            }
        }
    }

    private fun applyDirectSampling(freq: Long) {
        val need = when (directSamplingMode) {
            "ON" -> true
            "OFF" -> false
            else -> freq < DIRECT_SAMPLING_LIMIT
        }
        if (need != directSamplingActive) {
            directSamplingActive = need
            client?.setDirectSampling(if (need) 2 else 0)
            // Mecanismo de ganho muda entre DS (software) e normal (tuner):
            applyGain()
        }
    }

    private var dmr: DmrManager? = null
    private var dmrInverted = false

    private fun startDmr(inverted: Boolean = dmrInverted) {
        if (dmr != null) return
        dmrInverted = inverted
        val manager = DmrManager(this, engine.audio) { jsonState ->
            runOnUiThread {
                web.evaluateJavascript("DmrDecoder.updateSlots(${JSONObject.quote(jsonState)})", null)
            }
        }
        manager.inverted = inverted
        dmr = manager
        manager.start()
    }

    private fun stopDmr() {
        dmr?.stop()
        dmr = null
    }

    private fun doDisconnect() {
        stopDmr()
        client?.close()
        client = null
        engine.stop()
        stopRadioService()
    }

    private fun startRadioService() {
        try {
            val i = Intent(this, RadioService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun stopRadioService() {
        try { stopService(Intent(this, RadioService::class.java)) } catch (_: Exception) {}
    }

    private fun doSetVfo(hz: Long) {
        engine.vfoHz = hz
        val fs = engine.sampleRate
        if (abs(hz - engine.centerHz) > (fs * 0.38).toLong()) {
            retuneCenter(hz)
        }
    }

    private fun retuneCenter(vfo: Long) {
        var newCenter = vfo + IF_OFFSET
        if (newCenter < 0) newCenter = vfo
        engine.centerHz = newCenter
        applyDirectSampling(newCenter)
        client?.setFrequency(newCenter)
        js("onCenter(" + newCenter + ")")
    }

    // ------------------- driver USB -------------------

    private fun launchUsbDriver() {
        try {
            val uri = Uri.parse("iqsrc://-a 127.0.0.1 -p 1234 -s " + engine.sampleRate)
            startActivityForResult(Intent(Intent.ACTION_VIEW, uri), REQ_USB)
        } catch (e: ActivityNotFoundException) {
            js("onDriverMissing()")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_USB) {
            if (resultCode == RESULT_OK) {
                handler.postDelayed({ doConnectTcp("127.0.0.1", 1234) }, 300)
            } else {
                val msg = data?.getStringExtra("detailed_exception_message")
                    ?: "O driver RTL-SDR nao conseguiu abrir o dispositivo USB"
                js("onUsbError(" + JSONObject.quote(msg) + ")")
            }
        }
    }

    // ------------------- ponte JavaScript -------------------

    inner class JsBridge {
        @JavascriptInterface
        fun connectTcp(host: String, port: Int) {
            handler.post { doConnectTcp(host.trim(), port) }
        }

        @JavascriptInterface
        fun connectUsb() {
            handler.post { launchUsbDriver() }
        }

        @JavascriptInterface
        fun disconnect() {
            handler.post { doDisconnect() }
        }

        @JavascriptInterface
        fun setVfo(hzStr: String) {
            val hz = hzStr.toLongOrNull() ?: return
            handler.post { doSetVfo(hz) }
        }

        @JavascriptInterface
        fun setDirectMode(m: String) {
            handler.post {
                directSamplingMode = m
                applyDirectSampling(engine.vfoHz)
            }
        }

        @JavascriptInterface
        fun setMode(m: String) {
            val oldMode = engine.mode
            engine.mode = m
            engine.markDirty()
            handler.post {
                if (m == "DMR") {
                    if (oldMode != "DMR") {
                        startDmr()
                    }
                } else {
                    if (oldMode == "DMR") {
                        stopDmr()
                    }
                }
            }
        }

        @JavascriptInterface
        fun setBandwidth(hz: Int) {
            engine.bandwidthHz = hz
            engine.markDirty()
        }

        @JavascriptInterface
        fun setAgcHw(on: Boolean) {
            hwAgc = on
            handler.post { applyGain() }
        }

        @JavascriptInterface
        fun setGainTenths(tenths: Int) {
            gainTenths = tenths
            hwAgc = false
            handler.post { applyGain() }
        }

        @JavascriptInterface
        fun setPpm(p: Int) {
            ppm = p
            handler.post { client?.setPpm(p) }
        }

        @JavascriptInterface
        fun setSquelch(db: Double) {
            engine.squelchDb = db.toFloat()
        }

        @JavascriptInterface
        fun setTone(v: Double) {
            engine.setTone(v.toFloat())
        }

        @JavascriptInterface
        fun setFftSize(n: Int) {
            engine.setFftSize(n)
        }

        @JavascriptInterface
        fun setVolume(v: Double) {
            engine.volume = v.toFloat()
        }

        @JavascriptInterface
        fun setKeepScreen(on: Boolean) {
            handler.post {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun setNr(v: Double) {
            engine.nrLevel = v.toFloat().coerceIn(0f, 1f)
        }

        @JavascriptInterface
        fun setCal(db: Double) {
            engine.calDb = db.toFloat()
        }

        @JavascriptInterface
        fun setSampleRate(sps: Int) {
            handler.post {
                engine.sampleRate = sps
                engine.markDirty()
                client?.setSampleRate(sps)
                retuneCenter(engine.vfoHz)
            }
        }

        @JavascriptInterface
        fun setFftFps(fps: Int) {
            engine.fftFps = fps
        }

        @JavascriptInterface
        fun openDriverStore() {
            handler.post {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + DRIVER_PKG)))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + DRIVER_PKG)))
                }
            }
        }

        @JavascriptInterface
        fun setDmrPolarity(inverted: Boolean) {
            handler.post {
                dmrInverted = inverted
                if (dmr != null) {
                    stopDmr()
                    startDmr(inverted)
                }
            }
        }

        @JavascriptInterface
        fun setDmrPcmHz(hz: Int) {
            handler.post {
                dmr?.pcmHz = hz
            }
        }

        @JavascriptInterface
        fun setDmrListenMode(mode: String) {
            handler.post {
                dmr?.listenMode = mode
            }
        }

        @JavascriptInterface
        fun setDmrLogEnabled(enabled: Boolean) {
            handler.post {
                dmr?.logEnabled = enabled
            }
        }


        @JavascriptInterface
        fun exitApp() {
            handler.post {
                doDisconnect()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        js("onBackButton()")
    }

    override fun onDestroy() {
        doDisconnect()
        super.onDestroy()
    }
}
