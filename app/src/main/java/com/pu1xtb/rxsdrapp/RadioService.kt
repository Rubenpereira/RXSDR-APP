package com.pu1xtb.rxsdrapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servico em primeiro plano: impede que o Android suspenda a CPU, o Wi-Fi
 * e o processo enquanto o radio esta recebendo com a tela apagada.
 */
class RadioService : Service() {

    companion object {
        const val CHANNEL_ID = "rxsdr_radio"
        const val NOTIF_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "Recepcao de radio",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = builder
            .setContentTitle("RXSDR-APP")
            .setContentText("Recebendo... toque para abrir")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rxsdrapp:radio")
            wakeLock?.acquire()
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rxsdrapp:wifi")
            wifiLock?.acquire()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        super.onDestroy()
    }
}
