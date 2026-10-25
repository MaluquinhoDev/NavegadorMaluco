package com.example.extbrowser

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Janela PiP em Activity separada: MainActivity permanece livre para navegar.
 * Conecta à sessão Media3 já existente, sem criar outro player ou reiniciar mídia.
 */
class VideoPipActivity : Activity() {
    private lateinit var videoSurface: SurfaceView
    private lateinit var controls: LinearLayout
    private lateinit var info: TextView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        videoSurface = SurfaceView(this)
        root.addView(videoSurface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x44000000)
        }
        info = TextView(this).apply {
            text = "Conectando vídeo..."
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        controls.addView(info, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val toggle = Button(this).apply {
            text = "Play / Pausa"
            setOnClickListener {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
        actions.addView(toggle)
        actions.addView(Button(this).apply {
            text = "Voltar ao navegador"
            setOnClickListener { finish() }
        })
        controls.addView(actions)
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val c = runCatching { future.get() }.getOrNull()
            if (c?.currentMediaItem == null) {
                info.text = "Nenhum vídeo ativo"
                return@addListener
            }
            controller = c
            c.setVideoSurfaceView(videoSurface)
            info.visibility = View.GONE
            if (packageManager.hasSystemFeature("android.software.picture_in_picture")) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(false)
                    .setSeamlessResizeEnabled(false)
                    .build()
                runCatching { enterPictureInPictureMode(params) }
                    .onFailure { info.text = "PiP indisponível neste dispositivo"; info.visibility = View.VISIBLE }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, configuration: Configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration)
        controls.visibility = if (inPip) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        controller?.let { runCatching { it.clearVideoSurfaceView(videoSurface) } }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}
