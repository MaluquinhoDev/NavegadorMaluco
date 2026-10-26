package com.example.extbrowser

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun toolButton(symbol: String, description: String) = Button(this).apply {
            text = symbol
            contentDescription = description
            textSize = 19f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
        }
        val back = toolButton("‹", "Voltar no Firefox")
        val forward = toolButton("›", "Avançar no Firefox")
        val reload = toolButton("⟳", "Atualizar página")
        val close = toolButton("×", "Fechar Firefox")
        back.isEnabled = false
        forward.isEnabled = false
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(forward, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "Seu Imposto · Firefox"
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        toolbar.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))

        val view = GeckoView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { container, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            container.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val geckoSession = GeckoSession()
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {}
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, allowed: Boolean) {
                canGoBack = allowed
                back.isEnabled = allowed
            }

            override fun onCanGoForward(session: GeckoSession, allowed: Boolean) {
                forward.isEnabled = allowed
            }
        }
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

        back.setOnClickListener { if (canGoBack) geckoSession.goBack() }
        forward.setOnClickListener { if (forward.isEnabled) geckoSession.goForward() }
        reload.setOnClickListener { geckoSession.reload() }
        close.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) geckoSession.goBack() else finish()
            }
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
