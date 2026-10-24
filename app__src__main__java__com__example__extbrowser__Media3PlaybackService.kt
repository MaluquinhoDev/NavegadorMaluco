package com.example.extbrowser

import android.net.Uri
import android.util.Log
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
import java.util.Locale

/** Diagnóstico seguro: não imprime URLs completas, queries, cookies ou credenciais. */
class Media3PlaybackService : MediaSessionService() {
    companion object { private const val TAG = "NavegadorMedia3" }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(WebSettings.getDefaultUserAgent(this))
        val dataSource = DefaultDataSource.Factory(this, http)
        val mediaSources = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSources).build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val origin = item?.mediaMetadata?.extras?.getString("nm_page_origin") ?: ""
                val mediaUri = item?.localConfiguration?.uri
                val page = runCatching { Uri.parse(origin) }.getOrNull()
                // Preserva a política anterior: Referer somente com origem HTTPS.
                val headers = if (page?.scheme == "https" && !page.host.isNullOrBlank() &&
                    mediaUri?.scheme == "https") mapOf("Referer" to origin) else emptyMap()
                http.setDefaultRequestProperties(headers)
                Log.i(TAG, "Fonte inicial: host=${safeHost(mediaUri)}; tipo=${mediaKind(mediaUri)}; " +
                    "referer=${headers.containsKey("Referer")}; userAgent=true; " +
                    "urlComParametros=${mediaUri?.query != null}")
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
                // Navega por causas conhecidas, sem incluir exception.message:
                // mensagens e URLs podem conter tokens temporários.
                val seen = HashSet<Throwable>()
                while (cause != null && seen.add(cause)) {
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        httpStatus = cause.responseCode
                        failedUri = cause.dataSpec.uri
                        responseHeaderNames = cause.headerFields.keys
                            .filter { it.isNotBlank() }.map { it.lowercase(Locale.ROOT) }.toSet()
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
                // Não reproduz mensagens completas do ExoPlayer, que podem conter URLs privadas.
            }
        })
        player = exo
        session = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
