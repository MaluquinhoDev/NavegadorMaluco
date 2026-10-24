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

    // O Android devolve aqui o arquivo escolhido em formulários <input type="file">.
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == RESULT_OK)
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        else null
        fileCallback?.onReceiveValue(selected)
        fileCallback = null
    }

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
        }
        setContentView(root)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) exitFullscreen()
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
        val audioLabel = "Áudio em segundo plano: " + (if (backgroundAudioEnabled) "ligado" else "desligado")
        AlertDialog.Builder(this).setTitle("Biblioteca")
            .setItems(arrayOf("Favoritos", "Histórico", audioLabel, "Vídeo em janela flutuante (PiP)", "Player nativo: abrir URL de mídia", "Player nativo: tentar mídia da página")) { _, which ->
                when (which) {
                    0 -> showSavedPages(true)
                    1 -> showSavedPages(false)
                    2 -> toggleBackgroundAudio()
                    3 -> enterPip()
                    4 -> promptNativeMediaUrl()
                    5 -> tryNativeMediaFromPage()
                }
            }.show()
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
        // Apenas um elemento HTML5 já carregado. O valor não passa por bridge de extensão.
        val script = """(function(){const m=Array.from(document.querySelectorAll('audio,video'))
          .find(x=>x.currentSrc && /^https?:/i.test(x.currentSrc));
          return m ? m.currentSrc : '';})()"""
        webView.evaluateJavascript(script) { result ->
            val url = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("")
            if (url.isBlank()) {
                Toast.makeText(this, "Esta página não expõe uma URL direta de mídia compatível", Toast.LENGTH_LONG).show()
            } else {
                AlertDialog.Builder(this).setTitle("Transferir mídia para player nativo?")
                    .setMessage("O player nativo tentará abrir a mídia. Pause o player da página para evitar áudio duplicado.")
                    .setPositiveButton("Reproduzir") { _, _ -> startNativeMedia(url) }
                    .setNegativeButton("Cancelar", null).show()
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
        // Preserva as mesmas WebViews e só esconde os controles do navegador.
        browserBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
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
    }
    override fun onPause() {
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
        exitFullscreen()
        handler.removeCallbacks(ticker)
        tabs.forEach { (it.view.parent as? android.view.ViewGroup)?.removeView(it.view); it.view.destroy() }
        store.close()
        super.onDestroy()
    }
}
