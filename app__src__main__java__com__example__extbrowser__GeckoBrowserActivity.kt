package com.example.extbrowser

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Firefox real para o chat; não altera nem compartilha o estado da WebView Chromium. */
class GeckoBrowserActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "gecko_url"
        private const val CHAT_URL = "https://seuimposto.com/#chat"

        @Volatile private var sharedRuntime: GeckoRuntime? = null

        private fun runtime(activity: GeckoBrowserActivity): GeckoRuntime =
            sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: GeckoRuntime.create(activity.applicationContext).also {
                    sharedRuntime = it
                }
            }
    }

    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        val back = Button(this).apply { text = "Voltar" }
        val reload = Button(this).apply { text = "Atualizar" }
        val close = Button(this).apply { text = "Fechar" }
        toolbar.addView(back)
        toolbar.addView(reload)
        toolbar.addView(TextView(this).apply {
            text = "Seu Imposto · Firefox"
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        toolbar.addView(close)

        val view = GeckoView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        val geckoSession = GeckoSession()
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {}
        geckoSession.open(runtime(this))
        view.setSession(geckoSession)
        session = geckoSession

        val requested = intent.getStringExtra(EXTRA_URL).orEmpty()
        val uri = runCatching { Uri.parse(requested) }.getOrNull()
        val target = if (uri?.scheme == "https" &&
            (uri.host == "seuimposto.com" || uri.host == "www.seuimposto.com")) {
            requested
        } else CHAT_URL
        geckoSession.loadUri(target)

        back.setOnClickListener { geckoSession.goBack() }
        reload.setOnClickListener { geckoSession.reload() }
        close.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
