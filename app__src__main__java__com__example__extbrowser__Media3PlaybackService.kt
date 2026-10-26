package com.example.extbrowser

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** Diagnóstico seguro: não imprime URLs completas, queries, cookies ou credenciais. */
class Media3PlaybackService : MediaSessionService() {
    companion object {
        private const val TAG = "NavegadorMedia3"
        private const val ACTION_STOP_ALL = "com.example.extbrowser.MEDIA3_STOP_ALL"
        @Volatile private var liveService: Media3PlaybackService? = null

        fun stopExistingPlayback() {
            liveService?.stopAndClear()
        }

        /** Captura o ponto exato antes de uma tela de bloqueio limpar a sessão. */
        fun rememberPositionBeforeAttentionBlock() {
            liveService?.let { service ->
                service.player?.let { service.savePlaybackPosition(it, interrupted = true) }
            }
        }
    }
    private val stopAllCommand = SessionCommand(ACTION_STOP_ALL, Bundle.EMPTY)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var playerHistory: PlayerHistory
    private var lastHistorySaveMs = 0L
    private fun stopAndClear() {
        player?.let { exo ->
            savePlaybackPosition(exo)
            exo.pause()
            exo.stop()
            exo.clearMediaItems()
        }
        stopSelf()
    }
    private fun savePlaybackPosition(exo: ExoPlayer, interrupted: Boolean = false) {
        val extras = exo.currentMediaItem?.mediaMetadata?.extras ?: return
        val page = extras.getString("nm_attention_page") ?: return
        val title = exo.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Vídeo"
        // Uma interrupção por limite de atenção não significa que o usuário
        // concluiu o vídeo. Salvar zero aqui reiniciava o histórico indevidamente.
        val position = if (!interrupted && exo.playbackState == Player.STATE_ENDED) 0L
            else exo.currentPosition
        val safePosition = if (!interrupted && exo.playbackState == Player.STATE_ENDED) 0L
            else position.coerceAtLeast(0L)
        playerHistory.save(page, title, safePosition, durable = interrupted)
    }
    private val attentionHandler = Handler(Looper.getMainLooper())
    private var lastMediaTickMs = 0L
    private val attentionCheck = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val pageUrl = exo.currentMediaItem?.mediaMetadata?.extras
                ?.getString("nm_attention_page")
            val now = SystemClock.elapsedRealtime()
            val delta = if (exo.isPlaying && lastMediaTickMs > 0L)
                (now - lastMediaTickMs).coerceIn(0L, 1500L) else 0L
            lastMediaTickMs = now
            if (exo.currentMediaItem != null && now - lastHistorySaveMs >= 5000L) {
                savePlaybackPosition(exo)
                lastHistorySaveMs = now
            }
            val verdict = Attention.mediaPlaybackTick(pageUrl, delta)
            if (verdict != null) {
                // Parar e retirar a fonte da sessão impede Play/Seek/Próximo na
                // notificação, mini-player ou PiP de reativarem mídia bloqueada.
                if (exo.mediaItemCount > 0) {
                    savePlaybackPosition(exo, interrupted = true)
                    exo.stop()
                    exo.clearMediaItems()
                    Log.i(TAG, "Controle de Atenção encerrou sessão Media3: " +
                        verdict.site.domain + " (" + verdict.reason + ")")
                }
            }
            attentionHandler.postDelayed(this, 1000L)
        }
    }

    private fun safeHost(uri: Uri?): String = uri?.host ?: "desconhecido"

    private fun mediaKind(uri: Uri?): String {
        val path = uri?.path?.lowercase(Locale.ROOT) ?: return "indefinido"
        return when {
            path.endsWith(".m3u8") -> "HLS/m3u8"
            path.endsWith(".mpd") -> "DASH/mpd"
            path.endsWith(".mp4") -> "MP4"
            path.endsWith(".mp3") -> "MP3"
            path.endsWith(".m4a") -> "M4A"
            path.endsWith(".ts") -> "segmento TS"
            path.endsWith(".m4s") -> "segmento M4S"
            path.endsWith(".aac") -> "AAC"
            path.endsWith(".webm") -> "WebM"
            else -> "tipo não identificável pela extensão"
        }
    }

    override fun onCreate() {
        super.onCreate()
        liveService = this
        Attention.init(this)
        playerHistory = PlayerHistory(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(WebSettings.getDefaultUserAgent(this))
        val dataSource = DefaultDataSource.Factory(this, http)
        val mediaSources = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSources).build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Salva também ao trocar de item, independentemente do ticker.
                // Mantém a sessão de atenção ao trocar de vídeo no mesmo site.
                lastMediaTickMs = SystemClock.elapsedRealtime()
                val origin = item?.mediaMetadata?.extras?.getString("nm_page_origin") ?: ""
                val mediaUri = item?.localConfiguration?.uri
                val page = runCatching { Uri.parse(origin) }.getOrNull()
                // Mantém o comportamento anterior: apenas a origem HTTPS como Referer.
                val headers = if (page?.scheme == "https" && !page.host.isNullOrBlank() &&
                    mediaUri?.scheme == "https") mapOf("Referer" to origin) else emptyMap()
                http.setDefaultRequestProperties(headers)
                Log.i(TAG, "Fonte inicial: host=${safeHost(mediaUri)}; tipo=${mediaKind(mediaUri)}; " +
                    "referer=${headers.containsKey("Referer")}; userAgent=true; " +
                    "urlComParametros=${mediaUri?.query != null}")

                // Apenas examina se a WebView possui cookies aplicáveis a esta URL.
                // Não imprime nem encaminha cookies, tokens ou URLs completas.
                val cookieState = if (mediaUri?.scheme == "https" && mediaUri.host != null) {
                    runCatching {
                        if (CookieManager.getInstance().getCookie(mediaUri.toString()).isNullOrEmpty())
                            "ausentes" else "presentes"
                    }.getOrElse { "indisponivel" }
                } else "nao_aplicavel"
                val pageHost = page?.host
                val mediaHost = mediaUri?.host
                val sameHost = pageHost != null && mediaHost != null &&
                    pageHost.equals(mediaHost, ignoreCase = true)
                Log.i(TAG, "DiagnosticoContexto: hostPagina=${safeHost(page)}; " +
                    "hostMidia=${safeHost(mediaUri)}; mesmaOrigemDeHost=$sameHost; " +
                    "cookiesDaMidia=$cookieState; " +
                    "refererConfigurado=${headers.containsKey("Referer")}; " +
                    "cookiesEnviadosPeloMedia3=false")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val status = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "OUTRO"
                }
                Log.i(TAG, "Estado Media3=$status")
            }

            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error
                var httpStatus: Int? = null
                var failedUri: Uri? = null
                var responseHeaderNames: Set<String> = emptySet()
                val seen = HashSet<Throwable>()
                while (cause != null && seen.add(cause)) {
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        httpStatus = cause.responseCode
                        failedUri = cause.dataSpec.uri
                        responseHeaderNames = cause.headerFields.keys
                            .mapNotNull { key ->
                                key?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
                            }.toSet()
                    } else if (cause is HttpDataSource.HttpDataSourceException && failedUri == null) {
                        failedUri = cause.dataSpec.uri
                    }
                    cause = cause.cause
                }
                val source = exo.currentMediaItem?.localConfiguration?.uri
                Log.e(TAG, "Falha Media3: ${error.errorCodeName}; " +
                    "http=${httpStatus ?: "indisponível"}; " +
                    "hostRequisitado=${safeHost(failedUri)}; tipoRequisitado=${mediaKind(failedUri)}; " +
                    "hostInicial=${safeHost(source)}; tipoInicial=${mediaKind(source)}; " +
                    "redirecionamentoEntreHosts=${failedUri?.host != null && source?.host != null && failedUri.host != source.host}; " +
                    "respostaHeaderNames=${responseHeaderNames.sorted().joinToString(",")}")
            }
        })
        player = exo
        lastMediaTickMs = SystemClock.elapsedRealtime()
        attentionHandler.post(attentionCheck)
        val openPlayer = android.content.Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_MEDIA_PLAYER
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = android.app.PendingIntent.getActivity(
            this, 101, openPlayer,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Parar todas as mídias")
            .setSessionCommand(stopAllCommand)
            .build()
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(listOf(stopButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(stopAllCommand).build())
                        .build()

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_STOP_ALL) {
                        StopAllMediaReceiver.stopAll(this@Media3PlaybackService)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        if (liveService === this) liveService = null
        player?.let(::savePlaybackPosition)
        attentionHandler.removeCallbacks(attentionCheck)
        Attention.mediaPlaybackReset()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
