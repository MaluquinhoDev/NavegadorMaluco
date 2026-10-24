package com.example.extbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
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
    private class Blocker(val name: String, val regex: Regex)
    private data class Tab(val view: WebView, var title: String = "Nova aba", var url: String = HOME)

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = 0
    private val webView get() = tabs[activeIndex].view
    private lateinit var webHost: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView
    private lateinit var tabsButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var store: BrowserData
    private var statusDefaultColor = 0
    private var loaded = false
    private var blockerList: List<Blocker> = emptyList()
    private var extensionList: List<Extension> = emptyList()
    private var extensionManager: ExtensionManager? = null
    private var lastBlockMs = 0L
    private var servedBlockUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object { private const val HOME = "https://www.wikipedia.org"; private const val MAX_TABS = 6 }

    private val ticker = object : Runnable {
        override fun run() {
            if (tabs.isNotEmpty()) {
                val verdict = Attention.onUrl(webView.url) ?: Attention.tick()
                if (verdict == null) servedBlockUrl = null
                else if (webView.url != servedBlockUrl) showBlocked(verdict)
                updateStatus()
            }
            handler.postDelayed(this, 5000)
        }
    }

    private val extLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            persistTabs()
            recreate()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Attention.init(this)
        store = BrowserData(this)
        extensionManager = ExtensionManager(this).also { it.seedFromAssets() }
        extensionList = extensionManager!!.loadEnabled()
        blockerList = extensionList.flatMap { e -> e.blockRegexes.map { Blocker(e.name, Regex(it)) } }

        urlBar = EditText(this).apply {
            setSingleLine()
            hint = "URL ou busca"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        val extButton = Button(this).apply {
            text = "Ext"
            setOnClickListener { extLauncher.launch(Intent(this@MainActivity, ExtensionsActivity::class.java)) }
        }
        tabsButton = Button(this).apply { text = "Abas"; setOnClickListener { showTabs() } }
        favoriteButton = Button(this).apply { text = "☆"; setOnClickListener { toggleFavorite() } }
        val savedButton = Button(this).apply { text = "☰"; setOnClickListener { showLibrary() } }
        // Duas linhas: a barra de URL ocupa toda a largura disponível.
        // Os botões ficam separados para não comprimir a digitação no celular.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(favoriteButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(tabsButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(savedButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(extButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        statusView = TextView(this).apply { textSize = 12f; visibility = View.GONE; setPadding(16, 4, 16, 4) }
        statusDefaultColor = statusView.currentTextColor
        webHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val prefs = getSharedPreferences("tabs", MODE_PRIVATE)
        val urls = prefs.getString("urls", "")!!.split('\n').filter { it.startsWith("https://") || it.startsWith("http://") }.take(MAX_TABS)
        val initial = urls.ifEmpty { listOf(HOME) }
        for (url in initial) tabs.add(Tab(createWebView(), url = url))
        activeIndex = prefs.getInt("active", 0).coerceIn(0, tabs.lastIndex)
        showActive()
        initial.forEachIndexed { index, url -> if (index == activeIndex) tabs[index].view.loadUrl(url) }
        loaded = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                val t = tabs.firstOrNull { it.view === v } ?: return
                t.url = url
                if (v === webView && !urlBar.hasFocus()) urlBar.setText(url)
                if (v === webView) updateFavorite()
            }
            override fun onPageFinished(v: WebView, url: String) {
                val t = tabs.firstOrNull { it.view === v } ?: return
                t.title = v.title ?: url
                t.url = url
                if (v === webView) { updateFavorite(); persistTabs() }
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                if (!isReload && (url.startsWith("https://") || url.startsWith("http://")))
                    store.addHistory(url, v.title ?: url)
                if (v === webView) Attention.onUrl(url)?.let { showBlocked(it) }
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme != "extbrowser") return false
                if (v === webView) handleBreakAction(request.url)
                return true
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val hit = blockerList.firstOrNull { it.regex.matches(url) }
                if (hit != null) return if (request.isForMainFrame)
                    htmlResponse(BlockPage.html("Bloqueado", hit.name, ""))
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                if (request.isForMainFrame) {
                    val verdict = Attention.checkNavigation(url)
                    if (verdict != null) return htmlResponse(blockHtmlFor(verdict))
                }
                return null
            }
        }
        setupExtensions(view)
        return view
    }

    private fun setupExtensions(view: WebView) {
        val manager = extensionManager ?: return
        val origins = manager.allowedOrigins(extensionList)
        if (origins.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(this, "WebView sem suporte para extensões", Toast.LENGTH_LONG).show()
            return
        }
        val router = BridgeRouter(this, extensionList)
        WebViewCompat.addWebMessageListener(view, "__extBridge", origins, object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(v: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                if (!isMainFrame) return
                val data = message.data ?: return
                replyProxy.postMessage(router.handle(data, sourceOrigin))
            }
        })
        WebViewCompat.addDocumentStartJavaScript(view, manager.buildBootstrap(extensionList), origins)
    }

    private fun showActive() {
        Attention.pause()
        val newView = webView
        if (webHost.childCount > 0) {
            val old = webHost.getChildAt(0) as WebView
            old.onPause()
            webHost.removeView(old)
        }
        (newView.parent as? android.view.ViewGroup)?.removeView(newView)
        webHost.addView(newView, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        newView.onResume()
        if (newView.url == null) newView.loadUrl(tabs[activeIndex].url)
        urlBar.setText(newView.url ?: tabs[activeIndex].url)
        tabsButton.text = "${tabs.size} abas"
        servedBlockUrl = null
        lastBlockMs = 0
        updateFavorite()
        updateStatus()
        persistTabs()
    }

    private fun newTab(url: String = HOME) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, "Limite de $MAX_TABS abas para reduzir uso de memória", Toast.LENGTH_LONG).show()
            return
        }
        Attention.pause()
        tabs.add(Tab(createWebView(), url = url))
        activeIndex = tabs.lastIndex
        showActive()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.removeAt(index)
        if (webHost.childCount > 0 && webHost.getChildAt(0) === tab.view) webHost.removeView(tab.view)
        tab.view.stopLoading()
        tab.view.destroy()
        if (tabs.isEmpty()) { tabs.add(Tab(createWebView())) ; activeIndex = 0 }
        else activeIndex = when {
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> activeIndex.coerceAtMost(tabs.lastIndex)
            else -> activeIndex
        }
        showActive()
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t -> "${i + 1}. ${t.title.take(50)}${if (i == activeIndex) " ✓" else ""}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Abas (${tabs.size}/$MAX_TABS)")
            .setItems(names) { _, selected -> activeIndex = selected; showActive() }
            .setPositiveButton("Nova aba") { _, _ -> newTab() }
            .setNeutralButton("Fechar aba atual") { _, _ -> closeTab(activeIndex) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun toggleFavorite() {
        val url = webView.url ?: return
        if (store.isFavorite(url)) store.removeFavorite(url)
        else store.addFavorite(url, webView.title ?: url)
        updateFavorite()
    }
    private fun updateFavorite() {
        favoriteButton.text = if (tabs.isNotEmpty() && store.isFavorite(webView.url ?: "")) "★" else "☆"
    }
    private fun showLibrary() {
        AlertDialog.Builder(this).setTitle("Biblioteca")
            .setItems(arrayOf("Favoritos", "Histórico")) { _, which -> showSavedPages(which == 0) }
            .show()
    }
    private fun showSavedPages(favorites: Boolean) {
        val pages = if (favorites) store.favorites() else store.history()
        if (pages.isEmpty()) { Toast.makeText(this, "Nenhum registro", Toast.LENGTH_SHORT).show(); return }
        val labels = pages.map { (it.title.ifBlank { it.url }).take(60) + "\n" + it.url.take(75) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if (favorites) "Favoritos" else "Histórico")
            .setItems(labels) { _, index -> navigate(pages[index].url) }
            .setNeutralButton(if (favorites) "Remover favorito" else "Limpar histórico") { _, _ ->
                if (favorites) showRemoveFavorite(pages) else AlertDialog.Builder(this)
                    .setTitle("Apagar todo o histórico?")
                    .setPositiveButton("Apagar") { _, _ -> store.clearHistory() }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Voltar", null).show()
    }
    private fun showRemoveFavorite(pages: List<SavedPage>) {
        AlertDialog.Builder(this).setTitle("Remover favorito")
            .setItems(pages.map { it.title }.toTypedArray()) { _, index ->
                store.removeFavorite(pages[index].url)
                updateFavorite()
            }.show()
    }

    private fun persistTabs() {
        if (!loaded) return
        getSharedPreferences("tabs", MODE_PRIVATE).edit()
            .putString("urls", tabs.joinToString("\n") { it.view.url ?: it.url })
            .putInt("active", activeIndex).apply()
    }

    private fun navigate(input: String) {
        val t = input.trim()
        if (t.isBlank()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        webView.loadUrl(url)
        urlBar.clearFocus()
    }

    private fun htmlResponse(html: String) = WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    private fun blockHtmlFor(v: AttentionVerdict): String = if (v.untilMs > 0L)
        BlockPage.breakHtml(v, Attention.dismissWaitLeft(v.site.id))
    else BlockPage.html("Acesso encerrado", v.site.domain, v.reason)
    private fun loadBlock(v: AttentionVerdict) {
        servedBlockUrl = null
        lastBlockMs = SystemClock.elapsedRealtime()
        webView.loadDataWithBaseURL(null, blockHtmlFor(v), "text/html", "utf-8", null)
    }
    private fun showBlocked(v: AttentionVerdict) {
        if (SystemClock.elapsedRealtime() - lastBlockMs < 3000) return
        loadBlock(v)
    }
    private fun handleBreakAction(u: Uri) {
        val id = u.pathSegments.firstOrNull() ?: return
        val site = Attention.sites().firstOrNull { it.id == id } ?: return
        when (u.host) {
            "dispensar" -> {
                Attention.requestDismiss(id)
                Attention.verdictForSite(id)?.let { loadBlock(it) } ?: webView.loadUrl("https://${site.domain}/")
            }
            "confirmar" -> {
                if (Attention.confirmDismiss(id)) webView.loadUrl("https://${site.domain}/")
                else Attention.verdictForSite(id)?.let { loadBlock(it) } ?: webView.loadUrl("https://${site.domain}/")
            }
        }
    }
    private fun updateStatus() {
        if (tabs.isEmpty()) return
        val st = Attention.status(webView.url)
        if (st == null) { statusView.visibility = View.GONE; return }
        val s = st.site
        val used = AttentionLogic.formatTime(st.usedSeconds)
        val today = if (s.dailyMinutes > 0) "$used de ${s.dailyMinutes} min" else "$used (sem limite)"
        val session = AttentionLogic.formatTime(st.sessionSeconds) +
            (if (st.sessionLimitSeconds > 0) " de ${s.sessionMinutes} min" else "")
        var line = "⏳ ${s.domain} · hoje $today · sessão $session"
        val remaining = st.sessionLimitSeconds - st.sessionSeconds
        val lastMinute = st.sessionLimitSeconds > 0 && remaining <= 60
        if (lastMinute) line += " · falta ${AttentionLogic.formatTime(remaining)}, depois pausa de ${st.breakMinutes} min"
        statusView.text = line
        statusView.setTextColor(if (lastMinute) 0xFFC62828.toInt() else statusDefaultColor)
        statusView.visibility = View.VISIBLE
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); Attention.pause(); persistTabs(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        tabs.forEach { (it.view.parent as? android.view.ViewGroup)?.removeView(it.view); it.view.destroy() }
        store.close()
        super.onDestroy()
    }
}
