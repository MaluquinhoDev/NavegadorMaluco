package com.example.extbrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    private class Blocker(val extName: String, val regex: Regex)

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView

    /** Regras de bloqueio das extensões (campo block_requests). Lidas em thread de rede. */
    @Volatile
    private var blockers: List<Blocker> = emptyList()

    /** Instante do último bloqueio exibido; evita mostrar a mesma página de bloqueio duas vezes seguidas. */
    @Volatile
    private var lastBlockMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** A cada 5 s: confere a página atual, conta o tempo e aplica os limites do controle de atenção. */
    private val ticker = object : Runnable {
        override fun run() {
            val verdict = Attention.onUrl(webView.url) ?: Attention.tick()
            if (verdict != null) showBlocked(verdict)
            updateStatus()
            handler.postDelayed(this, 5000)
        }
    }

    /** Ao voltar da tela de extensões com mudanças, recria a tela para aplicá-las. */
    private val extLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) recreate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Attention.init(this)

        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "URL ou busca"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        val extButton = Button(this).apply {
            text = "Ext"
            setOnClickListener { extLauncher.launch(Intent(this@MainActivity, ExtensionsActivity::class.java)) }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(extButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(16, 4, 16, 4)
            visibility = View.GONE
        }
        webView = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // Android 15+ força edge-to-edge: aplicar os insets manualmente.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect; remova se não precisar
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!urlBar.hasFocus()) urlBar.setText(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                Attention.onUrl(url)?.let { showBlocked(it) }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                val hit = blockers.firstOrNull { it.regex.matches(url) }
                if (hit != null) {
                    return if (request.isForMainFrame) {
                        htmlResponse(BlockPage.html("Bloqueado", hit.extName, ""))
                    } else {
                        WebResourceResponse(
                            "text/plain", "utf-8", 403, "Blocked", emptyMap(),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }

                if (request.isForMainFrame) {
                    val verdict = Attention.checkNavigation(url)
                    if (verdict != null) {
                        lastBlockMs = SystemClock.elapsedRealtime()
                        return htmlResponse(BlockPage.html("Acesso encerrado", verdict.site.domain, verdict.reason))
                    }
                }
                return null
            }
        }

        setupExtensions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        webView.loadUrl(savedInstanceState?.getString("url") ?: "https://www.wikipedia.org")
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        Attention.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.url?.let { outState.putString("url", it) }
    }

    private fun htmlResponse(html: String) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK", emptyMap(),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )

    private fun showBlocked(v: AttentionVerdict) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockMs < 3000) return
        lastBlockMs = now
        webView.loadDataWithBaseURL(
            null,
            BlockPage.html("Acesso encerrado", v.site.domain, v.reason),
            "text/html", "utf-8", null,
        )
    }

    private fun updateStatus() {
        val st = Attention.status(webView.url)
        if (st == null) {
            statusView.visibility = View.GONE
            return
        }
        val s = st.site
        val used = AttentionLogic.formatTime(st.usedSeconds)
        val today = if (s.dailyMinutes > 0) "$used de ${s.dailyMinutes} min" else "$used (sem limite)"
        statusView.text = "⏳ ${s.domain} · hoje $today · sessão ${AttentionLogic.formatTime(st.sessionSeconds)}"
        statusView.visibility = View.VISIBLE
    }

    private fun setupExtensions() {
        val manager = ExtensionManager(this)
        manager.seedFromAssets()
        val extensions = manager.loadEnabled()

        // Bloqueio de requisições: não depende das APIs de content script.
        blockers = extensions.flatMap { e -> e.blockRegexes.map { Blocker(e.name, Regex(it)) } }

        val origins = manager.allowedOrigins(extensions)
        Log.i("ExtBrowser", "Extensões: ${extensions.map { it.id }}, bloqueios: ${blockers.size}, origens: $origins")
        if (origins.isEmpty()) return

        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!supported) {
            Toast.makeText(this, "WebView sem suporte às APIs necessárias. Atualize o Android System WebView.", Toast.LENGTH_LONG).show()
            return
        }

        val router = BridgeRouter(this, extensions)
        WebViewCompat.addWebMessageListener(webView, "__extBridge", origins, object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy,
            ) {
                if (!isMainFrame) return
                val data = message.data ?: return
                replyProxy.postMessage(router.handle(data, sourceOrigin))
            }
        })
        WebViewCompat.addDocumentStartJavaScript(webView, manager.buildBootstrap(extensions), origins)
    }

    private fun navigate(input: String) {
        val t = input.trim()
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        webView.loadUrl(url)
        urlBar.clearFocus()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        webView.destroy()
        super.onDestroy()
    }
}
