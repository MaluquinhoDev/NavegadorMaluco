package com.example.extbrowser

import android.util.Log
import android.net.Uri
import android.webkit.WebSettings
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Media3 independente da WebView. Envia somente headers públicos, sem cookies/tokens. */
class Media3PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val webUserAgent = WebSettings.getDefaultUserAgent(this)
        val http = DefaultHttpDataSource.Factory().setUserAgent(webUserAgent)
        val dataSource = DefaultDataSource.Factory(this, http)
        val mediaSources = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSources).build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val origin = item?.mediaMetadata?.extras?.getString("nm_page_origin") ?: ""
                val mediaUri = item?.localConfiguration?.uri
                val page = runCatching { Uri.parse(origin) }.getOrNull()
                // Nunca passar origem não HTTP(S) nem a URL completa da página.
                val headers = if (page?.scheme == "https" && !page.host.isNullOrBlank() &&
                    mediaUri?.scheme == "https") mapOf("Referer" to origin) else emptyMap()
                http.setDefaultRequestProperties(headers)
                Log.i("NavegadorMedia3", "Origem HTTPS: ${if (headers.isEmpty()) "não" else "sim"}; mídia: ${mediaUri?.host ?: "indefinida"}")
            }
            override fun onPlayerError(error: PlaybackException) {
                // Não incluir URL completa: endereços de mídia podem carregar tokens.
                var cause: Throwable? = error
                var status = "sem código HTTP"
                while (cause != null) {
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        status = "HTTP ${cause.responseCode}"
                        break
                    }
                    cause = cause.cause
                }
                Log.e("NavegadorMedia3", "Falha: $status; ${error.errorCodeName}")
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
