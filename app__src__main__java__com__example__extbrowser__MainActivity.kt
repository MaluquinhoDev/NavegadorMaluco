package com.example.extbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import android.content.Intent
import org.json.JSONArray
import android.app.DownloadManager
import android.content.Context
import android.content.res.Configuration
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import android.webkit.WebSettings
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
import java.util.WeakHashMap
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {
    private class Blocker(val name: String, val regex: Regex)
    private data class Tab(val view: WebView, var title: String = "Nova aba", var url: String = HOME)

    private val tabs = mutableListOf<Tab>()
    // WebViews em espera pelo transporte de uma nova janela.
    private val pendingPopups = mutableSetOf<WebView>()
    private var activeIndex = 0
    private val webView get() = tabs[activeIndex].view
    private lateinit var webHost: LinearLayout
    private lateinit var browserBar: LinearLayout
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
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    // Opcional: mantém WebViews ativas para reprodução HTML5 após trocar de aba.
    // Não contorna restrições de sites nem garante execução após o Android suspender o app.
    private var backgroundAudioEnabled = false
    // Apenas metadados de requisições. Nunca intercepta conteúdo, tokens nem corpos HTTP.
    private val mediaCandidates = WeakHashMap<WebView, LinkedHashSet<String>>()
    private val mediaCandidateLock = Any()
    // Domínio do Referer observado por fonte, sem valores completos de cabeçalhos.
    private val mediaRequestOrigins = WeakHashMap<WebView, LinkedHashMap<String, String>>()
    private fun refererHost(headers: Map<String, String>): String {
        val value = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
            ?: return "não informado"
        return runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme in listOf("https", "http")) uri.host ?: "não informado"
            else "não informado"
        }.getOrDefault("não informado")
    }

    private val mediaExtension = Regex("(?i)\\.(mp3|m4a|aac|ogg|oga|opus|wav|flac|mp4|m4v|webm|m3u8|mpd)(?:[?#]|$)")


    // Esta classificação é apenas indicativa: detecção não confirma acesso à fonte.
    private fun detectedMediaKind(url: String): String {
        val path = Uri.parse(url).path?.lowercase(java.util.Locale.ROOT).orEmpty()
        return when {
            path.endsWith(".m3u8") -> "HLS · playlist"
            path.endsWith(".mpd") -> "DASH · manifesto"
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") -> "Vídeo direto"
            else -> "Áudio"
        }
    }
    private fun detectedMediaRank(url: String): Int = when (detectedMediaKind(url)) {
        "Vídeo direto" -> 0
        "HLS · playlist" -> 1
        "DASH · manifesto" -> 2
        else -> 3
    }

    // O Android devolve aqui o arquivo escolhido em formulários <input type="file">.
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == RESULT_OK)
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        else null
        fileCallback?.onReceiveValue(selected)
        fileCallback = null
    }

    companion object {
        private const val HOME = "https://www.wikipedia.org"
        private const val MAX_TABS = 6
        const val ACTION_OPEN_MEDIA_PLAYER = "com.example.extbrowser.OPEN_MEDIA_PLAYER"
    }

    /** A notificação Media3 abre a sessão já existente, sem reiniciar o streaming. */
    private fun openPlayerFromMediaNotification(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_MEDIA_PLAYER || !loaded) return
        intent.action = Intent.ACTION_MAIN  // evita reabrir em alterações da Activity
        val future = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        ).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                if (!isFinishing && !isDestroyed && controller.currentMediaItem != null) {
                    showNativeVideoPlayer()
                } else if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Nenhuma reprodução ativa para retomar", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.w("NavegadorMedia3", "Não foi possível abrir sessão da notificação", e)
            } finally {
                MediaController.releaseFuture(future)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPlayerFromMediaNotification(intent)
    }

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
        backgroundAudioEnabled = getSharedPreferences("media_options", MODE_PRIVATE)
            .getBoolean("background_audio", false)
        if (backgroundAudioEnabled) {
            // Reestabelece a proteção do processo após recriação normal da Activity.
            runCatching { androidx.core.content.ContextCompat.startForegroundService(
                this, Intent(this, BackgroundAudioService::class.java)) }
        }
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
        browserBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        statusView = TextView(this).apply { textSize = 12f; visibility = View.GONE; setPadding(16, 4, 16, 4) }
        statusDefaultColor = statusView.currentTextColor
        webHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(browserBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(webHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            miniPlayerBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(12, 4, 12, 4)
                setBackgroundColor(0xFF202B3A.toInt())
            }
            val title = TextView(this@MainActivity).apply {
                text = "▶ Mídia em reprodução"
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener { showNativeVideoPlayer() }
            }
            miniPlayerBar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            miniPlayButton = Button(this@MainActivity).apply {
                text = "Pausar"
                setOnClickListener {
                    miniController?.let { if (it.isPlaying) it.pause() else it.play() }
                }
            }
            miniPlayerBar.addView(miniPlayButton)
            miniPlayerBar.addView(Button(this@MainActivity).apply {
                text = "Abrir vídeo"
                setOnClickListener { showNativeVideoPlayer() }
            })
            addView(miniPlayerBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        playerLayer = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        setContentView(playerLayer)
        updatePipAutoEnter(false)
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
        connectMiniPlayer()
        openPlayerFromMediaNotification(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerPanel != null) closeNativePlayer()
                else if (fullscreenView != null) exitFullscreen()
                else if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.setSupportMultipleWindows(true)
        // target=_blank funciona, mas window.open automático sem gesto fica desabilitado.
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                val t = tabs.firstOrNull { it.view === v } ?: return
                synchronized(mediaCandidateLock) { mediaCandidates.remove(v); mediaRequestOrigins.remove(v) }
                t.url = url
                if (v === webView && !urlBar.hasFocus()) urlBar.setText(url)
                if (v === webView) updateFavorite()
            }
            override fun onPageFinished(v: WebView, url: String) {
                val t = tabs.firstOrNull { it.view === v } ?: return
                t.title = v.title ?: url
                t.url = url
                if (v === webView) { updateFavorite(); persistTabs() }
                scanDeclaredMedia(v)
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                if (!isReload && (url.startsWith("https://") || url.startsWith("http://")))
                    store.addHistory(url, v.title ?: url)
                if (v === webView) Attention.onUrl(url)?.let { showBlocked(it) }
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (target.scheme == "extbrowser") {
                    if (v === webView) handleBreakAction(target)
                    return true
                }
                if (target.scheme == "http" || target.scheme == "https") return false
                if (!request.isForMainFrame) return false
                return openExternalLink(target)
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                // Chamado em thread auxiliar. Observa apenas URLs aparentes de mídia,
                // mantendo o mecanismo de rede e bloqueio existentes inalterados.
                if (!request.isForMainFrame &&
                    (request.url.scheme == "https" || request.url.scheme == "http") &&
                    mediaExtension.containsMatchIn(url)) {
                    synchronized(mediaCandidateLock) {
                        val found = mediaCandidates.getOrPut(v) { LinkedHashSet() }
                        if (found.size >= 60) found.remove(found.first())
                        found.add(url)
                        val origins = mediaRequestOrigins.getOrPut(v) { LinkedHashMap() }
                        if (origins.size >= 60 && !origins.containsKey(url))
                            origins.remove(origins.keys.first())
                        origins[url] = refererHost(request.requestHeaders)
                    }
                }
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
        // Upload de arquivos e vídeos HTML5 em tela cheia.
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                source: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                // Não permitir pop-ups automáticos nem exceder o limite de abas.
                if (!isUserGesture || resultMsg == null || tabs.size >= MAX_TABS) {
                    if (tabs.size >= MAX_TABS)
                        Toast.makeText(this@MainActivity, "Limite de $MAX_TABS abas", Toast.LENGTH_SHORT).show()
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                // Uma nova WebView recebe a navegação diretamente do mecanismo Android.
                // Não reconstruímos URL, preservando POST e scripts de abertura.
                val popup = createWebView()
                tabs.add(Tab(popup, title = "Nova aba", url = "about:blank"))
                activeIndex = tabs.lastIndex
                pendingPopups.add(popup)
                showActive()
                transport.webView = popup
                resultMsg.sendToTarget()
                // Após o transporte, a WebView passa a carregar a página normalmente.
                pendingPopups.remove(popup)
                return true
            }
            override fun onCloseWindow(window: WebView?) {
                val index = tabs.indexOfFirst { it.view === window }
                if (index >= 0) closeTab(index)
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                if (filePathCallback == null || fileChooserParams == null) return false
                try {
                    filePicker.launch(fileChooserParams.createIntent())
                } catch (e: ActivityNotFoundException) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    Toast.makeText(this@MainActivity, "Nenhum seletor de arquivos disponível", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    Toast.makeText(this@MainActivity, "Não foi possível selecionar o arquivo", Toast.LENGTH_LONG).show()
                }
                return true
            }
            override fun onShowCustomView(customView: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null || customView == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = customView
                fullscreenCallback = callback
                val decor = window.decorView as FrameLayout
                decor.addView(customView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                customView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                // Só habilita a transição automática após anexar o player à janela.
                customView.post {
                    if (fullscreenView === customView) updatePipAutoEnter(true)
                }
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
        setupExtensions(view)
        return view
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        fullscreenView = null
        v.systemUiVisibility = 0
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        updatePipAutoEnter(false)
    }

    // Usa o gerenciador de downloads do Android, sem solicitar acesso irrestrito ao armazenamento.
    private fun enqueueDownload(url: String, agent: String?, disposition: String?, mime: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" && uri.scheme != "http") {
            Toast.makeText(this, "Este tipo de download ainda não é suportado", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
                .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_")
            val request = DownloadManager.Request(uri)
                .setTitle(name)
                .setDescription("NavegadorMaluco")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrBlank()) request.setMimeType(mime)
            if (!agent.isNullOrBlank()) request.addRequestHeader("User-Agent", agent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Download iniciado: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao iniciar o download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Encaminha apenas esquemas externos reconhecidos para aplicativos do Android. */
    private fun openExternalLink(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        val allowed = setOf("mailto", "tel", "sms", "smsto", "geo", "market")
        if (scheme !in allowed && scheme != "intent") return true
        try {
            if (scheme == "intent") {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    val fallbackUri = fallback?.let { Uri.parse(it) }
                    if (fallbackUri?.scheme == "https" || fallbackUri?.scheme == "http") {
                        webView.loadUrl(fallback!!)
                    } else {
                        Toast.makeText(this, "Aplicativo necessário não instalado", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir este link", Toast.LENGTH_SHORT).show()
        }
        return true
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
            // Evita suspender áudio/vídeo HTML5 ao trocar de aba quando habilitado.
            if (!backgroundAudioEnabled) old.onPause()
            webHost.removeView(old)
        }
        (newView.parent as? android.view.ViewGroup)?.removeView(newView)
        webHost.addView(newView, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        newView.onResume()
        if (newView.url == null && newView !in pendingPopups) newView.loadUrl(tabs[activeIndex].url)
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
        pendingPopups.remove(tab.view)
        synchronized(mediaCandidateLock) { mediaCandidates.remove(tab.view) }
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
    /** Atalho de mídia: escolhe automaticamente somente quando há uma fonte inequívoca. */
    private fun playPageMediaDirectly() {
        val current = webView
        scanDeclaredMedia(current) {
            if (tabs.none { it.view === current }) return@scanDeclaredMedia
            val detected = synchronized(mediaCandidateLock) {
                mediaCandidates[current]?.toList().orEmpty().reversed()
            }
            val sources = detected.filter { detectedMediaKind(it) != "Áudio" }
                .ifEmpty { detected }
                .distinct()
            when (sources.size) {
                0 -> Toast.makeText(this,
                    "Nenhuma mídia detectada nesta página. Abra o vídeo na página e tente novamente.",
                    Toast.LENGTH_LONG).show()
                1 -> startMedia3(sources.single())
                else -> {
                    // Múltiplas fontes podem corresponder a qualidades ou vídeos diferentes.
                    val labels = sources.take(20).map { url ->
                        "${detectedMediaKind(url)} · ${Uri.parse(url).host ?: "desconhecido"}"
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Escolha a mídia para reproduzir")
                        .setItems(labels) { _, index -> startMedia3(sources[index]) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }
        }
    }

    private fun showLibrary() {
        val audioLabel = "Áudio em segundo plano: " + (if (backgroundAudioEnabled) "ligado" else "desligado")
        AlertDialog.Builder(this).setTitle("Biblioteca")
            .setItems(arrayOf("Favoritos", "Histórico", audioLabel, "Vídeo em janela flutuante (PiP)", "Player nativo: abrir URL de mídia", "Player nativo: tentar mídia da página", "Vídeos detectados", "Media3: retomar na página", "Player nativo: abrir vídeo", "▶ Reproduzir mídia desta página")) { _, which ->
                when (which) {
                    0 -> showSavedPages(true)
                    1 -> showSavedPages(false)
                    2 -> toggleBackgroundAudio()
                    3 -> enterPip()
                    4 -> promptNativeMediaUrl()
                    5 -> tryNativeMediaFromPage()
                    6 -> showRequestMediaCandidates()
                    7 -> returnMedia3ToPage()
                    8 -> showNativeVideoPlayer()
                    9 -> playPageMediaDirectly()
                }
            }.show()
    }

    /**
     * Lê fontes já declaradas no DOM, sem disparar play, fazer downloads,
     * inspecionar scripts ou ultrapassar a política de mesma origem dos iframes.
     */
    private fun scanDeclaredMedia(view: WebView, after: (() -> Unit)? = null) {
        val originalPage = view.url
        val js = """(function() {
            const found = new Set();
            const accepted = /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac|mp4|m4v|webm|m3u8|mpd)(?:[?#]|$)/i;
            function record(raw, base) {
                if (!raw || found.size >= 60) return;
                try {
                    const u = new URL(raw, base);
                    if ((u.protocol === 'https:' || u.protocol === 'http:') && accepted.test(u.href)) {
                        found.add(u.href);
                    }
                } catch (_) {}
            }
            function visit(doc, depth) {
                if (!doc || depth > 2) return;
                const base = doc.baseURI;
                doc.querySelectorAll('video,audio,source').forEach(el => {
                    record(el.currentSrc, base);
                    record(el.getAttribute('src'), base);
                    record(el.getAttribute('data-src'), base);
                });
                doc.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:audio"],meta[name="twitter:player:stream"]').forEach(el => {
                    record(el.getAttribute('content'), base);
                });
                doc.querySelectorAll('iframe').forEach(el => {
                    try { if (el.contentDocument) visit(el.contentDocument, depth + 1); } catch (_) {}
                });
            }
            visit(document, 0);
            return JSON.stringify(Array.from(found));
        })()"""
        view.evaluateJavascript(js) { result ->
            if (view.url == originalPage) {
                runCatching {
                    val decoded = org.json.JSONArray("[$result]").getString(0)
                    val urls = org.json.JSONArray(decoded)
                    synchronized(mediaCandidateLock) {
                        val candidates = mediaCandidates.getOrPut(view) { LinkedHashSet() }
                        for (i in 0 until urls.length()) {
                            val u = urls.optString(i)
                            val uri = Uri.parse(u)
                            if ((uri.scheme == "http" || uri.scheme == "https") &&
                                !uri.host.isNullOrBlank() && mediaExtension.containsMatchIn(u)) {
                                if (candidates.size >= 60) candidates.remove(candidates.first())
                                candidates.add(u)
                            }
                        }
                    }
                }.onFailure { android.util.Log.w("NavegadorMedia3", "Falha ao examinar fontes no DOM: ${it.javaClass.simpleName}") }
            }
            after?.invoke()
        }
    }

    /** Fontes observadas pela WebView; nenhuma foi previamente validada. */
    private fun showRequestMediaCandidates() {
        scanDeclaredMedia(webView) { showDetectedMediaDialog() }
    }

    private fun showDetectedMediaDialog() {
        val found = synchronized(mediaCandidateLock) {
            mediaCandidates[webView]?.toList().orEmpty().reversed()
        }
        if (found.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Vídeos detectados")
                .setMessage("Nenhuma fonte com extensão de mídia foi observada nesta aba. Alguns sites só carregam a fonte após interagir com o player. URLs blob:, DRM e fontes sem extensão podem não aparecer.")
                .setPositiveButton("OK", null).show()
            return
        }
        // Prefere vídeo direto, depois playlists HLS/DASH e áudio.
        // Dentro de cada grupo, preserva a ordem de detecção mais recente primeiro.
        val entries = found.withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { detectedMediaRank(it.value) }
                .thenBy { it.index })
            .map { it.value }
            .take(40)
        val labels = entries.map { url ->
            val u = Uri.parse(url)
            val file = u.lastPathSegment?.takeLast(34).orEmpty()
            "${detectedMediaKind(url)} · ${u.host ?: "origem desconhecida"}${if (file.isNotBlank()) " · $file" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Vídeos detectados · ${entries.size} fonte(s)")
            .setItems(labels) { _, index ->
                val candidate = entries[index]
                val parsed = Uri.parse(candidate)
                AlertDialog.Builder(this).setTitle("Reproduzir no player nativo?")
                    .setMessage("Tipo: ${detectedMediaKind(candidate)}\nServidor: ${parsed.host ?: "desconhecido"}\n\nFonte ainda não validada: pode retornar HTTP 403.\n\nO Media3 tentará tocar independentemente. O player da página só será pausado se a reprodução nativa começar e a fonte corresponder.")
                    .setPositiveButton("Reproduzir no player nativo") { _, _ -> startMedia3(candidate) }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setPositiveButton("Comparar com player") { _, _ ->
                compareWithPagePlayer(webView, entries)
            }
            .setNeutralButton("Limpar lista") { _, _ ->
                synchronized(mediaCandidateLock) { mediaCandidates.remove(webView) }
            }.setNegativeButton("Cancelar", null).show()
    }

    /** Compara apenas URLs visíveis no DOM; não registra URLs, cookies ou tokens. */
    private fun compareWithPagePlayer(view: WebView, candidates: List<String>) {
        val pageUrl = view.url
        val urlsJson = org.json.JSONArray(candidates).toString()
        val js = """(function() {
            const candidates = $urlsJson;
            let elements = 0, playing = 0, inaccessible = 0, blob = 0, http = 0;
            const matches = new Set();
            const iframeHosts = new Set();
            function visit(doc, depth) {
                if (!doc || depth > 2) return;
                doc.querySelectorAll('video,audio').forEach(m => {
                    elements++;
                    if (!m.paused && !m.ended) playing++;
                    const raw = m.currentSrc || m.src || '';
                    if (raw.startsWith('blob:')) blob++;
                    if (/^https?:/i.test(raw)) http++;
                    const index = candidates.indexOf(raw);
                    if (index >= 0) matches.add(index + 1);
                });
                doc.querySelectorAll('iframe').forEach(frame => {
                    try {
                        const u = new URL(frame.getAttribute('src') || '', doc.baseURI);
                        if (u.protocol === 'https:' || u.protocol === 'http:') iframeHosts.add(u.hostname);
                    } catch (_) {}
                    try {
                        if (frame.contentDocument) visit(frame.contentDocument, depth + 1);
                        else inaccessible++;
                    } catch (_) { inaccessible++; }
                });
            }
            visit(document, 0);
            return JSON.stringify({
                elements: elements, playing: playing,
                inaccessible: inaccessible, blob: blob, http: http,
                matches: Array.from(matches).sort((a,b) => a-b),
                iframeHosts: Array.from(iframeHosts).sort().slice(0,20)
            });
        })()"""
        view.evaluateJavascript(js) { raw ->
            if (isFinishing || isDestroyed || view.url != pageUrl) return@evaluateJavascript
            val result = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
            }.getOrNull()
            if (result == null) {
                AlertDialog.Builder(this).setTitle("Comparação do vídeo")
                    .setMessage("Não foi possível consultar o player da página.")
                    .setPositiveButton("OK", null).show()
                return@evaluateJavascript
            }
            val matched = result.optJSONArray("matches")
            val indices = (0 until (matched?.length() ?: 0)).map { matched!!.optInt(it) }
            val iframesJson = result.optJSONArray("iframeHosts")
            val iframeHosts = (0 until (iframesJson?.length() ?: 0))
                .mapNotNull { iframesJson?.optString(it) }.filter { it.isNotBlank() }
            val observedOrigins = synchronized(mediaCandidateLock) {
                mediaRequestOrigins[view]?.toMap().orEmpty()
            }
            val requestContext = candidates.mapIndexed { index, url ->
                val host = Uri.parse(url).host ?: "desconhecido"
                val refHost = observedOrigins[url] ?: "não observado em requisição"
                "Fonte ${index + 1}: mídia=$host, Referer observado=$refHost"
            }.joinToString("\n")
            val mediaHostCounts = candidates.mapNotNull { Uri.parse(it).host }
                .groupingBy { it.lowercase(java.util.Locale.ROOT) }.eachCount()
            val mediaHosts = mediaHostCounts.entries.sortedBy { it.key }
                .joinToString(", ") { "${it.key} (${it.value})" }.ifEmpty { "nenhum" }
            val frameHosts = iframeHosts.joinToString(", ").ifEmpty { "nenhum identificado" }
            val shared = iframeHosts.intersect(mediaHostCounts.keys)
            val matchingText = if (indices.isEmpty()) "Nenhuma das fontes detectadas coincide com o currentSrc acessível."
                else "Fonte(s) correspondente(s): ${indices.joinToString(", ")}."
            val message = "Elementos de áudio/vídeo acessíveis: ${result.optInt("elements")}\n" +
                "Em reprodução: ${result.optInt("playing")}\n" +
                "Fontes blob: ${result.optInt("blob")}\n" +
                "Fontes HTTP(S) em elementos: ${result.optInt("http")}\n" +
                "Iframes inacessíveis: ${result.optInt("inaccessible")}\n\n" +
                matchingText + "\n\nDomínios dos iframes: $frameHosts" +
                "\nDomínios das fontes: $mediaHosts" +
                "\nDomínios coincidentes: ${shared.joinToString(", ").ifEmpty { "nenhum" }}" +
                "\n\nContexto observado nas requisições:\n$requestContext" +
                "\n\nA coincidência de domínio não prova qual player solicitou a mídia."
            android.util.Log.i("NavegadorMedia3",
                "ComparacaoDOM: elementos=${result.optInt("elements")}; " +
                "tocando=${result.optInt("playing")}; blob=${result.optInt("blob")}; " +
                "http=${result.optInt("http")}; iframesInacessiveis=${result.optInt("inaccessible")}; " +
                "fontesCorrespondentes=${indices.joinToString(",")}; " +
                "dominiosIframe=$frameHosts; dominiosFontes=$mediaHosts; " +
                "dominiosCoincidentes=${shared.joinToString(",")}; " +
                "contextoRequisicoes=${requestContext.replace("\n", " | ")}")
            AlertDialog.Builder(this).setTitle("Comparação do vídeo")
                .setMessage(message).setPositiveButton("OK", null).show()
        }
    }

    private lateinit var miniPlayerBar: LinearLayout
    private var miniController: MediaController? = null
    private var miniControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val miniRefresh = object : Runnable {
        override fun run() {
            val c = miniController
            if (c != null && ::miniPlayerBar.isInitialized) {
                val active = c.currentMediaItem != null &&
                    c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    c.playbackState != androidx.media3.common.Player.STATE_ENDED
                miniPlayerBar.visibility = if (active && playerPanel == null &&
                    !isInPictureInPictureMode) View.VISIBLE else View.GONE
                miniPlayButton.text = if (c.isPlaying) "Pausar" else "Play"
            }
            handler.postDelayed(this, 1000L)
        }
    }
    private lateinit var miniPlayButton: Button
    private fun connectMiniPlayer() {
        if (miniControllerFuture != null) return
        val future = MediaController.Builder(this,
            SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        ).buildAsync()
        miniControllerFuture = future
        future.addListener({
            if (isFinishing || isDestroyed) { MediaController.releaseFuture(future); return@addListener }
            runCatching { miniController = future.get() }
                .onFailure { android.util.Log.w("NavegadorMedia3", "Mini-player indisponível", it) }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }
    private lateinit var playerLayer: FrameLayout
    private var playerPanel: FrameLayout? = null
    private var nativePlayerClose: (() -> Unit)? = null
    private var playerControls: View? = null
    private var playerIsImmersive = false
    private fun closeNativePlayer() { nativePlayerClose?.invoke() }

    /** Interface de vídeo vinculada à sessão que já toca áudio em segundo plano. */
    private fun showNativeVideoPlayer() {
        if (playerPanel != null) { playerControls?.visibility = View.VISIBLE; return }
        val panel = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x55000000)
        }
        val surface = android.view.SurfaceView(this)
        panel.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, android.view.Gravity.CENTER))
        val info = TextView(this).apply {
            text = "Conectando ao player..."
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        outer.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val progress = android.widget.SeekBar(this)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val back = Button(this).apply { text = "−10 s" }
        val play = Button(this).apply { text = "Play/Pause" }
        val forward = Button(this).apply { text = "+10 s" }
        val rotate = Button(this).apply { text = "Girar" }
        val fullscreenButton = Button(this).apply { text = "Tela cheia" }
        val pipButton = Button(this).apply { text = "PiP" }
        val close = Button(this).apply { text = "Fechar" }
        val navControls = LinearLayout(this)
        listOf(close, rotate, fullscreenButton, pipButton).forEach { navControls.addView(it,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        outer.addView(navControls)
        outer.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        listOf(back, play, forward).forEach { controls.addView(it,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        outer.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(outer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playerLayer.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playerPanel = panel
        miniPlayerBar.visibility = View.GONE
        playerControls = outer
        browserBar.visibility = View.GONE
        statusView.visibility = View.GONE
        val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        var player: MediaController? = null
        var seeking = false
        var closed = false
        var videoFullscreen = false
        val originalOrientation = requestedOrientation
        var videoWidth = 0
        var videoHeight = 0
        var filling = false
        fun updateVideoAspect() {
            val width = panel.width
            val height = panel.height
            if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
            val ratio = videoWidth.toFloat() / videoHeight
            val boxRatio = width.toFloat() / height
            val targetWidth = if ((ratio > boxRatio) != filling) width else (height * ratio).toInt()
            val targetHeight = if ((ratio > boxRatio) != filling) (width / ratio).toInt() else height
            val w = targetWidth.coerceAtLeast(1)
            val h = targetHeight.coerceAtLeast(1)
            val current = surface.layoutParams
            if (current.width != w || current.height != h)
                surface.layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoAspect() }
        val aspectButton = Button(this).apply { text = "Ajustar" }
        navControls.addView(aspectButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        aspectButton.setOnClickListener {
            filling = !filling
            aspectButton.text = if (filling) "Preencher" else "Ajustar"
            updateVideoAspect()
        }
        val hideControls = Runnable { if (!closed && player?.isPlaying == true &&
            !seeking && !isInPictureInPictureMode) outer.visibility = View.GONE }
        fun showControls() {
            if (!isInPictureInPictureMode) outer.visibility = View.VISIBLE
            handler.removeCallbacks(hideControls)
            if (!videoFullscreen && player?.isPlaying == true)
                handler.postDelayed(hideControls, 3500L)
        }
        fun setVideoFullscreen(enabled: Boolean) {
            videoFullscreen = enabled
            val visibility = if (enabled) View.GONE else View.VISIBLE
            outer.visibility = visibility
            if (!enabled) showControls()
            playerIsImmersive = enabled
            window.decorView.systemUiVisibility = if (enabled)
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                else 0
            fullscreenButton.text = if (enabled) "Sair tela cheia" else "Tela cheia"
        }
        surface.setOnClickListener { showControls() }
        outer.setOnClickListener { if (outer.visibility == View.VISIBLE) {
            outer.visibility = View.GONE
            handler.removeCallbacks(hideControls)
        } else showControls() }

        val update = object : Runnable {
            override fun run() {
                val c = player
                if (!closed && c != null) {
                    val duration = c.duration
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    val total = if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET)
                        duration else 0L
                    if (!seeking) {
                        progress.max = if (total > 0L) 1000 else 0
                        progress.progress = if (total > 0L)
                            ((pos.coerceAtMost(total) * 1000L) / total).toInt() else 0
                    }
                    fun clock(ms: Long): String {
                        val sec = ms.coerceAtLeast(0L) / 1000L
                        return "%02d:%02d".format(sec / 60, sec % 60)
                    }
                    info.text = if (total > 0L) "${clock(pos)} / ${clock(total)}"
                        else "Áudio/vídeo nativo · ${clock(pos)}"
                    play.text = if (c.isPlaying) "Pausar" else "Reproduzir"
                    if (c.isPlaying && outer.visibility == View.VISIBLE &&
                        !handler.hasCallbacks(hideControls) && !videoFullscreen)
                        handler.postDelayed(hideControls, 3500L)
                    handler.postDelayed(this, 500L)
                }
            }
        }
        progress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                seeking = true
                handler.removeCallbacks(hideControls)
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val c = player
                if (c != null && c.duration > 0L &&
                    c.duration != androidx.media3.common.C.TIME_UNSET) {
                    c.seekTo(c.duration * progress.progress / 1000L)
                }
                seeking = false
                showControls()
            }
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {}
        })
        play.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        back.setOnClickListener { player?.let { it.seekTo((it.currentPosition - 10000L).coerceAtLeast(0L)) } }
        forward.setOnClickListener {
            player?.let { c ->
                val target = c.currentPosition + 10000L
                c.seekTo(if (c.duration > 0L && c.duration != androidx.media3.common.C.TIME_UNSET)
                    target.coerceAtMost(c.duration) else target)
            }
        }
        rotate.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        fullscreenButton.setOnClickListener { setVideoFullscreen(!videoFullscreen) }
        pipButton.setOnClickListener {
            if (player == null) Toast.makeText(this, "Player ainda conectando", Toast.LENGTH_SHORT).show()
            else enterPip()
        }
        close.setOnClickListener { closeNativePlayer() }
        nativePlayerClose = {
            if (!closed) {
                closed = true
                handler.removeCallbacks(update)
                handler.removeCallbacks(hideControls)
                player?.let { c -> runCatching { c.clearVideoSurfaceView(surface) } }
                MediaController.releaseFuture(future)
                playerLayer.removeView(panel)
                playerPanel = null
                playerControls = null
                nativePlayerClose = null
                playerIsImmersive = false
                window.decorView.systemUiVisibility = 0
                requestedOrientation = originalOrientation
                browserBar.visibility = View.VISIBLE
                miniPlayerBar.visibility = if (miniController?.currentMediaItem != null) View.VISIBLE else View.GONE
                updateStatus()
            }
        }
        future.addListener({
            if (closed || isFinishing || isDestroyed) {
                MediaController.releaseFuture(future)
            } else {
                try {
                    val c = future.get()
                    player = c
                    c.setVideoSurfaceView(surface)
                    val sizeListener = object : androidx.media3.common.Player.Listener {
                        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                            if (size.width > 0 && size.height > 0) {
                                videoWidth = (size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                                videoHeight = size.height
                                updateVideoAspect()
                            }
                        }
                    }
                    c.addListener(sizeListener)
                    val initialSize = c.videoSize
                    if (initialSize.width > 0 && initialSize.height > 0) {
                        videoWidth = (initialSize.width * initialSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                        videoHeight = initialSize.height
                        updateVideoAspect()
                    }
                    handler.post(update)
                } catch (e: Exception) {
                    info.text = "Não foi possível conectar ao player."
                    android.util.Log.e("NavegadorMedia3", "Falha ao abrir tela nativa", e)
                }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    /** Uma transferência explícita de reprodução. Não é uma sincronização contínua
     * com vídeos de outros sites, nem tenta acessar iframes de outra origem.
     */
    private var media3OriginView: WebView? = null
    private var media3OriginUrl: String? = null

    private fun startMedia3(url: String) {
        val u = Uri.parse(url)
        if (u.scheme !in listOf("http", "https") || u.host.isNullOrBlank()) return
        val sourceView = webView
        val quotedUrl = org.json.JSONObject.quote(url)
        val probe = """(function(){
            const target = $quotedUrl;
            function collect(doc) {
                let out = [...doc.querySelectorAll('audio,video')];
                for(const frame of doc.querySelectorAll('iframe')) {
                    try { if(frame.contentDocument) out = out.concat(collect(frame.contentDocument)); } catch(e) {}
                }
                return out;
            }
            const matches = collect(document).filter(m => m.currentSrc === target || m.src === target);
            const m = matches.find(x => !x.paused && !x.ended) || matches[0];
            return JSON.stringify({matched:!!m, seconds:m && Number.isFinite(m.currentTime) ? m.currentTime : 0});
        })()"""
        sourceView.evaluateJavascript(probe) { result ->
            val state = runCatching {
                org.json.JSONObject(JSONArray("[$result]").getString(0))
            }.getOrNull()
            val matched = state?.optBoolean("matched", false) == true
            val position = if (matched) (state?.optDouble("seconds", 0.0) ?: 0.0)
                .coerceAtLeast(0.0).times(1000).toLong() else 0L
            val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            future.addListener({
                try {
                    val controller = future.get()
                    media3OriginView = if (matched) sourceView else null
                    media3OriginUrl = if (matched) url else null
                    var transferred = false
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (!isPlaying || transferred) return
                            transferred = true
                            if (matched) {
                                // Pausa o player original somente após o Media3 tocar.
                                sourceView.evaluateJavascript("""(function(){
                                    const target=$quotedUrl;
                                    const visited=new Set();
                                    function pause(doc){
                                        if(!doc || visited.has(doc)) return;
                                        visited.add(doc);
                                        for(const m of doc.querySelectorAll('audio,video')) {
                                            if(m.currentSrc===target || m.src===target) m.pause();
                                        }
                                        for(const f of doc.querySelectorAll('iframe')) {
                                            try { pause(f.contentDocument); } catch(e) {}
                                        }
                                    }
                                    pause(document);
                                })()""", null)
                            }
                            Toast.makeText(this@MainActivity,
                                if (matched) "Media3 reproduzindo na posição do vídeo" else
                                    "Media3 reproduzindo (fonte sem vínculo com player da página)",
                                Toast.LENGTH_LONG).show()
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Toast.makeText(this@MainActivity,
                                "Erro Media3: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                        }
                    }
                    controller.addListener(listener)
                    // Prioriza a origem observada no Referer da própria requisição.
                    // Nunca envia cookies, URL integral, query ou tokens ao serviço.
                    // Aceita somente domínio HTTPS relacionado ao host da mídia.
                    val observedHost = synchronized(mediaCandidateLock) {
                        mediaRequestOrigins[sourceView]?.get(url)
                    }
                    val mediaHost = u.host?.lowercase(java.util.Locale.ROOT).orEmpty()
                    val refHost = observedHost?.lowercase(java.util.Locale.ROOT).orEmpty()
                    val related = refHost.isNotBlank() && refHost != "não informado" &&
                        (mediaHost == refHost || mediaHost.endsWith(".$refHost"))
                    val pageUri = Uri.parse(sourceView.url ?: "")
                    val fallbackOrigin = if (pageUri.scheme == "https" && !pageUri.host.isNullOrBlank())
                        "https://${pageUri.host}${if (pageUri.port > 0) ":${pageUri.port}" else ""}/"
                        else ""
                    val selectedOrigin = if (u.scheme == "https" && related)
                        "https://$refHost/" else fallbackOrigin
                    android.util.Log.i("NavegadorMedia3",
                        "OrigemRefererSelecionada: ${if (related) "observada" else "pagina"}; " +
                        "hostReferer=${if (related) refHost else "indisponivel"}; hostMidia=$mediaHost")
                    val mediaInfo = android.os.Bundle().apply {
                        putString("nm_page_origin", selectedOrigin)
                    }
                    val item = MediaItem.Builder().setUri(u)
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            // Metadados usados pelo cartão de mídia do Android (API 34+).
                            // Os botões Play/Pause são comandos da MediaSession, não atalhos da Activity.
                            .setTitle(sourceView.title?.take(90)?.takeIf { it.isNotBlank() }
                                ?: "Vídeo do NavegadorMaluco")
                            .setArtist("NavegadorMaluco")
                            .setIsPlayable(true)
                            .setExtras(mediaInfo).build())
                        .build()
                    controller.setMediaItem(item)
                    controller.prepare()
                    if (position > 0L) controller.seekTo(position)
                    controller.play()
                    // A seleção da fonte abre o vídeo; não exige voltar à Biblioteca.
                    showNativeVideoPlayer()
                    handler.postDelayed({
                        controller.removeListener(listener)
                        MediaController.releaseFuture(future)
                    }, 12000L)
                    if (!matched) Toast.makeText(this,
                        "Fonte não corresponde ao elemento de vídeo acessível; iniciando do começo",
                        Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Media3 não iniciou: ${e.message}", Toast.LENGTH_LONG).show()
                    MediaController.releaseFuture(future)
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(this))
        }
    }

    private fun returnMedia3ToPage() {
        val sourceView = media3OriginView
        val sourceUrl = media3OriginUrl
        if (sourceView == null || sourceUrl == null || tabs.none { it.view === sourceView }) {
            Toast.makeText(this, "Nenhum vídeo da página vinculado ao Media3", Toast.LENGTH_LONG).show()
            return
        }
        val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                val time = (controller.currentPosition.coerceAtLeast(0L) / 1000.0)
                val target = org.json.JSONObject.quote(sourceUrl)
                val script = """(function(){
                    const target=$target, seconds=$time;
                    const visited=new Set(); let changed=false;
                    function resume(doc){
                        if(!doc || visited.has(doc)) return; visited.add(doc);
                        for(const m of doc.querySelectorAll('audio,video')) {
                            if(m.currentSrc===target || m.src===target) {
                                try { if(Number.isFinite(m.duration)) m.currentTime=Math.min(seconds,Math.max(0,m.duration-0.2)); else m.currentTime=seconds; } catch(e) {}
                                m.play().catch(()=>{}); changed=true;
                            }
                        }
                        for(const f of doc.querySelectorAll('iframe')) {
                            try {resume(f.contentDocument);}catch(e){}
                        }
                    }
                    resume(document); return changed;
                })()"""
                val tabIndex = tabs.indexOfFirst { it.view === sourceView }
                if (tabIndex >= 0) { activeIndex = tabIndex; showActive() }
                sourceView.evaluateJavascript(script) { ok ->
                    if (ok == "true") {
                        controller.pause()
                        Toast.makeText(this, "Posição enviada ao player da página", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "A página não permitiu retomar este vídeo", Toast.LENGTH_LONG).show()
                    MediaController.releaseFuture(future)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Falha na retomada: ${e.message}", Toast.LENGTH_LONG).show()
                MediaController.releaseFuture(future)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    /** O player nativo reproduz URLs diretas; não extrai fluxos protegidos ou blob:. */
    private fun startNativeMedia(raw: String) {
        val url = raw.trim()
        val uri = Uri.parse(url)
        if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Informe uma URL HTTP(S) direta de áudio/vídeo", Toast.LENGTH_LONG).show()
            return
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(this,
                Intent(this, NativeMediaService::class.java).apply {
                    action = NativeMediaService.ACTION_PLAY
                    putExtra(NativeMediaService.EXTRA_URL, url)
                })
            Toast.makeText(this, "Abrindo mídia no player nativo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao abrir player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun promptNativeMediaUrl() {
        val field = EditText(this).apply {
            hint = "https://exemplo.com/audio.mp3"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Reproduzir mídia diretamente")
            .setMessage("Cole uma URL direta de mídia. Links de páginas e players protegidos não funcionam.")
            .setView(field)
            .setPositiveButton("Reproduzir") { _, _ -> startNativeMedia(field.text.toString()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun tryNativeMediaFromPage() {
        // A WebView so permite examinar o documento e iframes da mesma origem.
        // URLs blob:, MSE, DRM e players isolados nao podem ser transferidos.
        val script = """(function() {
            const medias = [];
            let crossOriginFrames = 0;
            const seen = new Set();
            function scan(doc) {
                if (!doc || seen.has(doc)) return;
                seen.add(doc);
                try {
                    for (const m of doc.querySelectorAll('audio,video')) {
                        let u = m.currentSrc || m.src || '';
                        if (!u) {
                            const source = m.querySelector('source[src]');
                            if (source) u = source.src;
                        }
                        medias.push({url: u, playing: !m.paused && !m.ended,
                            kind: m.tagName.toLowerCase()});
                    }
                    for (const iframe of doc.querySelectorAll('iframe')) {
                        try { scan(iframe.contentDocument); }
                        catch (_) { crossOriginFrames++; }
                        if (!iframe.contentDocument) crossOriginFrames++;
                    }
                } catch (_) { crossOriginFrames++; }
            }
            scan(document);
            medias.sort((a,b) => Number(b.playing) - Number(a.playing));
            const direct = medias.find(m => /^https?:\/\//i.test(m.url));
            const blobs = medias.filter(m => /^blob:/i.test(m.url)).length;
            return JSON.stringify({url: direct ? direct.url : '',
                found: medias.length, blobs: blobs, frames: crossOriginFrames,
                playing: direct ? direct.playing : false});
        })()"""
        webView.evaluateJavascript(script) { result ->
            val data = runCatching {
                val encoded = JSONArray("[$result]").getString(0)
                org.json.JSONObject(encoded)
            }.getOrNull()
            val url = data?.optString("url").orEmpty()
            if (url.isNotBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Mídia direta identificada")
                    .setMessage("A URL foi identificada. O player nativo tentará abrir o arquivo, mas pode falhar se precisar de sessão, cookies ou formato especial. O vídeo da página não é transferido automaticamente.")
                    .setPositiveButton("Tentar reproduzir") { _, _ -> startNativeMedia(url) }
                    .setNegativeButton("Cancelar", null).show()
            } else {
                val count = data?.optInt("found", 0) ?: 0
                val blobs = data?.optInt("blobs", 0) ?: 0
                val frames = data?.optInt("frames", 0) ?: 0
                val diagnosis = when {
                    data == null -> "Falha ao consultar os elementos HTML5 da página."
                    blobs > 0 -> "O player usa URL blob: (fluxo de mídia interno), que o player nativo não consegue abrir."
                    frames > 0 -> "O vídeo pode estar em iframe de outra origem; o Android não permite ler o conteúdo desse iframe a partir da página."
                    count > 0 -> "Foram encontrados elementos de mídia, mas sem endereço HTTP(S) direto."
                    else -> "Nenhum elemento de áudio/vídeo acessível foi encontrado na página."
                }
                AlertDialog.Builder(this).setTitle("Diagnóstico do player")
                    .setMessage(diagnosis + "\n\nPara testar a reprodução em segundo plano, use uma URL direta de áudio MP3 no menu Player nativo. O áudio da aba comum continua sujeito à suspensão da WebView.")
                    .setPositiveButton("Entendi", null).show()
            }
        }
    }

    /** PiP mantém a própria Activity visível numa janela flutuante.
     * Não cria um player independente nem mantém o áudio após encerrar o processo.
     */
    private fun pipAvailable(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(autoEnter)
            // Surfaces de vídeo da WebView podem apresentar tela preta durante
            // redimensionamento contínuo para a janela PiP.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    private fun updatePipAutoEnter(enabled: Boolean) {
        if (!pipAvailable()) return
        runCatching { setPictureInPictureParams(pipParams(enabled)) }
    }

    private fun enterPip() {
        if (!pipAvailable()) {
            Toast.makeText(this, "Este Android não oferece Picture-in-Picture", Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (!isInPictureInPictureMode) {
                val entered = enterPictureInPictureMode(pipParams(false))
                if (!entered) Toast.makeText(this, "PiP não autorizado nas configurações do Android", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "PiP indisponível: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ usa transição automática somente durante vídeo em tela cheia.
        // Em Android 14+ (minSdk 34), o mecanismo automático é sempre disponível.
        // O botão manual PiP é oferecido pelo menu Biblioteca.
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // No PiP, mostra somente a superfície nativa; fora dele, devolve os controles.
        playerControls?.visibility = if (isInPictureInPictureMode || playerIsImmersive) View.GONE else View.VISIBLE
        // Preserva as mesmas WebViews e só esconde os controles do navegador.
        browserBar.visibility = if (isInPictureInPictureMode || playerPanel != null) View.GONE else View.VISIBLE
        statusView.visibility = if (isInPictureInPictureMode) View.GONE else
            if (tabs.isNotEmpty() && Attention.status(webView.url) != null) View.VISIBLE else View.GONE
        if (!isInPictureInPictureMode) updateStatus()
        webHost.requestLayout()
        // O WebChromeClient pode ter anexado o player fullscreen ao decorView,
        // fora de webHost. Recalcular somente webHost não o atualiza.
        fullscreenView?.let { player ->
            player.visibility = View.VISIBLE
            player.requestLayout()
            player.invalidate()
        }
        if (tabs.isNotEmpty()) {
            webView.requestLayout()
            webView.invalidate()
        }
    }

    /** O serviço inicia por ação explícita do usuário, enquanto a Activity está visível. */
    private fun toggleBackgroundAudio() {
        val enable = !backgroundAudioEnabled
        if (enable) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, BackgroundAudioService::class.java)
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Falha ao ativar áudio: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            stopService(Intent(this, BackgroundAudioService::class.java))
        }
        backgroundAudioEnabled = enable
        getSharedPreferences("media_options", MODE_PRIVATE).edit()
            .putBoolean("background_audio", backgroundAudioEnabled).apply()
        tabs.forEachIndexed { index, tab ->
            if (backgroundAudioEnabled || index == activeIndex) tab.view.onResume()
            else tab.view.onPause()
        }
        Toast.makeText(this,
            if (enable) "Suporte a áudio em segundo plano ativado"
            else "Suporte a áudio em segundo plano desativado",
            Toast.LENGTH_LONG).show()
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
        if (isInPictureInPictureMode) { statusView.visibility = View.GONE; return }
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
    /** Mantém as mesmas instâncias de WebView e abas durante a rotação.
     * O Android recalcula o layout, sem chamar onDestroy/onCreate.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A hierarquia usa MATCH_PARENT e pesos, então adapta-se automaticamente.
        // Solicita novo cálculo da área útil (barras do sistema/teclado) e desenho.
        ViewCompat.requestApplyInsets(window.decorView)
        webHost.requestLayout()
        if (tabs.isNotEmpty()) {
            webView.requestLayout()
            webView.invalidate()
        }
        fullscreenView?.let { it.requestLayout(); it.invalidate() }
    }

    override fun onResume() {
        super.onResume()
        backgroundAudioEnabled = getSharedPreferences("media_options", MODE_PRIVATE)
            .getBoolean("background_audio", false)
        // Retoma a renderização sem recriar páginas ou reiniciar players.
        tabs.forEachIndexed { index, tab ->
            if (backgroundAudioEnabled || index == activeIndex) tab.view.onResume()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        handler.removeCallbacks(miniRefresh)
        handler.post(miniRefresh)
    }
    override fun onPause() {
        handler.removeCallbacks(miniRefresh)
        handler.removeCallbacks(ticker)
        Attention.pause()
        persistTabs()
        // O controle de atenção para de contar fora do app. A opção de áudio
        // mantém o WebView vivo como melhor esforço, sem serviço de mídia.
        if (!backgroundAudioEnabled && !isInPictureInPictureMode && fullscreenView == null)
            tabs.forEach { it.view.onPause() }
        super.onPause()
    }
    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        closeNativePlayer()
        exitFullscreen()
        handler.removeCallbacks(miniRefresh)
        miniControllerFuture?.let { MediaController.releaseFuture(it) }
        miniControllerFuture = null
        miniController = null
        handler.removeCallbacks(ticker)
        tabs.forEach { (it.view.parent as? android.view.ViewGroup)?.removeView(it.view); it.view.destroy() }
        store.close()
        super.onDestroy()
    }
}
