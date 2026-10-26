package com.example.extbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Mantém o processo em primeiro plano durante mídia iniciada na WebView.
 * Não hospeda um player próprio e não contorna restrições impostas pelo site.
 */
class BackgroundAudioService : Service() {
    companion object {
        private const val CHANNEL = "navegador_audio"
        private const val NOTIFICATION_ID = 107
        const val ACTION_STOP = "com.example.extbrowser.STOP_BACKGROUND_AUDIO"
        const val ACTION_MEDIA_ACTIVE = "com.example.extbrowser.BACKGROUND_MEDIA_ACTIVE"
        private const val IDLE_TIMEOUT_MS = 30_000L
        @Volatile var running = false
            private set
        @Volatile private var lastMediaSeenMs = 0L

        /** Apenas sinaliza atividade observada; não inicia serviço em segundo plano. */
        fun markMediaObserved() {
            lastMediaSeenMs = SystemClock.elapsedRealtime()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastMediaSeenMs >= IDLE_TIMEOUT_MS) {
                stopSelf()
            } else {
                handler.postDelayed(this, 5000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reprodução em segundo plano", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("media_options", MODE_PRIVATE).edit()
                .putBoolean("background_audio", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_MEDIA_ACTIVE) {
            stopSelf()
            return START_NOT_STICKY
        }
        markMediaObserved()
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BackgroundAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forceStopIntent = StopAllMediaReceiver.pendingIntent(this, 107)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NavegadorMaluco")
            .setContentText("Suporte a áudio em segundo plano ativo")
            .setContentIntent(contentIntent)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Desativar", stopIntent).build())
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Parar todas", forceStopIntent).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, 5000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        running = false
        super.onDestroy()
    }
}
