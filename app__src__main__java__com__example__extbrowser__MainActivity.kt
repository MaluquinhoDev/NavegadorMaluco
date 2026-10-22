package com.example.extbrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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

    /** Regras de bloqueio das extensões (campo block_requests). Lidas em thread de rede. */
    @Volatile
    private var blockers: List<Blocker> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "URL ou busca"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        webView = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val hit = blockers.firstOrNull { it.regex.matches(url) } ?: return null
                return if (request.isForMainFrame) {
                    val html = "<!doctype html><meta charset=utf-8>" +
                        "<meta name=viewport content='width=device-width,initial-scale=1'>" +
                        "<body style='font:18px sans-serif;padding:40px 24px;text-align:center'>" +
                        "<h2>Bloqueado</h2><p>" + TextUtils.htmlEncode(hit.extName) + "</p></body>"
                    WebResourceResponse(
                        "text/html", "utf-8", 200, "OK", emptyMap(),
                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
                    )
                } else {
                    WebResourceResponse(
                        "text/plain", "utf-8", 403, "Blocked", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
            }
        }

        setupExtensions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        webView.loadUrl("https://www.wikipedia.org")
    }

    private fun setupExtensions() {
        val manager = ExtensionManager(this)
        manager.seedFromAssets()
        val extensions = manager.load()

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
        webView.destroy()
        super.onDestroy()
    }
}
