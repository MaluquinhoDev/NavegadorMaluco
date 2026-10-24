package com.example.extbrowser

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder

/** Player independente da WebView: somente URLs diretas HTTP(S) compatíveis com MediaPlayer. */
class NativeMediaService : Service() {
    companion object {
        const val ACTION_PLAY = "com.example.extbrowser.NATIVE_PLAY"
        const val ACTION_PAUSE = "com.example.extbrowser.NATIVE_PAUSE"
        const val ACTION_RESUME = "com.example.extbrowser.NATIVE_RESUME"
        const val ACTION_STOP = "com.example.extbrowser.NATIVE_STOP"
        const val EXTRA_URL = "media_url"
        private const val CHANNEL = "navegador_native_media"
        private const val NOTIFICATION_ID = 108
    }
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var ready = false
    private var currentUrl = ""
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Player nativo", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "NavegadorMalucoPlayer")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { resumeMedia() }
            override fun onPause() { pauseMedia() }
            override fun onStop() { stopSelf() }
        })
        session.isActive = true
        setState(PlaybackState.STATE_NONE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val raw = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val uri = Uri.parse(raw)
                if (uri.scheme != "https" && uri.scheme != "http") return START_NOT_STICKY
                currentUrl = raw
                ready = false
                playing = false
                // Deve ser chamado imediatamente após startForegroundService.
                startForeground(NOTIFICATION_ID, notification("Carregando áudio…"))
                startPlayback(uri)
            }
            ACTION_PAUSE -> pauseMedia()
            ACTION_RESUME -> resumeMedia()
            ACTION_STOP -> stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(uri: Uri) {
        player?.release()
        player = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pauseMedia()
            }.build()
        focusRequest = focus
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stopSelf()
            return
        }
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(this, uri)
            p.setOnPreparedListener {
                ready = true
                it.start()
                playing = true
                setState(PlaybackState.STATE_PLAYING)
                refreshNotification("Reproduzindo áudio")
            }
            p.setOnCompletionListener { stopSelf() }
            p.setOnErrorListener { _, _, _ -> stopSelf(); true }
            setState(PlaybackState.STATE_BUFFERING)
            p.prepareAsync()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun pauseMedia() {
        if (!ready || !playing) return
        runCatching { player?.pause() }
        playing = false
        setState(PlaybackState.STATE_PAUSED)
        refreshNotification("Áudio pausado")
    }

    private fun resumeMedia() {
        if (!ready || playing) return
        runCatching { player?.start() }
        playing = true
        setState(PlaybackState.STATE_PLAYING)
        refreshNotification("Reproduzindo áudio")
    }

    private fun setState(state: Int) {
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .build())
    }

    private fun pending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(this, requestCode,
            Intent(this, NativeMediaService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_RESUME
        val label = if (playing) "Pausar" else "Reproduzir"
        val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NavegadorMaluco · Player nativo")
            .setContentText(message)
            .setContentIntent(open)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .addAction(Notification.Action.Builder(icon, label, pending(toggleAction, 2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Parar", pending(ACTION_STOP, 3)).build())
            .setOngoing(playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .build()
    }
    private fun refreshNotification(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        setState(PlaybackState.STATE_STOPPED)
        session.release()
        super.onDestroy()
    }
}
