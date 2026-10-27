package com.example.extbrowser

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Janela PiP em Activity separada: MainActivity permanece livre para navegar.
 * Conecta à sessão Media3 já existente, sem criar outro player ou reiniciar mídia.
 */
class VideoPipActivity : Activity() {
    companion object {
        @Volatile var active = false
            private set
        private var liveWindow: java.lang.ref.WeakReference<VideoPipActivity>? = null

        fun closeExistingWindow() {
            liveWindow?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finishAndRemoveTask()
                }
            }
        }
    }
    private lateinit var videoSurface: SurfaceView
    private lateinit var info: TextView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        liveWindow = java.lang.ref.WeakReference(this)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        videoSurface = SurfaceView(this)
        root.addView(videoSurface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        info = TextView(this).apply {
            text = "Conectando vídeo..."
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(24, 24, 24, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { restoreMainPlayer() }
        }
        root.addView(info, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val c = runCatching { future.get() }.getOrNull()
            if (c?.currentMediaItem == null) {
                info.text = "Nenhum vídeo ativo\nToque para voltar ao navegador"
                return@addListener
            }
            controller = c
            c.setVideoSurfaceView(videoSurface)
            info.visibility = View.GONE
            if (savedInstanceState == null) enterVideoPip()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enterVideoPip() {
        if (!packageManager.hasSystemFeature("android.software.picture_in_picture")) {
            info.text = "PiP indisponível neste dispositivo\nToque para voltar ao player"
            info.visibility = View.VISIBLE
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(currentVideoRatio())
            .setAutoEnterEnabled(false)
            .setSeamlessResizeEnabled(false)
            .build()
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) {
            info.text = "Não foi possível abrir a janela PiP\nToque para voltar ao player"
            info.visibility = View.VISIBLE
        }
    }

    /** Respeita vídeos verticais e quadrados, com proporção aceita pela janela PiP. */
    private fun currentVideoRatio(): Rational {
        val size = controller?.videoSize ?: return Rational(16, 9)
        if (size.width <= 0 || size.height <= 0) return Rational(16, 9)
        val measuredRatio = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
        if (!measuredRatio.isFinite() || measuredRatio <= 0f) return Rational(16, 9)
        val ratio = measuredRatio.coerceIn(0.43f, 2.3f)
        return Rational((ratio * 1000f).roundToInt().coerceAtLeast(1), 1000)
    }

    /** Expandir o PiP restaura diretamente o player principal, sem tela intermediária. */
    private fun restoreMainPlayer() {
        if (isFinishing || isDestroyed) return
        // Libera a posse visual antes do onNewIntent na Activity principal.
        active = false
        controller?.let { runCatching { it.clearVideoSurfaceView(videoSurface) } }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_MEDIA_PLAYER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finishAndRemoveTask()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, configuration: Configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration)
        info.visibility = if (inPip || controller != null) View.GONE else View.VISIBLE
        if (!inPip && !isFinishing && !isChangingConfigurations && controller != null) {
            restoreMainPlayer()
        }
    }

    override fun onDestroy() {
        if (liveWindow?.get() === this) liveWindow = null
        active = false
        controller?.let { runCatching { it.clearVideoSurfaceView(videoSurface) } }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}
