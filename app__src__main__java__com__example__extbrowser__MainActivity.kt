package com.example.extbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
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
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.SystemClock
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.media.AudioManager
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
import android.widget.ScrollView
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
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {
    companion object {
        private const val HOME = "https://www.wikipedia.org"
        private const val MAX_TABS = BrowserTabPolicy.MAX_TABS
        const val ACTION_OPEN_MEDIA_PLAYER = "com.example.extbrowser.OPEN_MEDIA_PLAYER"
        private var liveBrowser: java.lang.ref.WeakReference<MainActivity>? = null

        fun stopExistingWebMedia() {
            val activity = liveBrowser?.get() ?: return
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.runOnUiThread { activity.forceStopWebMedia() }
            }
        }
    }
    private class Blocker(val name: String, val regex: Regex)
    private val isolatedGoogleProfile = "google_secondary_v1"
    private data class Tab(
        val view: WebView, var title: String = "Nova aba", var url: String = HOME,
        var constellation: String = "Exploração", var lastUsed: Long = SystemClock.elapsedRealtime(),
        val id: String = UUID.randomUUID().toString(), val profile: String = ""
    )
    private data class ParkedTab(val url: String, val title: String, val constellation: String,
        val id: String = UUID.randomUUID().toString(), val profile: String = "")
    private val parkedTabs = mutableListOf<ParkedTab>()
    private val pinnedTabIds = mutableSetOf<String>()
    private var automaticParkingInProgress = false
    private val constellations = mutableListOf("Exploração", "Estudos", "Entretenimento", "Trabalho")

    private fun restoreConstellationNames() {
        val raw = getSharedPreferences("tabs", MODE_PRIVATE).getString("constellation_names_v1", null)
            ?: return
        val saved = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (index in 0 until minOf(saved.length(), 16)) {
            val name = saved.optString(index).trim().take(28)
            if (name.isNotEmpty() && name !in constellations) constellations.add(name)
        }
    }

    private fun createConstellation(afterCreate: () -> Unit) {
        val field = EditText(this).apply {
            styleInput(this)
            hint = "Nome da constelação"
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Nova constelação")
            .setView(field)
            .setPositiveButton("Criar") { _, _ ->
                val name = field.text.toString().trim().take(28)
                if (name.isBlank() || name in constellations || constellations.size >= 20) {
                    Toast.makeText(this, "Nome inválido, repetido ou limite de grupos atingido", Toast.LENGTH_LONG).show()
                } else {
                    constellations.add(name)
                    val saved = JSONArray().apply { constellations.forEach { put(it) } }
                    getSharedPreferences("tabs", MODE_PRIVATE).edit()
                        .putString("constellation_names_v1", saved.toString()).apply()
                    afterCreate()
                }
            }.setNegativeButton("Cancelar", null).show()
    }
    private val totalTabCount get() = tabs.size + parkedTabs.size

    private fun supportedGoogleProfile(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun storedProfile(value: String?): String =
        if (value == isolatedGoogleProfile && supportedGoogleProfile()) isolatedGoogleProfile else ""

    // Diagnóstico volátil: nenhum cookie, token, e-mail, URL ou corpo de resposta é coletado.
    private var googleLoginAuditEnabled = false
    private var googleLoginAuditStarted = 0L
    private val googleLoginAuditEvents = ArrayDeque<String>()
    private val googleLoginLastPage = java.util.WeakHashMap<WebView, String>()
    private var googleLoginRedirectCount = 0
    private var googleLoginPopupCount = 0
    private var googleLoginAppNavigationCount = 0

    private fun googleCookieCompatEnabled(): Boolean =
        getSharedPreferences("google_login_diagnostic", MODE_PRIVATE)
            .getBoolean("consistent_auth_cookies", false)

    /** Política por WebView, aplicada ANTES de navegar sempre que possível.
     * Não modifica cookies existentes nem habilita terceiros em todos os sites. */
    private fun applyLoginCookiePolicy(view: WebView, url: String?): Pair<Boolean, Boolean> {
        val host = websiteHost(url)
        val thirdParty = CookieManager.getInstance()
        val before = thirdParty.acceptThirdPartyCookies(view)
        val standardLogin = host == "x.com" || host?.endsWith(".x.com") == true ||
            host == "twitter.com" || host?.endsWith(".twitter.com") == true ||
            host == "accounts.google.com"
        val additionalGoogleLogin = googleCookieCompatEnabled() &&
            (host == "myaccount.google.com" || host == "google.com" ||
                host == "www.google.com")
        val seuImpostoCookies = isSeuImposto(url) &&
            getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                .getBoolean("third_party_cookies", false)
        val allow = standardLogin || additionalGoogleLogin || seuImpostoCookies
        thirdParty.setAcceptThirdPartyCookies(view, allow)
        return before to allow
    }

    private fun googleLoginHostCategory(rawUrl: String?): String? {
        val host = runCatching { Uri.parse(rawUrl).host?.lowercase() }.getOrNull() ?: return null
        return when {
            host == "accounts.google.com" -> "Contas Google"
            host == "myaccount.google.com" -> "Minha conta"
            host == "oauth2.googleapis.com" -> "OAuth Google"
            host == "google.com" || host.endsWith(".google.com") -> "Google"
            host == "gmail.com" || host.endsWith(".gmail.com") -> "Gmail"
            host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be" -> "YouTube"
            else -> null
        }
    }

    private fun recordGoogleLoginAudit(event: String, url: String? = null,
                                       relatedView: WebView? = null) {
        if (!googleLoginAuditEnabled) return
        val category = googleLoginHostCategory(url)
            ?: if (googleLoginHostCategory(relatedView?.url) != null) "Terceiro na página Google"
                else return
        val elapsed = (SystemClock.elapsedRealtime() - googleLoginAuditStarted).coerceAtLeast(0)
        if (googleLoginAuditEvents.size >= 90) googleLoginAuditEvents.removeFirst()
        googleLoginAuditEvents.addLast("+${elapsed}ms · $category · $event")
    }

    /** Apenas categorias de domínio; nunca guarda URLs ou parâmetros de autenticação. */
    private fun recordGoogleLoginTransition(view: WebView, url: String?, origin: String) {
        if (!googleLoginAuditEnabled) return
        val next = googleLoginHostCategory(url) ?: return
        val previous = googleLoginLastPage.put(view, next)
        if (previous != next)
            recordGoogleLoginAudit("Transição ${previous ?: "origem não monitorada"} → $next · $origin", url, view)
    }

    private fun recordGoogleLoginAppNavigation(view: WebView, url: String?, reason: String) {
        if (!googleLoginAuditEnabled || googleLoginHostCategory(url) == null) return
        googleLoginAppNavigationCount++
        recordGoogleLoginAudit("Carregamento iniciado pelo app · $reason", url, view)
    }

    private fun googleLoginAuditReport(): String = buildString {
        appendLine("Navegador Maluco · diagnóstico de login Google")
        appendLine("Monitor: ${if (googleLoginAuditEnabled) "ativo" else "inativo"}")
        appendLine("Motor: ${WebView.getCurrentWebViewPackage()?.packageName ?: "desconhecido"}")
        appendLine("Modo: ${siteDisplayMode(webView.url)}")
        appendLine("Identificação: ${if (webView.settings.userAgentString.contains("; wv)")) "WebView" else "personalizada"}")
        appendLine("JavaScript: ${webView.settings.javaScriptEnabled}")
        appendLine("DOM Storage: ${webView.settings.domStorageEnabled}")
        appendLine("Cookies gerais: ${CookieManager.getInstance().acceptCookie()}")
        appendLine("Cookies de terceiros (aba atual): ${CookieManager.getInstance().acceptThirdPartyCookies(webView)}")
        appendLine("Compatibilidade de cookies Google: ${googleCookieCompatEnabled()}")
        appendLine("Perfis isolados WebView disponíveis: ${supportedGoogleProfile()}")
        appendLine("Perfil da aba atual: ${if (tabs.getOrNull(activeIndex)?.profile == isolatedGoogleProfile) "Google isolado" else "padrão"}")
        appendLine("Abas: $totalTabCount · residentes: ${tabs.size}")
        appendLine("Redirecionamentos HTTP observados: $googleLoginRedirectCount")
        appendLine("Janelas auxiliares solicitadas: $googleLoginPopupCount")
        appendLine("Carregamentos iniciados pelo app: $googleLoginAppNavigationCount")
        appendLine("Interpretação: página concluída não comprova autenticação.")
        appendLine("Eventos (${googleLoginAuditEvents.size}):")
        if (googleLoginAuditEvents.isEmpty()) appendLine("- Nenhum evento relevante registrado")
        else googleLoginAuditEvents.forEach { appendLine("- $it") }
        appendLine("Não contém e-mails, senhas, cookies, tokens, URLs completas nem parâmetros da autenticação.")
    }

    private fun safeTabId(candidate: String?): String = runCatching {
        UUID.fromString(candidate).toString()
    }.getOrElse { UUID.randomUUID().toString() }

    private fun sessionStateFile(id: String): File = File(File(filesDir, "tab_navigation"), "$id.bin")

    /** Histórico de navegação privado e limitado; nunca promete restaurar DOM, senha ou reprodução. */
    private fun saveTabNavigation(tab: Tab) {
        runCatching {
            val state = Bundle()
            if (tab.view.saveState(state) == null) return@runCatching
            val parcel = Parcel.obtain()
            val bytes = try { state.writeToParcel(parcel, 0); parcel.marshall() }
                finally { parcel.recycle() }
            if (bytes.size !in 1..262_144) return@runCatching
            val file = sessionStateFile(tab.id)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }.onFailure { android.util.Log.w("TabNavigation", "Falha ao salvar histórico", it) }
    }

    private fun restoreTabNavigation(tab: Tab): Boolean = runCatching {
        val file = sessionStateFile(tab.id)
        if (!file.isFile || file.length() !in 1..262_144) return@runCatching false
        val parcel = Parcel.obtain()
        try {
            val bytes = file.readBytes()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            tab.view.restoreState(parcel.readBundle(javaClass.classLoader) ?: return@runCatching false) != null
        } finally { parcel.recycle() }
    }.getOrDefault(false)

    private fun deleteTabNavigation(id: String) { runCatching { sessionStateFile(id).delete() } }

    private val tabs = mutableListOf<Tab>()
    private var youtubeDiagnosticsVideoId: String? = null
    private var youtubeExtractionResult = "Ainda não executado"
    private var youtubeSourceHttpResult = "Ainda não executado"
    private var youtubeMedia3Result = "Ainda não executado"
    private val siteDiagnostics = WeakHashMap<WebView, MutableList<String>>()
    private var lastSeuImpostoImeVisible: Boolean? = null
    private var lastSeuImpostoImeOpenAt = 0L
    private fun isSeuImposto(url: String?): Boolean = websiteHost(url) == "seuimposto.com" ||
        websiteHost(url) == "www.seuimposto.com"
    private fun seuImpostoPanEnabled(): Boolean =
        getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
            .getBoolean("keyboard_pan", true)
    private fun updateKeyboardWindowMode(url: String?) {
        val pan = isSeuImposto(url) && seuImpostoPanEnabled()
        window.setSoftInputMode(if (pan) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        findViewById<View>(android.R.id.content)?.let { ViewCompat.requestApplyInsets(it) }
    }
    private fun safeDiagnosticPath(uri: Uri): String {
        // Nunca registra query, fragmento ou segmentos identificadores da sessão.
        return "/" + uri.pathSegments.take(4).joinToString("/") { segment ->
            if (segment.length <= 24 && segment.matches(Regex("[A-Za-z_.-]+"))) segment
            else ":id"
        }
    }
    private fun consoleErrorCategory(message: String): String {
        val source = message.lowercase(java.util.Locale.ROOT)
        return when {
            "cors" in source || "cross-origin" in source -> "CORS"
            "websocket" in source -> "WebSocket"
            "failed to fetch" in source || "networkerror" in source -> "Falha fetch/rede"
            "content security policy" in source || "csp" in source -> "Política CSP"
            "uncaught" in source || "typeerror" in source || "referenceerror" in source -> "Exceção JavaScript"
            "404" in source || "503" in source -> "Erro HTTP no console"
            else -> "Outro aviso/erro"
        }
    }
    private fun recordSiteDiagnostic(view: WebView, event: String) {
        synchronized(siteDiagnostics) {
            val events = siteDiagnostics[view] ?: return
            if (events.size >= 60) events.removeAt(0)
            events.add("t=${System.currentTimeMillis() % 600000} · $event")
        }
    }
    // WebViews em espera pelo transporte de uma nova janela.
    private val pendingPopups = mutableSetOf<WebView>()
    private var activeIndex = 0
    private val webView get() = tabs[activeIndex].view
    private lateinit var webHost: LinearLayout
    private lateinit var browserBar: LinearLayout
    private lateinit var browserAddressRow: LinearLayout
    private lateinit var browserActionsRow: LinearLayout

    /** Largura real disponível; celulares deitados mantêm os comandos em duas faixas. */
    private fun arrangeBrowserToolbar(widthDp: Int) {
        if (!::browserBar.isInitialized || !::browserAddressRow.isInitialized ||
            !::browserActionsRow.isInitialized) return
        val expanded = widthDp >= 840
        browserBar.removeAllViews()
        browserBar.orientation = if (expanded) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        browserBar.gravity = if (expanded) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.NO_GRAVITY
        if (expanded) {
            browserBar.addView(browserAddressRow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 3f))
            browserBar.addView(browserActionsRow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 4f).apply {
                marginStart = dp(12)
            })
        } else {
            browserBar.addView(browserAddressRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            browserBar.addView(browserActionsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView
    private lateinit var tabsButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var store: BrowserData
    private var statusDefaultColor = 0
    private val defaultWebUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }
    private fun websiteHost(url: String?): String? = runCatching {
        Uri.parse(url).host?.lowercase(java.util.Locale.ROOT)
    }.getOrNull()

    private fun siteDisplayMode(url: String?): String {
        val host = websiteHost(url) ?: return "mobile"
        return getSharedPreferences("site_display_modes", MODE_PRIVATE)
            .getString(host, "mobile") ?: "mobile"
    }

    /** Preferência explícita por domínio. Nunca aplica a otimização a sites não configurados. */
    private fun turboMode(url: String?): String {
        val host = websiteHost(url) ?: return "off"
        return getSharedPreferences("site_turbo", MODE_PRIVATE)
            .getString(host, "off")?.takeIf { it in setOf("off", "compatible", "balanced", "aggressive") }
            ?: "off"
    }

    private fun turboProtected(url: String?): Boolean {
        val host = websiteHost(url) ?: return true
        return isSeuImposto(url) || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "google.com" || host.endsWith(".google.com")
    }

    private fun effectiveTurboMode(url: String?): String {
        val selected = turboMode(url)
        return if (turboProtected(url) && selected in setOf("balanced", "aggressive"))
            "compatible" else selected
    }

    private fun applyTurboNetworkPolicy(view: WebView, url: String?) {
        // O bloqueio é reversível, mas imagens descartadas só voltam após recarga.
        view.settings.blockNetworkImage = effectiveTurboMode(url) == "aggressive"
    }

    private fun applyTurboPagePolicy(view: WebView, url: String) {
        val mode = effectiveTurboMode(url)
        if (mode == "off" || view.url != url) return
        val balanced = mode == "balanced" || mode == "aggressive"
        val script = """(function(){
          try {
            if (!document.head || document.getElementById('__nmTurboStyle')) return;
            const style=document.createElement('style');
            style.id='__nmTurboStyle';
            style.textContent='html{scroll-behavior:auto!important}' +
              '*{animation-duration:.08s!important;transition-duration:.08s!important}';
            document.head.appendChild(style);
            if ($balanced) {
              // Somente blocos longos abaixo da dobra e sem campos ou mídia.
              const targets=document.querySelectorAll('main > article, main > section, article > section');
              let changed=0;
              for (const el of targets) {
                if (changed>=20) break;
                const rect=el.getBoundingClientRect();
                if (rect.height<320 || rect.top<innerHeight*1.5) continue;
                if (el.querySelector('input,textarea,button,form,video,audio,iframe,[contenteditable]')) continue;
                el.style.contentVisibility='auto';
                el.style.containIntrinsicSize='auto 600px';
                changed++;
              }
            }
          } catch (_) {}
        })()"""
        view.evaluateJavascript(script, null)
    }

    private fun configureSiteDisplay(view: WebView, url: String?) {
        // Exceção restrita ao Instagram híbrido: permite play() disparado
        // pelo controle nativo, que não é reconhecido como gesto DOM da página.
        val host = websiteHost(url)
        val instagram = host == "instagram.com" || host?.endsWith(".instagram.com") == true
        view.settings.mediaPlaybackRequiresUserGesture =
            !(instagram && siteDisplayMode(url) == "hybrid")
        when (siteDisplayMode(url)) {
            "desktop", "hybrid" -> {
                // Apenas a identificação desktop; o modo híbrido mantém viewport
                // estreita e CSS responsivo no tamanho real do aparelho.
                val version = Regex("Chrome/([0-9.]+)")
                    .find(defaultWebUserAgent)?.groupValues?.getOrNull(1) ?: "120.0.0.0"
                view.settings.userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$version Safari/537.36"
                val desktop = siteDisplayMode(url) == "desktop"
                view.settings.useWideViewPort = desktop
                view.settings.loadWithOverviewMode = desktop
            }
            else -> {
                // Teste controlado de identidade: o motor permanece Chromium.
                val uaMode = if (isSeuImposto(url)) seuImpostoUaProbe() else "webview"
                view.settings.userAgentString = when (uaMode) {
                    "chrome" -> defaultWebUserAgent.replace("; wv)", ")")
                        .replace(" Version/4.0", "")
                    "firefox" -> "Mozilla/5.0 (Android 16; Mobile; rv:140.0) " +
                        "Gecko/140.0 Firefox/140.0"
                    else -> defaultWebUserAgent
                }
                view.settings.useWideViewPort = false
                view.settings.loadWithOverviewMode = false
            }
        }
    }

    private fun seuImpostoUaProbe(): String {
        val prefs = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
        return prefs.getString("ua_probe", null) ?: if (prefs.getBoolean("plain_chrome_ua", false))
            "chrome" else "webview"
    }

    private fun showSiteDisplayModes() {
        val currentUrl = webView.url ?: tabs.getOrNull(activeIndex)?.url
        val host = websiteHost(currentUrl)
        if (host == null) {
            Toast.makeText(this, "Abra um site primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        val choices = arrayOf("Celular (padrão)",
            "Híbrido: funções desktop + layout compacto",
            "Computador: layout completo")
        val modes = listOf("mobile", "hybrid", "desktop")
        val selected = modes.indexOf(siteDisplayMode(currentUrl)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Modo de exibição · $host")
            .setSingleChoiceItems(choices, selected) { dialog, index ->
                dialog.dismiss()
                val chosen = modes[index]
                if (chosen != siteDisplayMode(currentUrl)) {
                    getSharedPreferences("site_display_modes", MODE_PRIVATE).edit()
                        .putString(host, chosen).apply()
                    configureSiteDisplay(webView, currentUrl)
                    webView.reload()
                    updateInstagramOverlay()
                    Toast.makeText(this, "Modo aplicado a $host", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private var loaded = false
    private fun isDarkTheme() = getSharedPreferences("appearance", MODE_PRIVATE)
        .getString("theme", "light") == "dark"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun uiBackground() = if (isDarkTheme()) 0xFF101720.toInt() else 0xFFF4F6FA.toInt()
    private fun uiSurface() = if (isDarkTheme()) 0xFF202B39.toInt() else Color.WHITE
    private fun uiInputSurface() = if (isDarkTheme()) 0xFF2C4055.toInt() else Color.WHITE
    private fun uiForeground() = if (isDarkTheme()) Color.WHITE else 0xFF182536.toInt()
    private fun uiMuted() = if (isDarkTheme()) 0xFFB7C3D0.toInt() else 0xFF536477.toInt()
    private fun uiAccent() = if (isDarkTheme()) 0xFF8CCBFF.toInt() else 0xFF1769A6.toInt()
    private fun uiOutline() = if (isDarkTheme()) 0xFF405366.toInt() else 0xFFDCE3EC.toInt()
    private fun uiShape(color: Int, radius: Int = 14, outline: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (outline) setStroke(dp(1), uiOutline())
        }
    /** Evita campos escuros com texto invisível ao alternar entre tema do app e do Android. */
    private fun styleInput(field: EditText) {
        field.setTextColor(uiForeground())
        field.setHintTextColor(uiMuted())
        field.minHeight = dp(48)
        field.background = uiShape(uiInputSurface(), 12, true)
        field.setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun uiAction(label: String, primary: Boolean = false): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(4), 0, dp(4), 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(if (primary) Color.WHITE else uiForeground())
        background = uiShape(if (primary) 0xFF1769A6.toInt() else uiSurface(), 12, !primary)
    }
    private data class MenuAction(
        val section: String, val label: String, val description: String,
        val destructive: Boolean = false, val invoke: () -> Unit
    )

    /** Navegação secundária em uma lista agrupada, com alvos de toque grandes. */
    private fun showActionHub(title: String, subtitle: String, actions: List<MenuAction>) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(uiBackground())
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(uiForeground())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(uiAction("Fechar").apply {
            contentDescription = "Fechar $title"
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(uiMuted())
            setPadding(0, dp(4), 0, dp(12))
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        var previousSection: String? = null
        for (entry in actions) {
            if (entry.section != previousSection) {
                previousSection = entry.section
                list.addView(TextView(this).apply {
                    text = entry.section
                    textSize = 14f
                    setTextColor(uiAccent())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(4), dp(12), dp(4), dp(8))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                minimumHeight = dp(60)
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = uiShape(uiSurface(), 12, true)
                isClickable = true
                isFocusable = true
                contentDescription = "${entry.label}. ${entry.description}"
                setOnClickListener { dialog.dismiss(); entry.invoke() }
            }
            row.addView(TextView(this).apply {
                text = entry.label
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (entry.destructive) 0xFFC45049.toInt() else uiForeground())
            })
            row.addView(TextView(this).apply {
                text = entry.description
                textSize = 12f
                setTextColor(uiMuted())
            })
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
        }
        dialog.setContentView(root)
        dialog.show()
        val contentHeight = dp(120 + actions.size * 80 + actions.map { it.section }.distinct().size * 35)
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(600)),
            minOf(resources.displayMetrics.heightPixels * 85 / 100, dp(740), contentHeight))
    }

    private fun showThemePicker() {
        val names = arrayOf("Claro", "Escuro")
        val current = if (isDarkTheme()) 1 else 0
        AlertDialog.Builder(this).setTitle("Aparência do navegador")
            .setSingleChoiceItems(names, current) { dialog, choice ->
                dialog.dismiss()
                if (choice != current) {
                    persistTabs()
                    getSharedPreferences("appearance", MODE_PRIVATE).edit()
                        .putString("theme", if (choice == 1) "dark" else "light").apply()
                    // Recria apenas a interface. As URLs das abas são restauradas.
                    recreate()
                }
            }.setNegativeButton("Cancelar", null).show()
    }
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
    private var browserIsResumed = false
    private var backgroundServiceStartPending = false
    // A notificação pode desativar o recurso enquanto a Activity ainda existe.
    // O listener mantém as abas e o monitoramento sincronizados com essa escolha.
    private val backgroundAudioPrefs by lazy {
        getSharedPreferences("media_options", MODE_PRIVATE)
    }
    private val backgroundAudioPreferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "background_audio") {
                handler.post {
                    if (!isDestroyed && !isFinishing) {
                        applyBackgroundAudioState(prefs.getBoolean("background_audio", false))
                    }
                }
            }
        }
    // Apenas metadados de requisições. Nunca intercepta conteúdo, tokens nem corpos HTTP.
    private val mediaCandidates = WeakHashMap<WebView, LinkedHashSet<String>>()
    private val mediaCandidateLock = Any()
    // Contadores agregados: nunca armazenam URL, caminho, query ou cabeçalhos HTTP.
    private val mediaNetworkSignals = WeakHashMap<WebView, LinkedHashMap<String, Int>>()
    private fun mediaSignal(uri: Uri): String? {
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        val yt = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "youtu.be"
        if (!yt) return null
        return when {
            path.endsWith(".mpd") -> "DASH: extensão mpd"
            path.endsWith(".m3u8") -> "HLS: extensão m3u8"
            path.endsWith(".m4s") -> "Segmento: m4s"
            path.endsWith(".ts") -> "Segmento: ts"
            path.contains("videoplayback") -> {
                // Somente categorias derivadas de parâmetros conhecidos; nunca logar valores.
                // "mime" é indício declarado pela URL, não confirmação do conteúdo HTTP.
                val mime = runCatching { uri.getQueryParameter("mime")?.lowercase(java.util.Locale.ROOT) }
                    .getOrNull().orEmpty()
                val mediaType = when {
                    mime.startsWith("video/") -> "vídeo declarado"
                    mime.startsWith("audio/") -> "áudio declarado"
                    else -> "tipo não declarado"
                }
                val adaptive = runCatching { uri.getQueryParameterNames().contains("range") }
                    .getOrDefault(false)
                val hasItag = runCatching { uri.getQueryParameterNames().contains("itag") }
                    .getOrDefault(false)
                "videoplayback: $mediaType" +
                    (if (adaptive) " · range indicado" else "") +
                    (if (hasItag) " · itag presente" else "")
            }
            else -> null
        }
    }
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
        "HLS · playlist" -> 0
        "DASH · manifesto" -> 1
        "Vídeo direto" -> 2
        else -> 3
    }

    private fun isStreamingManifest(url: String): Boolean =
        detectedMediaKind(url) in listOf("HLS · playlist", "DASH · manifesto")

    /** Mostra fontes de streaming explicitamente observadas, nunca segmentos isolados. */
    private fun showStreamingManifests() {
        val current = webView
        scanDeclaredMedia(current) {
            if (current !== webView || isFinishing || isDestroyed) return@scanDeclaredMedia
            val manifests = synchronized(mediaCandidateLock) {
                mediaCandidates[current]?.toList().orEmpty().reversed()
            }.filter(::isStreamingManifest).distinct().take(30)
            if (manifests.isEmpty()) {
                AlertDialog.Builder(this).setTitle("HLS / DASH")
                    .setMessage("Nenhum manifesto .m3u8 ou .mpd foi observado nesta página. " +
                        "Isso não significa ausência de vídeo: players MSE/blob podem usar " +
                        "requisições sem extensões. Não é seguro tratar segmentos como playlists.")
                    .setPositiveButton("OK", null).show()
            } else {
                val labels = manifests.map { url ->
                    "${detectedMediaKind(url)} · ${Uri.parse(url).host ?: "servidor desconhecido"}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Manifestos HLS / DASH (${manifests.size})")
                    .setItems(labels) { _, index -> startMedia3(manifests[index]) }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    // O Android devolve aqui o arquivo escolhido em formulários <input type="file">.
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == RESULT_OK)
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        else null
        fileCallback?.onReceiveValue(selected)
        fileCallback = null
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
                if (!isFinishing && !isDestroyed && controller.currentMediaItem != null &&
                    playerDisplay.shouldOpenFullscreen(VideoPipActivity.active)) {
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

    // Mantém o Controle de Atenção nas abas HTML5 que reproduzem em segundo plano.
    // Monitoramento de melhor esforço: somente vídeo/áudio acessível pela própria
    // página; frames de outras origens não são inspecionados.
    private val backgroundMediaSeen = java.util.WeakHashMap<WebView, Long>()
    // O documento de bloqueio usa about:blank: nunca substituir por ele a URL
    // da publicação que deve permanecer na lista de abas e no retorno da pausa.
    private val attentionBlockedSources = java.util.WeakHashMap<WebView, String>()
    private fun rememberAttentionSource(view: WebView, originalUrl: String?) {
        val candidate = attentionBlockedSources[view] ?: originalUrl.orEmpty()
        if (candidate.startsWith("https://") || candidate.startsWith("http://")) {
            attentionBlockedSources[view] = candidate
            tabs.firstOrNull { it.view === view }?.url = candidate
        }
    }
    private fun forceStopWebMedia() {
        val script = """(function(){
            window.__nmForceMediaStop=true;
            if(!window.__nmForceStopListener){
                window.__nmForceStopListener=true;
                document.addEventListener('play',function(e){
                    const m=e.target;
                    if(window.__nmForceMediaStop&&m instanceof HTMLMediaElement){
                        try{m.pause();m.autoplay=false;m.loop=false;}catch(_){}
                    }
                },true);
                // Um toque real na página libera uma nova reprodução intencional.
                document.addEventListener('pointerdown',function(){
                    window.__nmForceMediaStop=false;
                },true);
                document.addEventListener('keydown',function(){
                    window.__nmForceMediaStop=false;
                },true);
            }
            document.querySelectorAll('video,audio').forEach(function(m){
                try{m.pause();m.autoplay=false;m.loop=false;
                    m.removeAttribute('autoplay');}catch(_){}
            });
        })()"""
        tabs.forEach { tab -> runCatching { tab.view.evaluateJavascript(script, null) } }
        backgroundMediaSeen.clear()
    }

    private fun forceStopAllMediaFromMenu() {
        queueSyncView = null
        queueSyncPage = null
        handler.removeCallbacks(queueSyncTicker)
        StopAllMediaReceiver.stopAll(this)
        Toast.makeText(this, "Todas as reproduções foram interrompidas", Toast.LENGTH_SHORT).show()
    }
    /** Evita loop/auto-próximo depois que uma mídia HTML5 termina fora da tela.
     * A proteção é por documento: outras abas e players Media3 seguem independentes.
     */
    private fun setBackgroundEndGuard(enabled: Boolean) {
        if (tabs.isEmpty()) return
        val script = if (enabled) """(function(){
            let g=window.__nmBackgroundEndGuard;
            if(!g){
                g={enabled:false,finished:false,started:new WeakSet(),ended:new WeakSet(),loops:[]};
                window.__nmBackgroundEndGuard=g;
                document.addEventListener('ended',function(e){
                    if(!g.enabled||!(e.target instanceof HTMLMediaElement))return;
                    g.finished=true;g.ended.add(e.target);
                    // Não entregar o fim ao autoplay/auto-próximo da página no fundo.
                    e.stopImmediatePropagation();
                },true);
                // Alguns players voltam para zero pouco antes de emitir 'ended'.
                document.addEventListener('timeupdate',function(e){
                    const m=e.target;
                    if(g.enabled&&m instanceof HTMLMediaElement&&
                        Number.isFinite(m.duration)&&m.duration>1&&
                        m.currentTime>=m.duration-0.12){
                        g.finished=true;g.ended.add(m);
                    }
                },true);
                document.addEventListener('play',function(e){
                    const m=e.target;
                    if(!g.enabled||!(m instanceof HTMLMediaElement))return;
                    if(g.ended.has(m)||(g.finished&&!g.started.has(m))){
                        try{m.pause();m.autoplay=false;m.removeAttribute('autoplay')}catch(_){}
                    }
                },true);
            }
            if(!g.enabled){
                g.finished=false;g.started=new WeakSet();g.ended=new WeakSet();g.loops=[];
            }
            g.enabled=true;
            document.querySelectorAll('audio,video').forEach(function(m){
                if(!m.paused&&!m.ended){
                    g.started.add(m);
                    if(m.loop){g.loops.push(m);m.loop=false;}
                }
            });
        })()""" else """(function(){
            const g=window.__nmBackgroundEndGuard;
            if(!g)return;
            g.enabled=false;g.finished=false;
            for(const m of g.loops){if(m.isConnected)m.loop=true;}
            g.loops=[];
        })()"""
        tabs.forEach { tab ->
            runCatching { tab.view.evaluateJavascript(script, null) }
        }
    }
    private fun detectAudibleWebMedia() {
        if (!backgroundAudioEnabled || tabs.isEmpty()) return
        // Apenas elementos HTML5 audíveis; o player Media3 tem serviço próprio.
        // Frames de terceiros e WebAudio não são inspecionáveis daqui.
        val script = "(function(){try{return Array.from(document.querySelectorAll('audio,video'))" +
            ".some(v=>!v.paused&&!v.ended&&!v.muted&&v.volume>0)}catch(e){return false}})()"
        tabs.forEach { tab ->
            val view = tab.view
            val page = view.url ?: return@forEach
            view.evaluateJavascript(script) { value ->
                if (!backgroundAudioEnabled || isFinishing || isDestroyed ||
                    tabs.none { it.view === view } || view.url != page || value != "true") {
                    return@evaluateJavascript
                }
                BackgroundAudioService.markMediaObserved()
                // A criação de um foreground service é feita somente com a
                // Activity visível, respeitando as restrições recentes do Android.
                if (browserIsResumed && !BackgroundAudioService.running &&
                    !backgroundServiceStartPending) {
                    backgroundServiceStartPending = true
                    runCatching {
                        androidx.core.content.ContextCompat.startForegroundService(
                            this, Intent(this, BackgroundAudioService::class.java).apply {
                                action = BackgroundAudioService.ACTION_MEDIA_ACTIVE
                            })
                    }.onFailure {
                        android.util.Log.w("NavegadorAudio", "Falha ao iniciar serviço de mídia", it)
                    }
                    handler.postDelayed({ backgroundServiceStartPending = false }, 2000L)
                }
            }
        }
    }

    private val audibleMediaTicker = object : Runnable {
        override fun run() {
            if (!browserIsResumed || !backgroundAudioEnabled || isFinishing || isDestroyed) return
            detectAudibleWebMedia()
            handler.postDelayed(this, 2000L)
        }
    }
    private fun checkBackgroundTabMedia(includeActive: Boolean = false) {
        if (!backgroundAudioEnabled || (tabs.size < 2 && !includeActive)) {
            backgroundMediaSeen.clear()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        // Evita cobrar simultaneamente duas abas do mesmo domínio no mesmo ciclo.
        val chargedDomains = HashSet<String>()
        tabs.forEachIndexed { index, tab ->
            if (!includeActive && index == activeIndex) return@forEachIndexed
            val view = tab.view
            val page = view.url ?: return@forEachIndexed
            if (Attention.status(page) == null) return@forEachIndexed
            val domain = android.net.Uri.parse(page).host ?: return@forEachIndexed
            val previous = backgroundMediaSeen.put(view, now)
            val elapsed = if (previous == null) 0L else (now - previous).coerceIn(0L, 5000L)
            view.evaluateJavascript(
                "(function(){return Array.from(document.querySelectorAll('video,audio')).some(v=>!v.paused&&!v.ended)})()"
            ) { value ->
                if (isFinishing || isDestroyed || tabs.none { it.view === view } ||
                    view.url != page) return@evaluateJavascript
                // Verifica bloqueio mesmo que a reprodução tenha sido pausada
                // temporariamente. Bloqueio não pode depender do estado Play.
                val existingBlock = Attention.checkNavigation(page)
                if (value != "true" && existingBlock == null) return@evaluateJavascript
                if (!chargedDomains.add(domain)) return@evaluateJavascript
                val verdict = existingBlock ?: Attention.backgroundTabPlaybackTick(page, elapsed)
                if (verdict != null) {
                    view.evaluateJavascript(
                        "(function(){document.querySelectorAll('video,audio').forEach(v=>{v.pause();v.removeAttribute('autoplay')})})()", null
                    )
                    // A aba deixa de oferecer controles da página bloqueada.
                    if (view.url == page) {
                        rememberAttentionSource(view, page)
                        view.stopLoading()
                        view.loadDataWithBaseURL(null, blockHtmlFor(verdict),
                            "text/html", "utf-8", null)
                    }
                    android.util.Log.i("Attention", "Mídia HTML5 pausada em aba de segundo plano: " +
                        verdict.site.domain)
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (tabs.isNotEmpty()) {
                val verdict = Attention.onUrl(webView.url) ?: Attention.tick()
                if (verdict == null) servedBlockUrl = null
                else if (webView.url != servedBlockUrl) showBlocked(verdict)
                updateStatus()
            }
            checkBackgroundTabMedia()
            handler.postDelayed(this, 5000)
        }
    }
    // Fora da tela a contagem da navegação para, mas a mídia HTML5 que continua
    // realmente tocando ainda deve consumir a cota. Não recria nem dá play em abas.
    private val backgroundMediaTicker = object : Runnable {
        override fun run() {
            if (browserIsResumed || !backgroundAudioEnabled || isFinishing || isDestroyed) return
            setBackgroundEndGuard(true)
            checkBackgroundTabMedia(includeActive = true)
            detectAudibleWebMedia()
            handler.postDelayed(this, 5000L)
        }
    }

    private fun applyBackgroundAudioState(enabled: Boolean) {
        backgroundAudioEnabled = enabled
        if (!enabled) backgroundMediaSeen.clear()
        handler.removeCallbacks(audibleMediaTicker)
        if (!enabled) {
            setBackgroundEndGuard(false)
            stopService(Intent(this, BackgroundAudioService::class.java))
        }
        if (!loaded) return
        tabs.forEachIndexed { index, tab ->
            if (enabled || (browserIsResumed && index == activeIndex)) tab.view.onResume()
            else tab.view.onPause()
        }
        handler.removeCallbacks(backgroundMediaTicker)
        if (enabled && !browserIsResumed) handler.post(backgroundMediaTicker)
        if (enabled && browserIsResumed) handler.post(audibleMediaTicker)
    }

    private val extLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            persistTabs()
            recreate()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (isDarkTheme()) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        YoutubeExtractor.configure(applicationContext)
        liveBrowser = java.lang.ref.WeakReference(this)
        // Baseline do teste isolado: evita a dupla intervenção experimental
        // na WebView do Seu Imposto. Migra uma única vez as opções antigas;
        // ambas continuam reversíveis pelo menu Ferramentas.
        getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE).let { prefs ->
            if (!prefs.getBoolean("keyboard_baseline_migrated", false)) {
                prefs.edit()
                    .putBoolean("keyboard_compat", false)
                    .putBoolean("focus_recovery", false)
                    .putBoolean("keyboard_baseline_migrated", true)
                    .apply()
            }
        }
        Attention.init(this)
        store = BrowserData(this)
        backgroundAudioPrefs.registerOnSharedPreferenceChangeListener(backgroundAudioPreferenceListener)
        backgroundAudioEnabled = backgroundAudioPrefs.getBoolean("background_audio", false)
        // A configuração não inicia serviço: somente reprodução audível detectada.
        extensionManager = ExtensionManager(this).also { it.seedFromAssets() }
        extensionList = extensionManager!!.loadEnabled()
        blockerList = extensionList.flatMap { e -> e.blockRegexes.map { Blocker(e.name, Regex(it)) } }

        urlBar = EditText(this).apply {
            styleInput(this)
            setSingleLine()
            hint = "URL ou busca"
            textSize = 15f
            minHeight = dp(48)
            setSingleLine(true)
            setPadding(dp(14), 0, dp(12), 0)
            background = uiShape(uiInputSurface(), 14, true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        tabsButton = uiAction("Abas").apply { setOnClickListener { showTabs() } }
        favoriteButton = Button(this).apply { text = "☆"; setOnClickListener { toggleFavorite() } }
        val backButton = uiAction("‹").apply {
            text = "‹"
            contentDescription = "Voltar na página"
            setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        }
        val forwardButton = uiAction("›").apply {
            text = "›"
            contentDescription = "Avançar na página"
            setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        }
        val mediaButton = uiAction("Mídia").apply { setOnClickListener { showMediaMenu() } }
        val menuButton = uiAction("Menu").apply { setOnClickListener { showMainMenu() } }
        // Navegação frequente em uma linha; endereço na linha superior.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(backButton, forwardButton, tabsButton, mediaButton, menuButton).forEach {
                addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                })
            }
        }
        val reloadButton = uiAction("↻").apply {
            text = "↻"
            contentDescription = "Recarregar página atual"
            setOnClickListener {
                if (tabs.isNotEmpty()) webView.reload()
            }
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(urlBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(reloadButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(6)
            })
        }
        browserBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiBackground())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        browserAddressRow = addressRow
        browserActionsRow = actions
        arrangeBrowserToolbar(resources.configuration.screenWidthDp)
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
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0xFF202B3A.toInt())
            }
            miniTitle = TextView(this@MainActivity).apply {
                text = "▶ Mídia em reprodução"
                setTextColor(android.graphics.Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = "Abrir player de mídia"
                setOnClickListener { showNativeVideoPlayer() }
            }
            miniPlayerBar.addView(miniTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            miniPlayButton = uiAction("Pausar").apply {
                text = "Pausar"
                setOnClickListener {
                    miniController?.let { if (it.isPlaying) it.pause() else it.play() }
                }
            }
            miniPlayerBar.addView(miniPlayButton)
            miniPlayerBar.addView(uiAction("Abrir").apply {
                setOnClickListener { showNativeVideoPlayer() }
            })
            addView(miniPlayerBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        playerLayer = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        setContentView(playerLayer)
        createInstagramOverlay()
        updatePipAutoEnter(false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val showingIme = insets.isVisible(WindowInsetsCompat.Type.ime())
            val site = tabs.getOrNull(activeIndex)?.view
            val currentSite = site?.let { isSeuImposto(it.url) } == true
            if (currentSite && lastSeuImpostoImeVisible != showingIme) {
                if (showingIme) lastSeuImpostoImeOpenAt = SystemClock.elapsedRealtime()
                site?.let { recordSiteDiagnostic(it,
                    "IME ${if (showingIme) "aberto" else "fechado"} · altura ${ime.bottom}") }
                site?.let { current ->
                    current.post {
                        recordSiteDiagnostic(current,
                            "Layout após IME · raiz=${v.height} · WebView=${current.height}")
                    }
                }
                lastSeuImpostoImeVisible = showingIme
            }
            // Experimento reversível: deixa o ajuste de altura da janela cuidar
            // do teclado, mantendo apenas a proteção das barras do sistema.
            val compat = currentSite && getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                .getBoolean("keyboard_compat", false)
            // Em adjustPan o Android desloca a janela sem alterar o tamanho do DOM;
            // aplicar também o inset do IME na raiz anularia essa proteção.
            val pan = currentSite && seuImpostoPanEnabled()
            val bottom = if (compat || pan) bars.bottom else maxOf(bars.bottom, ime.bottom)
            v.setPadding(bars.left, bars.top, bars.right, bottom)
            insets
        }

        val prefs = getSharedPreferences("tabs", MODE_PRIVATE)
        pinnedTabIds.addAll(prefs.getStringSet("pinned_tab_ids_v1", emptySet()).orEmpty())
        restoreConstellationNames()
        val savedConstellations = prefs.getString("constellations_v1", null)
        if (savedConstellations != null) {
            runCatching {
                val entries = org.json.JSONArray(savedConstellations)
                for (i in 0 until minOf(entries.length(), MAX_TABS)) {
                    val item = entries.getJSONObject(i)
                    val url = item.optString("url", HOME)
                    if (!url.startsWith("https://") && !url.startsWith("http://")) continue
                    val title = item.optString("title", "Nova aba").take(100)
                    val group = item.optString("group", "Exploração").takeIf { it in constellations } ?: "Exploração"
                    val id = safeTabId(item.optString("id", ""))
                    val profile = storedProfile(item.optString("profile", ""))
                    if (item.optBoolean("live", false) && tabs.size < BrowserTabPolicy.MAX_RESIDENT_TABS) {
                        val tab = Tab(createWebView(profile), title, url, group, id = id, profile = profile)
                        tabs.add(tab)
                        restoreTabNavigation(tab)
                    } else parkedTabs.add(ParkedTab(url, title, group, id, profile))
                }
            }.onFailure {
                tabs.forEach { it.view.destroy() }
                tabs.clear(); parkedTabs.clear()
            }
        }
        if (tabs.isEmpty()) {
            if (parkedTabs.isNotEmpty()) {
                // Sessão válida somente com abas guardadas: recupera uma, sem duplicar o legado.
                val first = parkedTabs.removeAt(0)
                val tab = Tab(createWebView(first.profile), first.title, first.url,
                    first.constellation, id = first.id, profile = first.profile)
                tabs.add(tab)
                restoreTabNavigation(tab)
            } else {
                val urls = prefs.getString("urls", "")!!.split('\n')
                    .filter { it.startsWith("https://") || it.startsWith("http://") }.take(MAX_TABS)
                val first = urls.ifEmpty { listOf(HOME) }
                for (url in first.take(BrowserTabPolicy.MAX_RESIDENT_TABS))
                    tabs.add(Tab(createWebView(), url = url))
                if (savedConstellations == null)
                    urls.drop(BrowserTabPolicy.MAX_RESIDENT_TABS).forEach {
                        parkedTabs.add(ParkedTab(it, "Nova aba", "Exploração"))
                    }
            }
        }
        activeIndex = tabs.indexOfFirst { it.id == prefs.getString("active_id", null) }
            .takeIf { it >= 0 } ?: BrowserTabPolicy.restoredIndex(prefs.getInt("active", 0), tabs.size)
        showActive()
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
    private fun createWebView(profile: String = ""): WebView {
        val view = WebView(this)
        // Vincular ANTES de acessar settings, JavaScript ou navegar.
        if (profile == isolatedGoogleProfile && supportedGoogleProfile())
            WebViewCompat.setProfile(view, isolatedGoogleProfile)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        configureSiteDisplay(view, null)
        applyTurboNetworkPolicy(view, null)
        // Preferência de cores respeitada pela WebView quando o motor oferece suporte.
        // Sites que implementam tema próprio continuam controlando sua aparência.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                view.settings, isDarkTheme())
        }
        view.settings.setSupportMultipleWindows(true)
        // target=_blank funciona, mas window.open automático sem gesto fica desabilitado.
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                applyTurboNetworkPolicy(v, url)
                if (url.startsWith("http://") || url.startsWith("https://"))
                    attentionBlockedSources.remove(v)
                if (tabs.getOrNull(activeIndex)?.view === v) updateKeyboardWindowMode(url)
                synchronized(siteDiagnostics) {
                    if (isSeuImposto(url)) siteDiagnostics[v] = mutableListOf("Navegação iniciada")
                    else siteDiagnostics.remove(v)
                }
                val pageHost = websiteHost(url)
                val instagramHybrid = (pageHost == "instagram.com" ||
                    pageHost?.endsWith(".instagram.com") == true) &&
                    siteDisplayMode(url) == "hybrid"
                v.settings.mediaPlaybackRequiresUserGesture = !instagramHybrid
                val (cookieBefore, cookieAfter) = applyLoginCookiePolicy(v, url)
                recordGoogleLoginAudit("Navegação iniciada · cookies terceiros: $cookieBefore → $cookieAfter", url, v)
                recordGoogleLoginTransition(v, url, "início do documento")
                val t = tabs.firstOrNull { it.view === v } ?: return
                synchronized(mediaCandidateLock) { mediaCandidates.remove(v); mediaRequestOrigins.remove(v); mediaNetworkSignals.remove(v) }
                val shownUrl = attentionBlockedSources[v] ?: url
                t.url = shownUrl
                if (v === webView && !urlBar.hasFocus()) urlBar.setText(shownUrl)
                if (v === webView) { updateFavorite(); updateInstagramOverlay() }
            }
            override fun onPageFinished(v: WebView, url: String) {
                recordGoogleLoginAudit("Navegação concluída", url, v)
                if (isSeuImposto(url)) {
                    recordSiteDiagnostic(v, "Navegação concluída")
                    val recoverFocus = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                        .getBoolean("focus_recovery", false)
                    v.evaluateJavascript("""(function(){
                        if(window.__nmKeyboardAudit)return;
                        const audit={logs:[],attempts:[],lastFocused:null,lastBlurAt:0,
                            detachLogged:false,structureEvents:[]};
                        const recoveryEnabled=$recoverFocus;
                        window.__nmKeyboardAudit=audit;
                        const add=(kind,el,details={})=>{
                            const entry={t:Date.now()%600000,event:kind,tag:el&&el.tagName||'',
                                type:el&&el.type||'',
                                viewport:window.visualViewport?Math.round(visualViewport.height):null,
                                screen:innerHeight,
                                viewportOffset:window.visualViewport?Math.round(visualViewport.offsetTop):null,
                                documentHeight:document.documentElement.clientHeight,...details};
                            audit.logs.push(entry);
                            if(audit.logs.length>60)audit.logs.shift();
                        };
                        const focusState=el=>{
                            const rect=el&&el.getBoundingClientRect?el.getBoundingClientRect():null;
                            const style=el&&el.isConnected?getComputedStyle(el):null;
                            return {connected:!!(el&&el.isConnected),
                                disabled:!!(el&&el.disabled),readOnly:!!(el&&el.readOnly),
                                inert:!!(el&&el.closest&&el.closest('[inert]')),
                                visible:!!(rect&&rect.width>0&&rect.height>0&&style&&
                                    style.display!=='none'&&style.visibility!=='hidden'),
                                documentFocus:document.hasFocus(),
                                activeTag:document.activeElement&&document.activeElement.tagName||''};
                        };
                        // Indicadores estruturais, sem IDs, classes, atributos livres ou texto.
                        const ancestry=el=>{
                            const tags=[];
                            for(let n=el,i=0;n&&i<5;n=n.parentElement,i++)
                                tags.push(n.tagName||'?');
                            return tags.join('>');
                        };
                        const layoutState=()=>({
                            width:innerWidth,
                            compactHeight:matchMedia('(max-height: 400px)').matches,
                            mediumHeight:matchMedia('(max-height: 600px)').matches,
                            narrowWidth:matchMedia('(max-width: 600px)').matches
                        });
                        let lastLayout=JSON.stringify(layoutState());
                        const checkLayout=()=>{
                            const current=JSON.stringify(layoutState());
                            if(current!==lastLayout){
                                add('layout-breakpoint-change',document.activeElement,layoutState());
                                lastLayout=current;
                            }
                        };
                        const inputStates=new WeakMap();
                        const scanInputs=()=>{
                            document.querySelectorAll('input,textarea,[contenteditable="true"]')
                                .forEach(el=>{
                                    const now={disabled:!!el.disabled,readOnly:!!el.readOnly,
                                        ariaDisabled:el.getAttribute('aria-disabled')==='true',
                                        inert:!!(el.closest&&el.closest('[inert]'))};
                                    const before=inputStates.get(el);
                                    inputStates.set(el,now);
                                    if(!before)return;
                                    if(Object.keys(now).some(k=>now[k]!==before[k]))
                                        add('input-state-change',el,{...now,
                                            previousDisabled:before.disabled});
                                });
                        };
                        scanInputs();
                        document.addEventListener('focusin',e=>{
                            audit.lastFocused=e.target;
                            audit.detachLogged=false;
                            add('focusin',e.target,{tree:ancestry(e.target),...layoutState()});
                        },true);
                        document.addEventListener('focusout',e=>{
                            const el=e.target;
                            add('focusout',el,focusState(el));
                            audit.lastBlurAt=Date.now();
                            scanInputs();
                            setTimeout(scanInputs,250);
                            setTimeout(scanInputs,1000);
                            setTimeout(scanInputs,3000);
                            add(e.relatedTarget?'focusout-to-element':'focusout-no-target',
                                e.relatedTarget||el);
                            if(!recoveryEnabled||!el||
                                !(el.matches&&el.matches('input,textarea,[contenteditable="true"]'))||
                                e.relatedTarget||!window.visualViewport||
                                visualViewport.height>innerHeight-120)return;
                            const now=Date.now();
                            audit.attempts=audit.attempts.filter(t=>now-t<10000);
                            if(audit.attempts.length>=2)return;
                            audit.attempts.push(now);
                            const oldRect=el.getBoundingClientRect();
                            const oldParent=el.parentElement;
                            const oldCenter={x:(oldRect.left+oldRect.right)/2,
                                y:(oldRect.top+oldRect.bottom)/2};
                            let observer=null;
                            let timeout=null;
                            let interval=null;
                            let finished=false;
                            let focusTries=0;
                            const waitingForEnabled=new WeakSet();
                            const stop=()=>{finished=true;if(observer)observer.disconnect();
                                if(timeout)clearTimeout(timeout);
                                if(interval)clearInterval(interval);};
                            const restore=()=>{
                                if(finished)return;
                                const active=document.activeElement;
                                if(active&&active!==document.body&&active!==document.documentElement){
                                    stop();return;
                                }
                                if(!window.visualViewport||
                                    Date.now()-audit.lastBlurAt>900){
                                    stop();return;
                                }
                                let target=el.isConnected?el:null;
                                if(!target){
                                    const matches=[...document.querySelectorAll('input,textarea,[contenteditable="true"]')]
                                        .filter(x=>x!==el&&x.tagName===el.tagName&&
                                            (x.type||'')===(el.type||''));
                                    target=matches.find(x=>oldParent&&oldParent.isConnected&&
                                        oldParent.contains(x))||matches.find(x=>{
                                        const r=x.getBoundingClientRect();
                                        return r.width>0&&r.height>0&&
                                            Math.abs((r.left+r.right)/2-oldCenter.x)<180&&
                                            Math.abs((r.top+r.bottom)/2-oldCenter.y)<400;
                                    })||null;
                                }
                                if(!target)return;
                                const stateBefore=focusState(target);
                                if(!stateBefore.connected||stateBefore.disabled||stateBefore.readOnly||
                                    stateBefore.inert||!stateBefore.visible||!stateBefore.documentFocus||
                                    target.getAttribute('aria-disabled')==='true'){
                                    if(!waitingForEnabled.has(target)){
                                        waitingForEnabled.add(target);
                                        add('waiting-input-enabled',target,stateBefore);
                                    }
                                    return;
                                }
                                if(focusTries>=2){stop();return;}
                                focusTries++;
                                add('focus-attempt',target,stateBefore);
                                try{target.focus({preventScroll:true});
                                    if(document.activeElement===target){
                                        stop();
                                        add(target===el?'focus-restored':'replacement-focused',
                                            target,focusState(target));
                                        if(window.__nmFocusBridge)
                                            window.__nmFocusBridge.postMessage('focus-restored');
                                        setTimeout(()=>{
                                            if(document.activeElement!==target)
                                                add('focus-stolen-after-success',target,focusState(target));
                                        },80);
                                    }else add('focus-rejected',target,focusState(target));
                                }catch(_){add('focus-exception',target,focusState(target));}
                            };
                            setTimeout(()=>{
                                if(!el.isConnected)add('input-replaced',el);
                                restore();
                                if(finished)return;
                                observer=new MutationObserver(restore);
                                observer.observe(document.body,{childList:true,subtree:true,
                                    attributes:true,
                                    attributeFilter:['disabled','readonly','aria-disabled','inert']});
                                interval=setInterval(restore,70);
                                timeout=setTimeout(()=>{stop();add('recovery-timeout',el);},900);
                            },0);
                        },true);
                        // Observa estrutura e estados de editabilidade, nunca valores digitados.
                        const monitor=new MutationObserver(changes=>{
                            scanInputs();
                            if(!audit.lastFocused||Date.now()-audit.lastBlurAt>1000)return;
                            if(!audit.lastFocused.isConnected&&!audit.detachLogged){
                                audit.detachLogged=true;
                                add('dom-focused-element-removed',audit.lastFocused);
                            }
                            for(const change of changes){
                                if(change.type!=='childList'||!change.removedNodes.length)continue;
                                const removesFocus=[...change.removedNodes].some(node=>
                                    node===audit.lastFocused||
                                    (node.contains&&node.contains(audit.lastFocused)));
                                if(!removesFocus)continue;
                                // Diferencia remoção isolada do INPUT de remontagem de contêiner.
                                add('focused-node-removal',audit.lastFocused,{
                                    removedTree:ancestry(change.target),
                                    removedTag:[...change.removedNodes]
                                        .find(node=>node===audit.lastFocused||
                                            (node.contains&&node.contains(audit.lastFocused)))?.nodeName||'?',
                                    addedSiblings:change.addedNodes.length,
                                    ...layoutState()});
                                break;
                            }
                            if(!changes.some(change=>change.addedNodes.length))return;
                            let addedInput=null;
                            for(const change of changes){
                                for(const node of change.addedNodes){
                                    if(node.nodeType!==1)continue;
                                    if(node.matches&&node.matches('input,textarea,[contenteditable="true"]'))
                                        addedInput=node;
                                    else if(node.querySelector)
                                        addedInput=node.querySelector('input,textarea,[contenteditable="true"]');
                                    if(addedInput)break;
                                }
                                if(addedInput)break;
                            }
                            if(addedInput)add('dom-new-editable-field',addedInput,
                                {...focusState(addedInput),
                                tree:ancestry(addedInput),...layoutState(),
                                ariaDisabled:addedInput.getAttribute('aria-disabled')==='true'});
                        });
                        monitor.observe(document.documentElement,{childList:true,subtree:true,
                            attributes:true,attributeFilter:['disabled','readonly','aria-disabled','inert']});
                        if(window.visualViewport)
                            visualViewport.addEventListener('resize',()=>{
                                add('viewport-resize',document.activeElement);checkLayout();
                            });
                        window.addEventListener('resize',()=>{
                            add('window-resize',document.activeElement);checkLayout();
                        });
                        add('monitor-start',document.activeElement);
                    })()""", null)
                }
                val t = tabs.firstOrNull { it.view === v } ?: return
                val shownUrl = attentionBlockedSources[v] ?: url
                if (attentionBlockedSources[v] == null) t.title = v.title ?: url
                t.url = shownUrl
                if (v === webView) { updateFavorite(); persistTabs() }
                scanDeclaredMedia(v)
                val pageHost = websiteHost(url)
                if ((pageHost == "instagram.com" || pageHost?.endsWith(".instagram.com") == true) &&
                    siteDisplayMode(url) == "hybrid" &&
                    getSharedPreferences("instagram_controls", MODE_PRIVATE).getBoolean("sound_on", false)) {
                    applyInstagramAudioIntent(v, true)
                }
                applyTurboPagePolicy(v, url)
                updateInstagramOverlay()
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                recordGoogleLoginTransition(v, url,
                    if (isReload) "histórico: recarga" else "histórico: endereço alterado")
                if (!isReload && (url.startsWith("https://") || url.startsWith("http://")))
                    store.addHistory(url, v.title ?: url)
                if (v === webView) Attention.onUrl(url)?.let { showBlocked(it) }
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (request.isForMainFrame) {
                    val (before, after) = applyLoginCookiePolicy(v, target.toString())
                    if (before != after)
                        recordGoogleLoginAudit("Política antes da navegação: $before → $after", target.toString(), v)
                    val redirect = android.os.Build.VERSION.SDK_INT >= 24 && request.isRedirect
                    if (redirect && googleLoginAuditEnabled && googleLoginHostCategory(target.toString()) != null)
                        googleLoginRedirectCount++
                    recordGoogleLoginAudit("Navegação principal · " +
                        (if (redirect) "redirecionamento HTTP" else "não marcado como redirecionamento HTTP") +
                        " · " + (if (request.hasGesture()) "com gesto" else "sem gesto") +
                        " · destino=" + (if (target.scheme == "http" || target.scheme == "https") "WebView" else "externo"),
                        target.toString(), v)
                    recordGoogleLoginTransition(v, target.toString(),
                        if (redirect) "redirecionamento HTTP" else "navegação solicitada")
                }
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
                if (!request.isForMainFrame && request.url.scheme == "https") {
                    mediaSignal(request.url)?.let { category ->
                        synchronized(mediaCandidateLock) {
                            val counts = mediaNetworkSignals.getOrPut(v) { LinkedHashMap() }
                            counts[category] = (counts[category] ?: 0) + 1
                        }
                    }
                }
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
                if (hit != null && synchronized(siteDiagnostics) { siteDiagnostics.containsKey(v) })
                    recordSiteDiagnostic(v, "Extensão ${hit.name} bloqueou " +
                        "${request.method} ${request.url.host}${safeDiagnosticPath(request.url)}")
                if (hit != null) return if (request.isForMainFrame)
                    htmlResponse(BlockPage.html("Bloqueado", hit.name, ""))
                    else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                if (request.isForMainFrame) {
                    val verdict = Attention.checkNavigation(url)
                    if (verdict != null) return htmlResponse(blockHtmlFor(verdict))
                }
                return null
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest,
                                             errorResponse: WebResourceResponse) {
                recordGoogleLoginAudit("HTTP ${errorResponse.statusCode} · " +
                    (if (request.isForMainFrame) "documento principal" else "subrecurso"),
                    request.url.toString(), v)
                val hasRetryAfter = errorResponse.responseHeaders?.keys?.any {
                    it.equals("Retry-After", ignoreCase = true)
                } == true
                if (isSeuImposto(v.url)) recordSiteDiagnostic(v,
                    "HTTP ${errorResponse.statusCode} · " +
                    (if (request.isForMainFrame) "documento principal" else "subrecurso") +
                    " · ${request.method} ${request.url.host ?: "?"}${safeDiagnosticPath(request.url)}" +
                    " · MIME ${errorResponse.mimeType ?: "?"}" +
                    " · Retry-After ${if (hasRetryAfter) "presente" else "ausente"}")
                // Fotografia somente de estado, sem capturar texto, corpo da requisição ou cookies.
                // Ajuda a distinguir falha do servidor de desabilitação do campo na interface.
                val chatPath = request.url.path
                if (isSeuImposto(v.url) && isSeuImposto(request.url.toString()) &&
                    (chatPath == "/api/chat/eu" || chatPath == "/api/chat/sala") &&
                    errorResponse.statusCode == 503) {
                    v.post {
                        if (!isSeuImposto(v.url)) return@post
                        v.evaluateJavascript("""(function(){
                            const fields=[...document.querySelectorAll('input,textarea,[contenteditable="true"]')];
                            const visible=fields.filter(el=>{const r=el.getBoundingClientRect();
                                return r.width>0&&r.height>0;});
                            return JSON.stringify({visible:visible.length,
                                disabled:visible.filter(el=>el.disabled).length,
                                active:document.activeElement&&document.activeElement.tagName||'',
                                formCount:document.querySelectorAll('form').length,
                                online:navigator.onLine,
                                viewport:window.visualViewport?Math.round(visualViewport.height):null});
                        })()""") { raw ->
                            val state = runCatching {
                                org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
                            }.getOrNull() ?: return@evaluateJavascript
                            recordSiteDiagnostic(v, "Chat ${chatPath?.substringAfterLast('/')} 503 · DOM visíveis=${state.optInt("visible")} " +
                                "desabilitados=${state.optInt("disabled")} " +
                                "formulários=${state.optInt("formCount")} " +
                                "online=${state.optString("online")} " +
                                "foco=${state.optString("active")} " +
                                "viewport=${state.optString("viewport")}")
                        }
                    }
                }
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                recordGoogleLoginAudit("Erro de rede ${error.errorCode} · " +
                    (if (request.isForMainFrame) "documento principal" else "subrecurso"),
                    request.url.toString(), v)
                if (isSeuImposto(v.url)) recordSiteDiagnostic(v,
                    "Rede ${error.errorCode} · " +
                    (if (request.isForMainFrame) "documento principal" else "subrecurso") +
                    " · ${request.method} ${request.url.host ?: "?"}${safeDiagnosticPath(request.url)}")
            }
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                val index = tabs.indexOfFirst { it.view === v }
                val old = tabs.getOrNull(index)
                val address = old?.let { attentionBlockedSources[v] ?: v.url ?: it.url } ?: HOME
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                pendingPopups.remove(v)
                attentionBlockedSources.remove(v)
                synchronized(mediaCandidateLock) {
                    mediaCandidates.remove(v)
                    mediaRequestOrigins.remove(v)
                    mediaNetworkSignals.remove(v)
                }
                v.destroy()
                if (old != null) {
                    val replacement = old.copy(view = createWebView(old.profile), url = address)
                    tabs[index] = replacement
                    restoreTabNavigation(replacement)
                    if (index == activeIndex) showActive() else persistTabs()
                }
                Toast.makeText(this@MainActivity,
                    "Uma página foi recuperada após falha do mecanismo de navegação.",
                    Toast.LENGTH_LONG).show()
                return true
            }
        }
        // Upload de arquivos e vídeos HTML5 em tela cheia.
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (isSeuImposto(view.url) &&
                    (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                     message.messageLevel() == ConsoleMessage.MessageLevel.WARNING)) {
                    val sourceHost = websiteHost(message.sourceId()) ?: "script incorporado"
                    recordSiteDiagnostic(view,
                        "Console ${message.messageLevel()} · " +
                        "${consoleErrorCategory(message.message())} · " +
                        "$sourceHost:${message.lineNumber()}")
                }
                return false
            }
            override fun onCreateWindow(
                source: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                // Não permitir pop-ups automáticos nem exceder o limite de abas.
                if (googleLoginAuditEnabled) {
                    val category = googleLoginHostCategory(source?.url) ?: "Outro site"
                    val elapsed = (SystemClock.elapsedRealtime() - googleLoginAuditStarted).coerceAtLeast(0)
                    if (googleLoginAuditEvents.size >= 90) googleLoginAuditEvents.removeFirst()
                    googleLoginAuditEvents.addLast("+${elapsed}ms · $category · Nova janela solicitada · " +
                        (if (isUserGesture) "com gesto" else "sem gesto"))
                    if (googleLoginHostCategory(source?.url) != null) googleLoginPopupCount++
                }
                if (!isUserGesture || resultMsg == null) {
                    recordGoogleLoginAudit("Popup recusado: sem gesto ou transporte", source?.url, source)
                    return false
                }
                if (totalTabCount >= MAX_TABS) {
                    recordGoogleLoginAudit("Popup recusado: limite de páginas", source?.url, source)
                    Toast.makeText(this@MainActivity, "Limite de $MAX_TABS páginas", Toast.LENGTH_LONG).show()
                    return false
                }
                if (tabs.size >= BrowserTabPolicy.MAX_RESIDENT_TABS) {
                    recordGoogleLoginAudit("Popup recusado: limite de WebViews residentes", source?.url, source)
                    // O transporte WebView deve ser imediato. Liberar vaga em segundo
                    // plano evita a seleção manual; o gesto precisa ser repetido.
                    handler.post {
                        if (!isFinishing && !isDestroyed)
                            withResidentSlot("Liberar WebView para abrir janela do site") {
                                Toast.makeText(this@MainActivity,
                                    "Vaga liberada. Toque novamente no link para abrir a janela.",
                                    Toast.LENGTH_LONG).show()
                            }
                    }
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                recordGoogleLoginAudit("Popup permitido", source?.url, source)
                // Uma nova WebView recebe a navegação diretamente do mecanismo Android.
                // Não reconstruímos URL, preservando POST e scripts de abertura.
                val profile = tabs.firstOrNull { it.view === source }?.profile ?: ""
                val popup = createWebView(profile)
                tabs.add(Tab(popup, title = "Nova aba", url = "about:blank", profile = profile))
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
                // O navegador nunca entra em PiP: a janela de vídeo tem Activity própria.
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
        setupExtensions(view)
        setupSeuImpostoViewportOverlay(view)
        setupSeuImpostoFocusBridge(view)
        return view
    }

    private fun setupSeuImpostoViewportOverlay(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        // Execução restrita à origem. Os filtros de resize só são ativados
        // explicitamente para um experimento; o modo normal não bloqueia eventos.
        WebViewCompat.addDocumentStartJavaScript(view, """(function(){
            try {
                const vk=navigator.virtualKeyboard;
                if(vk)vk.overlaysContent =
                    sessionStorage.getItem('__nmVkOverlayOff')!=='1';
            } catch(_) {}
            // Experimento opt-in: identifica qual rotina chama a remoção do FORM.
            // Registra apenas nomes de funções; nunca texto, valores ou URLs de scripts.
            const trace={enabled:false,guardEnabled:false,subtreeGuard:false,
                guardBlocked:0,events:[],lastWindowResize:0,lastVisualResize:0,
                lastFocus:0};
            window.__nmRemovalTrace=trace;
            try {
                trace.enabled=sessionStorage.getItem('__nmTraceFormRemoval')==='1';
                trace.guardEnabled=sessionStorage.getItem('__nmGuardFormRemoval')==='1';
                trace.subtreeGuard=sessionStorage.getItem('__nmGuardChatSubtree')==='1';
                if(trace.enabled||trace.guardEnabled||trace.subtreeGuard){
                    let focused=null;
                    let focusGuardUsed=0;
                    window.addEventListener('resize',()=>{
                        trace.lastWindowResize=Date.now();
                    },true);
                    if(window.visualViewport)
                        visualViewport.addEventListener('resize',()=>{
                            trace.lastVisualResize=Date.now();
                        },true);
                    document.addEventListener('focusin',e=>{
                        if(e.target&&e.target.matches&&
                            e.target.matches('input,textarea,[contenteditable="true"]')){
                            focused=e.target;
                            focusGuardUsed=0;
                            trace.lastFocus=Date.now();
                        }
                    },true);
                    const traceRemoval=(method,node)=>{
                        if(!focused||!node||
                            !(node===focused||(node.contains&&node.contains(focused))))return;
                        const frames=(new Error().stack||'').split('\n').slice(2,26)
                            .map(line=>{
                                const m=line.match(/^\s*at\s+([^\s(]+)/);
                                const fn=m?m[1].replace(/[^\w.$<>-]/g,'').slice(0,60):'anonymous';
                                // Apenas arquivo e posição. Descarta host, caminho, query e hash.
                                const where=line.match(/(?:https?:\/\/[^\s)]+|<anonymous>):(\d+):(\d+)/);
                                let source='indisponível';
                                if(where){
                                    const url=where[0].slice(0,-where[1].length-where[2].length-2);
                                    try {
                                        const file=new URL(url).pathname.split('/').pop()||'script';
                                        source=file.replace(/[^A-Za-z0-9._-]/g,'').slice(0,56);
                                    }catch(_){source='script';}
                                    source+=':'+where[1]+':'+where[2];
                                }
                                return {fn,source};
                            });
                        trace.events.push({t:Date.now()%600000,method,
                            removedTag:node.nodeName||'?',functions:frames.map(f=>f.fn),frames,
                            msSinceFocus:trace.lastFocus?Date.now()-trace.lastFocus:null,
                            msSinceWindowResize:trace.lastWindowResize?
                                Date.now()-trace.lastWindowResize:null,
                            msSinceVisualResize:trace.lastVisualResize?
                                Date.now()-trace.lastVisualResize:null,
                            activeTag:document.activeElement?.tagName||'',
                            viewport:window.visualViewport?
                                Math.round(visualViewport.height):null});
                        if(trace.events.length>16)trace.events.shift();
                    };
                    const removeChild=Node.prototype.removeChild;
                    Node.prototype.removeChild=function(node){
                        traceRemoval('removeChild',node);
                        // Bloqueio de formulário, ou de formulário e SECTION no modo
                        // ampliado. Limite de quatro para não travar a aplicação.
                        const viewport=window.visualViewport;
                        const allowed=node&&(node.nodeName==='FORM'||
                            (trace.subtreeGuard&&node.nodeName==='SECTION'));
                        if((trace.guardEnabled||trace.subtreeGuard)&&focusGuardUsed<
                            (trace.subtreeGuard?4:1)&&allowed&&focused&&
                            node.contains&&node.contains(focused)&&
                            document.activeElement===focused&&
                            viewport&&viewport.height<innerHeight-100){
                            focusGuardUsed++;
                            trace.guardBlocked++;
                            trace.events.push({t:Date.now()%600000,
                                method:'removeChild-BLOQUEADO',removedTag:node.nodeName,
                                functions:[]});
                            if(trace.events.length>16)trace.events.shift();
                            return node;
                        }
                        return removeChild.apply(this,arguments);
                    };
                    const replaceChild=Node.prototype.replaceChild;
                    Node.prototype.replaceChild=function(node,old){
                        traceRemoval('replaceChild',old);
                        return replaceChild.apply(this,arguments);
                    };
                    const remove=Element.prototype.remove;
                    Element.prototype.remove=function(){
                        traceRemoval('remove',this);
                        return remove.apply(this,arguments);
                    };
                    const replaceWith=Element.prototype.replaceWith;
                    Element.prototype.replaceWith=function(){
                        traceRemoval('replaceWith',this);
                        return replaceWith.apply(this,arguments);
                    };
                }
            } catch(_) {trace.enabled=false;}
            const probe={mode:'normal',blockedWindow:0,blockedVisual:0};
            window.__nmResizeProbe=probe;
            try {
                const mode=sessionStorage.getItem('__nmResizeProbeMode')||'normal';
                if(!['normal','window','visual','both'].includes(mode))return;
                probe.mode=mode;
                if(mode==='window'||mode==='both')
                    window.addEventListener('resize',event=>{
                        probe.blockedWindow++;event.stopImmediatePropagation();
                    },true);
                if((mode==='visual'||mode==='both')&&window.visualViewport)
                    visualViewport.addEventListener('resize',event=>{
                        probe.blockedVisual++;event.stopImmediatePropagation();
                    },true);
            } catch(_) {}
        })()""", setOf("https://seuimposto.com", "https://www.seuimposto.com"))
    }

    private fun setupSeuImpostoFocusBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, "__nmFocusBridge",
            setOf("https://seuimposto.com", "https://www.seuimposto.com"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(v: WebView, message: WebMessageCompat,
                                           sourceOrigin: Uri, isMainFrame: Boolean,
                                           replyProxy: JavaScriptReplyProxy) {
                    if (!isMainFrame || !isSeuImposto(v.url) ||
                        !isSeuImposto(sourceOrigin.toString()) ||
                        message.data != "focus-restored" || tabs.getOrNull(activeIndex)?.view !== v ||
                        !getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                            .getBoolean("focus_recovery", false) ||
                        SystemClock.elapsedRealtime() - lastSeuImpostoImeOpenAt > 1500L) return
                    v.postDelayed({
                        if (tabs.getOrNull(activeIndex)?.view !== v || !isSeuImposto(v.url))
                            return@postDelayed
                        v.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        val requested = imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        recordSiteDiagnostic(v, "IME reabertura solicitada · $requested")
                    }, 60L)
                }
            })
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
        updateKeyboardWindowMode(newView.url ?: tabs[activeIndex].url)
        if (newView.url == null) configureSiteDisplay(newView, tabs[activeIndex].url)
        if (webHost.childCount > 0) {
            val old = webHost.getChildAt(0) as WebView
            // Evita suspender áudio/vídeo HTML5 ao trocar de aba quando habilitado.
            if (!backgroundAudioEnabled) old.onPause()
            webHost.removeView(old)
        }
        (newView.parent as? android.view.ViewGroup)?.removeView(newView)
        webHost.addView(newView, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        newView.onResume()
        if (newView.url == null && newView !in pendingPopups) {
            applyLoginCookiePolicy(newView, tabs[activeIndex].url)
            recordGoogleLoginAppNavigation(newView, tabs[activeIndex].url, "reativação de aba")
            newView.loadUrl(tabs[activeIndex].url)
        }
        urlBar.setText(attentionBlockedSources[newView] ?: newView.url ?: tabs[activeIndex].url)
        tabs[activeIndex].lastUsed = SystemClock.elapsedRealtime()
        updateProfileIndicator()
        servedBlockUrl = null
        lastBlockMs = 0
        updateFavorite()
        updateStatus()
        updateInstagramOverlay()
        persistTabs()
    }

    private fun profileDisplayName(profile: String): String =
        if (profile == isolatedGoogleProfile) "Google 2 · isolado" else "Principal"

    private fun updateProfileIndicator() {
        val profile = tabs.getOrNull(activeIndex)?.profile ?: ""
        tabsButton.text = "$totalTabCount · ${if (profile == isolatedGoogleProfile) "P2" else "P1"}"
        tabsButton.contentDescription = "$totalTabCount abas · perfil ${profileDisplayName(profile)} · gerenciar abas"
        urlBar.hint = "${profileDisplayName(profile)} · URL ou busca"
    }

    private fun newTab(url: String = HOME, profile: String = "",
                       onCreated: (() -> Unit)? = null) {
        if (!BrowserTabPolicy.canCreate(totalTabCount)) {
            Toast.makeText(this, "Limite de $MAX_TABS abas", Toast.LENGTH_LONG).show()
            return
        }
        withResidentSlot("Liberar memória para nova aba") {
            openNewResidentTab(url, profile); onCreated?.invoke()
        }
    }

    private fun persistPinnedTabs() {
        getSharedPreferences("tabs", MODE_PRIVATE).edit()
            .putStringSet("pinned_tab_ids_v1", pinnedTabIds.toSet()).apply()
    }

    private fun toggleTabProtection(id: String) {
        if (!pinnedTabIds.add(id)) pinnedTabIds.remove(id)
        persistPinnedTabs()
        Toast.makeText(this, if (id in pinnedTabIds) "Aba protegida da hibernação automática"
            else "Aba disponível para hibernação automática", Toast.LENGTH_SHORT).show()
    }

    /** Sonda conservadora: resultado incerto não autoriza descartar uma WebView. */
    private fun checkAutomaticParkingSafety(view: WebView, done: (Boolean) -> Unit) {
        val script = """(function(){try{
            const media=Array.from(document.querySelectorAll('audio,video'))
                .some(m=>!m.paused&&!m.ended)||
                !!(navigator.mediaSession&&navigator.mediaSession.playbackState==='playing');
            const dirty=Array.from(document.querySelectorAll('input,textarea,select')).some(el=>{
                if(el.type==='file')return !!(el.files&&el.files.length);
                if(el.type==='checkbox'||el.type==='radio')return el.checked!==el.defaultChecked;
                if(el.tagName==='SELECT')return Array.from(el.options).some(o=>o.selected!==o.defaultSelected);
                return el.value!==el.defaultValue;
            });
            const editor=!!document.querySelector('[contenteditable="true"],[contenteditable=""]');
            const embedded=Array.from(document.querySelectorAll('iframe')).some(f=>
                /youtube|music|player|video|meet|zoom|teams/i.test(f.src||'')||
                /autoplay|microphone|camera/i.test(f.getAttribute('allow')||''));
            return JSON.stringify({safe:!(media||dirty||editor||embedded||window.onbeforeunload)});
        }catch(e){return JSON.stringify({safe:false});}})()"""
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; done(false) }
        }
        handler.postDelayed(timeout, 1800L)
        runCatching {
            view.evaluateJavascript(script) { result ->
                if (finished) return@evaluateJavascript
                finished = true
                handler.removeCallbacks(timeout)
                val safe = runCatching {
                    org.json.JSONObject(JSONArray("[$result]").getString(0))
                        .optBoolean("safe", false)
                }.getOrDefault(false)
                done(safe)
            }
        }.onFailure {
            if (!finished) { finished = true; handler.removeCallbacks(timeout); done(false) }
        }
    }

    /** LRU com exceções para áudio, formulários, abas protegidas e pop-ups em trânsito. */
    private fun withResidentSlot(reason: String, afterAvailable: () -> Unit) {
        if (tabs.size < BrowserTabPolicy.MAX_RESIDENT_TABS) { afterAvailable(); return }
        if (automaticParkingInProgress) {
            Toast.makeText(this, "Verificando abas inativas…", Toast.LENGTH_SHORT).show()
            return
        }
        automaticParkingInProgress = true
        val candidates = tabs.filter { tab ->
            tab.id != tabs.getOrNull(activeIndex)?.id && tab.id !in pinnedTabIds &&
                tab.view !in pendingPopups && tab.view !== queueSyncView &&
                tab.view !== media3OriginView
        }.sortedBy { it.lastUsed }.map { it.id }
        fun manualFallback() {
            automaticParkingInProgress = false
            if (!isDestroyed && !isFinishing) selectTabToPark(reason, afterAvailable)
        }
        fun tryCandidate(position: Int) {
            if (isDestroyed || isFinishing) { automaticParkingInProgress = false; return }
            if (position >= candidates.size) { manualFallback(); return }
            val id = candidates[position]
            val index = tabs.indexOfFirst { it.id == id }
            if (index < 0 || index == activeIndex || id in pinnedTabIds) {
                tryCandidate(position + 1); return
            }
            val view = tabs[index].view
            checkAutomaticParkingSafety(view) { safe ->
                val current = tabs.indexOfFirst { it.id == id }
                if (safe && current >= 0 && current != activeIndex &&
                    view === tabs[current].view && id !in pinnedTabIds &&
                    view !== queueSyncView && view !== media3OriginView) {
                    val previousCount = tabs.size
                    parkTab(current)
                    automaticParkingInProgress = false
                    if (tabs.size < previousCount) {
                        Toast.makeText(this, "Aba inativa hibernada automaticamente", Toast.LENGTH_SHORT).show()
                        afterAvailable()
                    } else manualFallback()
                } else tryCandidate(position + 1)
            }
        }
        tryCandidate(0)
    }

    private fun openNewResidentTab(url: String, profile: String = "") {
        Attention.pause()
        tabs.add(Tab(createWebView(profile), url = url, profile = profile))
        activeIndex = tabs.lastIndex
        showActive()
    }

    /** Hibernar descarta o DOM e preserva endereço, título e constelação. */
    private fun parkTab(index: Int) {
        if (index !in tabs.indices || index == activeIndex) return
        val tab = tabs.removeAt(index)
        saveTabNavigation(tab)
        val address = attentionBlockedSources[tab.view] ?: tab.view.url ?: tab.url
        if (address.startsWith("http://") || address.startsWith("https://")) {
            parkedTabs.add(ParkedTab(address, tab.view.title ?: tab.title,
                tab.constellation, tab.id, tab.profile))
        } else parkedTabs.add(ParkedTab(HOME, tab.title, tab.constellation, tab.id, tab.profile))
        attentionBlockedSources.remove(tab.view)
        pendingPopups.remove(tab.view)
        synchronized(mediaCandidateLock) { mediaCandidates.remove(tab.view) }
        tab.view.stopLoading()
        tab.view.onPause()
        (tab.view.parent as? android.view.ViewGroup)?.removeView(tab.view)
        tab.view.destroy()
        if (index < activeIndex) activeIndex--
        persistTabs()
        updateProfileIndicator()
    }

    private fun confirmParkTab(id: String, afterPark: () -> Unit = {}) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0 || index == activeIndex) return
        val tab = tabs[index]
        // A sonda HTML5 não detecta necessariamente mídia em iframes nem WebAudio.
        val script = "(function(){try{return Array.from(document.querySelectorAll('audio,video'))" +
            ".some(m=>!m.paused&&!m.ended)||" +
            "(navigator.mediaSession&&navigator.mediaSession.playbackState==='playing')}catch(e){return false}})()"
        val warn: (Boolean?) -> Unit = { playing ->
            val current = tabs.indexOfFirst { it.id == id }
            if (current >= 0 && current != activeIndex) {
                val status = when (playing) {
                    true -> "ATENÇÃO: foi detectada mídia em reprodução nesta aba.\n\n"
                    false -> "Nenhuma mídia HTML5 ativa foi detectada. Vídeos em frames e WebAudio podem não ser identificados.\n\n"
                    null -> "O estado da mídia não pôde ser verificado.\n\n"
                }
                AlertDialog.Builder(this).setTitle("Hibernar ${tabs[current].title.take(55)}?")
                    .setMessage(status + "A página será recarregada ao restaurar. Áudio, vídeo e dados de formulários não enviados podem ser perdidos.")
                    .setPositiveButton("Hibernar") { _, _ ->
                        val safeIndex = tabs.indexOfFirst { it.id == id }
                        if (safeIndex >= 0 && safeIndex != activeIndex) { parkTab(safeIndex); afterPark() }
                    }.setNegativeButton("Cancelar", null).show()
            }
        }
        if (tab.view.url == null) warn(null)
        else runCatching { tab.view.evaluateJavascript(script) { answer -> warn(answer == "true") } }
            .onFailure { warn(null) }
    }

    private fun selectTabToPark(title: String, afterPark: () -> Unit = {}) {
        val choices = tabs.indices.filter { it != activeIndex }.map { tabs[it].id }
        if (choices.isEmpty()) {
            Toast.makeText(this, "Não há aba inativa para hibernar", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("Escolha uma página. O navegador verificará a reprodução antes de confirmar a hibernação.")
            .setItems(choices.mapNotNull { id -> tabs.firstOrNull { it.id == id }?.let { "${it.constellation} · ${it.view.title ?: it.title}" } }.toTypedArray()) { _, which ->
                confirmParkTab(choices[which], afterPark)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun restoreParkedTab(index: Int) {
        val id = parkedTabs.getOrNull(index)?.id ?: return
        val restore = {
            val currentIndex = parkedTabs.indexOfFirst { it.id == id }
            if (currentIndex >= 0) {
                val stored = parkedTabs.removeAt(currentIndex)
                val tab = Tab(createWebView(stored.profile), stored.title, stored.url,
                    stored.constellation, id = stored.id, profile = stored.profile)
                tabs.add(tab)
                restoreTabNavigation(tab)
                activeIndex = tabs.lastIndex
                showActive()
            }
        }
        withResidentSlot("Liberar memória para restaurar aba") { restore() }
    }

    private fun moveTabToConstellation(id: String) {
        AlertDialog.Builder(this).setTitle("Mover para constelação")
            .setItems(constellations.toTypedArray()) { _, index ->
                tabs.firstOrNull { it.id == id }?.let { it.constellation = constellations[index] }
                parkedTabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { parkedIndex ->
                    parkedTabs[parkedIndex] = parkedTabs[parkedIndex].copy(constellation = constellations[index])
                }
                persistTabs()
                Toast.makeText(this, "Movida para ${constellations[index]}", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun closeTab(index: Int) {
        val tab = tabs.removeAt(index)
        pinnedTabIds.remove(tab.id)
        persistPinnedTabs()
        deleteTabNavigation(tab.id)
        attentionBlockedSources.remove(tab.view)
        if (webHost.childCount > 0 && webHost.getChildAt(0) === tab.view) webHost.removeView(tab.view)
        pendingPopups.remove(tab.view)
        synchronized(mediaCandidateLock) { mediaCandidates.remove(tab.view) }
        tab.view.stopLoading()
        tab.view.destroy()
        if (tabs.isEmpty()) { tabs.add(Tab(createWebView())) ; activeIndex = 0 }
        else activeIndex = BrowserTabPolicy.indexAfterClose(activeIndex, index, tabs.size)
        showActive()
    }

    private fun showTabs() {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(uiBackground())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "✦ Constelações"
            textSize = 24f
            setTextColor(uiForeground())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(uiAction("Fechar").apply {
            contentDescription = "Fechar gerenciador de constelações"
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "$totalTabCount de $MAX_TABS abas · ${tabs.size} em memória · ${parkedTabs.size} hibernadas\n" +
                "Até 6 em memória · as menos usadas são hibernadas automaticamente quando seguras"
            textSize = 13f
            setTextColor(uiMuted())
            setPadding(0, dp(4), 0, dp(12))
        })
        val search = EditText(this).apply {
            styleInput(this)
            hint = "Buscar título, endereço ou constelação"
            setSingleLine(true)
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = uiShape(uiInputSurface(), 12, true)
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(filters)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(uiAction("+ Nova constelação").apply {
            setOnClickListener { dialog.dismiss(); createConstellation { showTabs() } }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        bottom.addView(uiAction("Nova aba", true).apply {
            setOnClickListener { dialog.dismiss(); newTab() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(uiAction("Hibernar").apply {
            setOnClickListener { dialog.dismiss(); selectTabToPark("Escolher aba para hibernar") }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        bottom.addView(uiAction("Perfis").apply {
            setOnClickListener { dialog.dismiss(); showAccountProfilesMenu() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(bottom)

        var stateFilter = 0 // Todas, em memória, hibernadas
        val names = listOf("Todas", "Em memória", "Hibernadas")
        fun openTab(id: String) {
            dialog.dismiss()
            val liveIndex = tabs.indexOfFirst { it.id == id }
            if (liveIndex >= 0) { activeIndex = liveIndex; showActive() }
            else parkedTabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::restoreParkedTab)
        }
        fun render() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            var shown = 0
            for ((groupIndex, group) in constellations.withIndex()) {
                val icon = listOf("◈", "▤", "♫", "▣").getOrElse(groupIndex) { "✦" }
                val accent = listOf(0xFF3689BE.toInt(), 0xFF8B70C9.toInt(),
                    0xFFD38A31.toInt(), 0xFF32947C.toInt())
                    .getOrElse(groupIndex) { 0xFF3689BE.toInt() }
                val groupLive = tabs.filter { it.constellation == group }
                    .sortedByDescending { it.lastUsed }
                val groupParked = parkedTabs.filter { it.constellation == group }
                val candidates = (if (stateFilter != 2) groupLive.map { it.id } else emptyList()) +
                    (if (stateFilter != 1) groupParked.map { it.id } else emptyList())
                val matching = candidates.filter { id ->
                    val live = groupLive.firstOrNull { it.id == id }
                    val parked = groupParked.firstOrNull { it.id == id }
                    query.isBlank() || listOf(group, live?.view?.title, live?.title,
                        live?.view?.url, parked?.title, parked?.url)
                        .any { it?.contains(query, ignoreCase = true) == true }
                }
                if (matching.isEmpty() && (query.isNotBlank() || stateFilter != 0 ||
                    groupLive.isNotEmpty() || groupParked.isNotEmpty())) continue
                shown += matching.size
                list.addView(TextView(this).apply {
                    text = "$icon  $group  ·  ${matching.size}"
                    textSize = 18f
                    setTextColor(uiForeground())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(4), dp(14), 0, dp(8))
                })
                if (matching.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "Sem abas · mova uma página pelo menu de ações"
                        textSize = 12f
                        setTextColor(uiMuted())
                        setPadding(dp(12), dp(2), dp(8), dp(12))
                    })
                }
                for (id in matching) {
                    val resident = tabs.firstOrNull { it.id == id }
                    val parked = parkedTabs.firstOrNull { it.id == id }
                    val name = (resident?.view?.title ?: resident?.title ?: parked?.title ?: "Página")
                        .take(100)
                    val url = resident?.view?.url ?: resident?.url ?: parked?.url ?: ""
                    val active = resident?.id == tabs.getOrNull(activeIndex)?.id
                    val baseStatus = when {
                        active -> "Em primeiro plano"
                        resident != null -> "Em memória · reprodução não verificada"
                        else -> "Hibernada · recarrega ao abrir"
                    }
                    val status = if (id in pinnedTabIds) "Protegida · $baseStatus" else baseStatus
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(6), dp(10))
                        background = uiShape(if (active) {
                            if (isDarkTheme()) 0xFF25455E.toInt() else 0xFFE2F1FD.toInt()
                        } else uiSurface(), 14, true)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "$name, $status, toque para ${if (resident != null) "abrir" else "restaurar"}"
                        setOnClickListener { openTab(id) }
                    }
                    row.addView(TextView(this).apply {
                        text = if (active) "●" else if (resident != null) "◉" else "◌"
                        textSize = 22f
                        setTextColor(if (active) accent else uiMuted())
                        gravity = android.view.Gravity.CENTER
                    }, LinearLayout.LayoutParams(dp(32), dp(48)))
                    val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    detail.addView(TextView(this).apply {
                        text = name
                        textSize = 15f
                        setTextColor(uiForeground())
                        setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    detail.addView(TextView(this).apply {
                        text = (runCatching { Uri.parse(url).host }.getOrNull() ?: "Página") +
                            if ((resident?.profile ?: parked?.profile) == isolatedGoogleProfile) " · Perfil 2" else ""
                        textSize = 12f
                        setTextColor(uiMuted())
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    detail.addView(TextView(this).apply {
                        text = status
                        textSize = 12f
                        setTextColor(if (active) uiAccent() else uiMuted())
                    })
                    row.addView(detail, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    row.addView(uiAction("⋮").apply {
                        minWidth = dp(48)
                        contentDescription = "Ações da aba $name"
                        setOnClickListener {
                            dialog.dismiss()
                            val live = tabs.any { it.id == id }
                            val protection = if (id in pinnedTabIds) "Permitir hibernação automática"
                                else "Proteger da hibernação automática"
                            val actions = if (live) arrayOf("Mover de constelação", protection,
                                "Hibernar agora", "Fechar aba")
                                else arrayOf("Mover de constelação", protection, "Excluir hibernada")
                            AlertDialog.Builder(this@MainActivity).setTitle(name)
                                .setItems(actions) { _, selected ->
                                    when (selected) {
                                        0 -> moveTabToConstellation(id)
                                        1 -> toggleTabProtection(id)
                                        2 -> if (live) {
                                            if (tabs.getOrNull(activeIndex)?.id == id)
                                                Toast.makeText(this@MainActivity,
                                                    "Abra outra aba antes de hibernar a atual", Toast.LENGTH_LONG).show()
                                            else confirmParkTab(id)
                                        } else AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Excluir aba hibernada?")
                                            .setMessage(name)
                                            .setPositiveButton("Excluir") { _, _ ->
                                                parkedTabs.indexOfFirst { it.id == id }
                                                    .takeIf { it >= 0 }?.let { index ->
                                                        parkedTabs.removeAt(index)
                                                        pinnedTabIds.remove(id); persistPinnedTabs()
                                                        deleteTabNavigation(id)
                                                        persistTabs(); updateProfileIndicator()
                                                    }
                                            }.setNegativeButton("Cancelar", null).show()
                                        3 -> AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Fechar aba?")
                                            .setMessage(name)
                                            .setPositiveButton("Fechar") { _, _ ->
                                                tabs.indexOfFirst { it.id == id }
                                                    .takeIf { it >= 0 }?.let(::closeTab)
                                            }.setNegativeButton("Cancelar", null).show()
                                    }
                                }.setNegativeButton("Cancelar", null).show()
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
            if (shown == 0) list.addView(TextView(this).apply {
                text = "Nenhuma aba encontrada para este filtro."
                textSize = 15f
                setTextColor(uiMuted())
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        }
        // Atualização independente: evita recriar o campo de busca e perder o foco.
        fun refreshFilters() {
            filters.removeAllViews()
            names.forEachIndexed { index, name ->
                filters.addView(uiAction(name, index == stateFilter).apply {
                    setOnClickListener { stateFilter = index; refreshFilters(); render() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshFilters()
        render()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(720)), MATCH_PARENT)
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
        if (isYouTubeMusic(current.url)) {
            // Em Music, os candidatos da WebView podem ser segmentos de outra faixa.
            // Primeiro confirma a música ativa e apresenta as fontes do extrator.
            showYouTubeExtractor()
            return
        }
        scanDeclaredMedia(current) {
            scanYouTubeDeclaredManifests(current) {
            if (tabs.none { it.view === current }) return@scanYouTubeDeclaredManifests
            val detected = synchronized(mediaCandidateLock) {
                mediaCandidates[current]?.toList().orEmpty().reversed()
            }
            val sources = detected.filter { detectedMediaKind(it) != "Áudio" }
                .ifEmpty { detected }
                .distinct()
                .sortedBy(::detectedMediaRank)
            when (sources.size) {
                0 -> if (youtubeVideoId(current.url) != null) {
                    showYouTubeDetectedNotice()
                } else Toast.makeText(this,
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
    }

    private fun showMainMenu() {
        showActionHub("Navegador Maluco", "Navegação e recursos do navegador", listOf(
            MenuAction("Navegar", "Constelações", "Encontrar, proteger e organizar abas") { showTabs() },
            MenuAction("Navegar", "Biblioteca", "Favoritos e histórico pesquisáveis") { showLibrary() },
            MenuAction("Navegar", if (store.isFavorite(webView.url ?: "")) "Remover favorito" else "Favoritar página",
                "Atualizar o favorito da página atual") { toggleFavorite() },
            MenuAction("Reprodução", "Reprodução e mídia", "Player nativo, fila e controles") { showMediaMenu() },
            MenuAction("Conta", "Contas e perfis", "Alternar entre sessões Google") { showAccountProfilesMenu() },
            MenuAction("Configuração", "Ferramentas", "Extensões, aparência e Controle de Atenção") { showToolsMenu() },
            MenuAction("Avançado", "Diagnósticos e testes", "Ferramentas técnicas de investigação") { showDiagnosticsMenu() },
            MenuAction("Segurança", "Parar todas as mídias", "Interromper a reprodução em todas as abas",
                true) { forceStopAllMediaFromMenu() }
        ))
    }

    /** Alterna abas, nunca transporta cookies, senhas ou estado de autenticação. */
    private fun showAccountProfilesMenu() {
        val current = tabs.getOrNull(activeIndex)?.profile ?: ""
        AlertDialog.Builder(this).setTitle("Contas e perfis · ${profileDisplayName(current)}")
            .setItems(arrayOf("P1 · Principal", "P2 · Google isolado")) { _, choice ->
                showProfileActions(if (choice == 1) isolatedGoogleProfile else "")
            }.setNeutralButton("Como funciona") { _, _ ->
                AlertDialog.Builder(this).setTitle("Sessões Google independentes")
                    .setMessage("P1 conserva os logins originais. P2 usa cookies e armazenamento separados. " +
                        "Após autenticar em P2, abra YouTube ou Gmail em P2 por este menu. " +
                        "A conta ativa dentro de cada sessão é determinada pelo Google, não pelo navegador.")
                    .setPositiveButton("Entendi", null).show()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun showProfileActions(profile: String) {
        if (profile == isolatedGoogleProfile && !supportedGoogleProfile()) {
            AlertDialog.Builder(this).setTitle("Perfil isolado indisponível")
                .setMessage("Este motor WebView não suporta sessões independentes. A sessão principal foi preservada.")
                .setPositiveButton("Entendi", null).show()
            return
        }
        val count = tabs.count { it.profile == profile } + parkedTabs.count { it.profile == profile }
        showActionHub(profileDisplayName(profile), "$count abas neste perfil · sessões independentes", listOf(
            MenuAction("Abas", "Abrir aba existente", "Escolher página já aberta ou hibernada") { showProfileTabs(profile) },
            MenuAction("Abas", "Nova aba", "Abrir página inicial neste perfil") { newTab(profile = profile) },
            MenuAction("Abas", "Domínio da página atual", "Abrir apenas a origem, sem parâmetros") {
                val address = runCatching { Uri.parse(webView.url) }.getOrNull()
                val domain = address?.host
                val scheme = address?.scheme
                if (domain != null && (scheme == "https" || scheme == "http"))
                    newTab("$scheme://$domain/", profile = profile)
                else Toast.makeText(this, "Nenhum domínio HTTP válido nesta aba", Toast.LENGTH_LONG).show()
            },
            MenuAction("Google", "YouTube", "Abrir vídeo neste perfil") { newTab("https://www.youtube.com/", profile = profile) },
            MenuAction("Google", "Gmail", "Abrir caixa de entrada neste perfil") { newTab("https://mail.google.com/", profile = profile) },
            MenuAction("Google", "Minha Conta", "Gerenciar conta Google") { newTab("https://myaccount.google.com/", profile = profile) },
            MenuAction("Google", "Selecionar conta", "Abrir seletor de contas Google") { newTab("https://accounts.google.com/", profile = profile) }
        ))
    }

    private fun showProfileTabs(profile: String) {
        val liveIds = tabs.filter { it.profile == profile }.map { it.id }
        val parkedIds = parkedTabs.filter { it.profile == profile }.map { it.id }
        val ids = liveIds + parkedIds
        if (ids.isEmpty()) {
            AlertDialog.Builder(this).setTitle(profileDisplayName(profile))
                .setMessage("Ainda não há abas neste perfil.")
                .setPositiveButton("Abrir YouTube") { _, _ ->
                    newTab("https://www.youtube.com/", profile = profile)
                }.setNegativeButton("Fechar", null).show()
            return
        }
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(uiBackground())
        }
        root.addView(TextView(this).apply {
            text = "Abas · ${profileDisplayName(profile)}"
            textSize = 22f
            setTextColor(uiForeground())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        val search = EditText(this).apply {
            styleInput(this)
            hint = "Buscar abas deste perfil"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            background = uiShape(uiInputSurface(), 12, true)
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        fun render() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            var matches = 0
            ids.forEach { id ->
                val live = tabs.firstOrNull { it.id == id }
                val parked = parkedTabs.firstOrNull { it.id == id }
                val title = live?.view?.title ?: live?.title ?: parked?.title ?: return@forEach
                val url = live?.view?.url ?: live?.url ?: parked?.url.orEmpty()
                if (query.isNotBlank() && !title.contains(query, true) && !url.contains(query, true))
                    return@forEach
                matches++
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = uiShape(uiSurface(), 12, true)
                    isClickable = true; isFocusable = true
                    contentDescription = "${if (live != null) "Abrir" else "Restaurar"} $title"
                    setOnClickListener {
                        dialog.dismiss()
                        val liveIndex = tabs.indexOfFirst { it.id == id }
                        if (liveIndex >= 0) { activeIndex = liveIndex; showActive() }
                        else parkedTabs.indexOfFirst { it.id == id }
                            .takeIf { it >= 0 }?.let(::restoreParkedTab)
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 15f
                        setTextColor(uiForeground())
                        maxLines = 2
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = (if (live != null) "Em memória" else "Hibernada") + " · " + url
                        textSize = 12f
                        setTextColor(uiMuted())
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            if (matches == 0) list.addView(TextView(this).apply {
                text = "Nenhuma aba encontrada."
                setTextColor(uiMuted())
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(uiAction("Voltar").apply { setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        render()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(680)),
            minOf(resources.displayMetrics.heightPixels * 85 / 100, dp(740)))
    }

    private fun showLibrary() {
        showActionHub("Biblioteca", "Páginas salvas e visitadas", listOf(
            MenuAction("Suas páginas", "Favoritos", "Consultar e gerenciar páginas favoritas") {
                showSavedPages(true)
            },
            MenuAction("Suas páginas", "Histórico", "Buscar páginas visitadas anteriormente") {
                showSavedPages(false)
            }
        ))
    }

    private data class YoutubeTarget(val videoId: String, val page: String,
        val music: Boolean, val detectedBy: String) {
        val canonical: String get() = "https://www.youtube.com/watch?v=$videoId"
    }

    private fun isYouTubeMusic(url: String?): Boolean =
        runCatching { Uri.parse(url).host?.lowercase(java.util.Locale.ROOT) == "music.youtube.com" }
            .getOrDefault(false)

    /** O Music é uma SPA: a faixa ativa pode estar no player antes de aparecer na URL. */
    private fun resolveYoutubeTarget(view: WebView, done: (YoutubeTarget?) -> Unit) {
        val page = view.url ?: run { done(null); return }
        val urlId = youtubeVideoId(page)
        if (!isYouTubeMusic(page)) {
            done(urlId?.let { YoutubeTarget(it, page, false, "URL da página") })
            return
        }
        val js = """(function(){
            const valid = /^[A-Za-z0-9_-]{11}${'$'}/;
            function idFrom(href) {
                try { const u = new URL(href, location.href);
                    if (u.hostname !== 'music.youtube.com' && u.hostname !== 'www.youtube.com') return null;
                    const id = u.pathname === '/watch' ? u.searchParams.get('v') : null;
                    return id && valid.test(id) ? id : null;
                } catch (_) { return null; }
            }
            const bar = document.querySelector('ytmusic-player-bar');
            const musicPlayer = document.querySelector('ytmusic-player');
            try {
                const response = musicPlayer && typeof musicPlayer.getPlayerResponse === 'function'
                    ? musicPlayer.getPlayerResponse() : null;
                const id = response && response.videoDetails && response.videoDetails.videoId;
                if (id && valid.test(id))
                    return JSON.stringify({id,origin:'resposta do player Music'});
            } catch (_) {}
            for (const a of bar ? bar.querySelectorAll('a[href*="watch"]') : []) {
                const id = idFrom(a.href); if (id) return JSON.stringify({id,origin:'barra do player'});
            }
            for (const a of musicPlayer ? musicPlayer.querySelectorAll('a[href*="watch"]') : []) {
                const id = idFrom(a.href); if (id) return JSON.stringify({id,origin:'player do Music'});
            }
            const id = idFrom(location.href);
            return JSON.stringify({id:id || '',origin:id ? 'URL da página' : 'não identificado'});
        })()"""
        view.evaluateJavascript(js) { encoded ->
            if (isFinishing || isDestroyed || view !== webView || view.url != page) return@evaluateJavascript
            val result = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0))
            }.getOrNull()
            val id = result?.optString("id")?.takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{11}"))
            } ?: urlId
            done(id?.let { YoutubeTarget(it, page, true,
                result?.optString("origin")?.takeIf { it != "não identificado" }
                    ?: "URL da página") })
        }
    }

    /** Painel único: identificação, extração, HTTP e reprodução aparecem em sequência. */
    private fun showYouTubeTestPanel() {
        resolveYoutubeTarget(webView) { target ->
            if (target == null) {
                AlertDialog.Builder(this).setTitle("YouTube Music · faixa não identificada")
                    .setMessage("Abra uma música e inicie a reprodução. A URL e a barra do player " +
                        "ainda não apresentaram um identificador de faixa válido.")
                    .setNeutralButton("Examinar página") { _, _ ->
                        webView.url?.let { inspectYouTubePageSources(webView, it) }
                    }
                    .setPositiveButton("Fechar", null).show()
            } else showResolvedYouTubeTestPanel(target)
        }
    }

    private fun showResolvedYouTubeTestPanel(target: YoutubeTarget) {
        val videoId = target.videoId
        if (youtubeDiagnosticsVideoId != videoId) {
            youtubeDiagnosticsVideoId = videoId
            youtubeExtractionResult = "Ainda não executado"
            youtubeSourceHttpResult = "Ainda não executado"
            youtubeMedia3Result = "Ainda não executado"
            YoutubeExtractor.recordPoTokenStatus("Ainda não testado")
        }
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (10 * dp).toInt(), pad, pad)
        }
        fun description(title: String, status: String) {
            layout.addView(android.widget.TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            })
            layout.addView(android.widget.TextView(this).apply {
                text = status
                textSize = 14f
            })
        }
        description("1 · Identificação", "Serviço: ${if (target.music) "YouTube Music" else "YouTube"}\n" +
            "Faixa identificada pela: ${target.detectedBy}\n" +
            "Identificador: ${target.videoId}")
        description("2 · Extrator e PO Token", "Extração: $youtubeExtractionResult\n" +
            "PO Token: ${YoutubeExtractor.poTokenStatus}")
        description("3 · Fonte HTTP", youtubeSourceHttpResult)
        description("4 · Reprodução no Media3", youtubeMedia3Result)
        layout.addView(android.widget.TextView(this).apply {
            text = "HTTP 200 na API não garante reprodução. Uma URL encontrada na página " +
                "também pode receber 403 no servidor de mídia. O Media3 mantém seus controles nativos."
            textSize = 12f
            setPadding(0, (16 * dp).toInt(), 0, (10 * dp).toInt())
        })
        val buttons = mutableListOf<Pair<android.widget.Button, () -> Unit>>()
        fun action(title: String, command: () -> Unit) {
            val button = android.widget.Button(this).apply {
                text = title
                isAllCaps = false
            }
            layout.addView(button)
            buttons += button to command
        }
        action("Extrair faixa para o player nativo") { showYouTubeExtractor() }
        action("Examinar fontes declaradas na página") { inspectYouTubePageSources(webView, target.page) }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(layout)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (target.music) "YouTube Music · reprodução e testes"
                else "YouTube · reprodução e testes")
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .create()
        buttons.forEach { (button, command) ->
            button.setOnClickListener { dialog.dismiss(); command() }
        }
        dialog.show()
    }

    /** Extrai formatos no worker sem bloquear a WebView nem expor URLs assinadas no log. */
    private fun showYouTubeExtractor() {
        val current = webView
        resolveYoutubeTarget(current) { target ->
            if (target == null) {
                Toast.makeText(this, "Abra uma música ou vídeo antes de extrair.", Toast.LENGTH_LONG).show()
            } else extractResolvedYouTube(current, target)
        }
    }

    private fun extractResolvedYouTube(current: WebView, target: YoutubeTarget) {
        val page = target.page
        youtubeExtractionResult = "Executando…"
        Toast.makeText(this, "Extraindo ${if (target.music) "música" else "vídeo"}…",
            Toast.LENGTH_SHORT).show()
        Thread {
            // Preserva a rota original do NewPipe; no Music tenta também o ID canônico
            // apenas após falha, evitando forçar a interpretação de página de playlist.
            var route = "página original"
            var result = runCatching { YoutubeExtractor.extractWithReport(page) }
            if (target.music && (result.isFailure || result.getOrNull()?.sources.isNullOrEmpty()) &&
                page != target.canonical) {
                route = "vídeo canônico"
                result = runCatching { YoutubeExtractor.extractWithReport(target.canonical) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || current !== webView || current.url != page)
                    return@runOnUiThread
                result.onSuccess { report ->
                    val ordered = MediaSourceCatalog.ordered(report.sources)
                    // Music prioriza faixas de áudio; a ordem do YouTube normal não muda.
                    val sources = if (target.music) ordered.sortedBy {
                        if (it.label.startsWith("Áudio")) 0
                        else if (it.label.startsWith("Vídeo + áudio")) 1 else 2
                    } else ordered
                    youtubeExtractionResult = "$route · ${sources.size} formato(s) · ${report.requests} " +
                        "requisição(ões) · HTTP ${report.httpErrors.ifEmpty { mapOf() }}"
                    android.util.Log.i("NavegadorMedia3", "ExtratorYouTube: requests=${report.requests}; " +
                        "httpErrors=${report.httpErrors}; formatos=${sources.size}; " +
                        "tempoMs=${report.elapsedMs}")
                    if (sources.isEmpty()) {
                        AlertDialog.Builder(this).setTitle("Extrator · ${if (target.music) "YouTube Music" else "YouTube"}")
                            .setMessage("PO Token: ${YoutubeExtractor.poTokenStatus}\n\n" +
                                "Nenhuma fonte disponível. Requisições: ${report.requests}; " +
                                "HTTP: ${if (report.httpErrors.isEmpty()) "sem erros registrados" else report.httpErrors}. " +
                                "O YouTube pode disponibilizar apenas formatos protegidos ou " +
                                "incompatíveis com a extração atual.")
                            .setPositiveButton("Examinar página") { _, _ ->
                                inspectYouTubePageSources(current, page)
                            }
                            .setNegativeButton("Fechar", null).show()
                        return@onSuccess
                    }
                    val labels = sources.map { it.label +
                        (if (it.playable) "" else " · exige áudio separado") }.toTypedArray()
                    // Não combinar setMessage com setItems: o AlertDialog exibe a
                    // mensagem no lugar da lista e impede selecionar uma fonte.
                    AlertDialog.Builder(this)
                        .setTitle("${if (target.music) "YouTube Music" else "YouTube"} · ${sources.size} formato(s)")
                        .setItems(labels) { _, i ->
                            val selected = sources[i]
                            if (selected.playable) verifyExtractedYouTubeSource(current, page,
                                selected, target.videoId)
                            else AlertDialog.Builder(this).setTitle("Vídeo sem áudio")
                                .setMessage("Este formato tem áudio separado. A reprodução conjunta " +
                                    "ainda precisa ser integrada ao Media3.")
                                .setPositiveButton("OK", null).show()
                        }.setNegativeButton("Cancelar", null).show()
                }.onFailure { err ->
                    val failure = err as? YoutubeExtractor.ExtractionFailure
                    youtubeExtractionResult = "Falha · ${err.cause?.javaClass?.simpleName ?: err.javaClass.simpleName}" +
                        " · ${failure?.requests ?: 0} requisição(ões)"
                    android.util.Log.w("NavegadorMedia3",
                        "Falha no extrator: ${err.javaClass.simpleName}; " +
                            "causa=${err.cause?.javaClass?.simpleName ?: "desconhecida"}; " +
                            "requests=${failure?.requests ?: 0}; http=${failure?.httpErrors.orEmpty()}")
                    AlertDialog.Builder(this).setTitle("Extrator · ${if (target.music) "YouTube Music" else "YouTube"}")
                        .setMessage("PO Token: ${YoutubeExtractor.poTokenStatus}\n\n" + (when {
                            err.cause?.javaClass?.simpleName == "SignInConfirmNotBotException" ->
                                "O YouTube exigiu confirmação antirrobô do extrator anônimo. " +
                                    "Essa recusa não significa que a página aberta esteja bloqueada."
                            err.cause is java.net.SocketTimeoutException ->
                                "A conexão demorou demais. Confira a internet e tente novamente."
                            err.cause is java.net.UnknownHostException ->
                                "Não foi possível acessar o servidor. Verifique a conexão."
                            failure?.httpErrors?.containsKey(429) == true ->
                                "O YouTube limitou as requisições (HTTP 429)."
                            failure?.httpErrors?.containsKey(403) == true ->
                                "O YouTube recusou uma requisição do extrator (HTTP 403)."
                            else ->
                                "Não foi possível obter os formatos. O YouTube pode ter alterado " +
                                    "o protocolo ou restringido este vídeo."
                        }) + "\nDiagnóstico: ${failure?.requests ?: 0} requisição(ões), " +
                            "HTTP ${failure?.httpErrors.orEmpty()}, " +
                            "erro ${err.cause?.javaClass?.simpleName ?: err.javaClass.simpleName}.")
                        .setPositiveButton("Examinar página") { _, _ ->
                            inspectYouTubePageSources(current, page)
                        }
                        .setNegativeButton("Fechar", null).show()
                }
            }
        }.start()
    }

    /**
     * Segunda via de diagnóstico: inspeciona somente dados de mídia já declarados
     * pela página visível. Não lê cookies nem busca tokens de autenticação.
     */
    private fun inspectYouTubePageSources(view: WebView, page: String) {
        val script = """(function(){
            const music = location.hostname === 'music.youtube.com';
            let response = null;
            let origin = 'ausente';
            if (music) {
                try {
                    const p = document.querySelector('ytmusic-player');
                    if (p && typeof p.getPlayerResponse === 'function') {
                        response = p.getPlayerResponse(); origin = 'ytmusic-player';
                    }
                } catch (_) {}
            }
            if (!response && window.ytInitialPlayerResponse) {
                response = window.ytInitialPlayerResponse;
                origin = 'ytInitialPlayerResponse';
            }
            if (!response) {
                try {
                    const p = document.querySelector('ytd-player');
                    if (p && typeof p.getPlayerResponse === 'function') {
                        response = p.getPlayerResponse(); origin = 'ytd-player';
                    }
                } catch (e) {}
            }
            const sd = response && response.streamingData || {};
            const formats = [...(sd.formats || []), ...(sd.adaptiveFormats || [])];
            let cipher = 0, direct = 0;
            const sources = [];
            function add(kind, value, playable) {
                try {
                    if (typeof value !== 'string' || sources.length >= 30) return;
                    const u = new URL(value, location.href);
                    const host = u.hostname.toLowerCase();
                    if (u.protocol !== 'https:' || !(host === 'youtube.com' ||
                        host.endsWith('.youtube.com') || host === 'googlevideo.com' ||
                        host.endsWith('.googlevideo.com'))) return;
                    sources.push({kind:kind,url:u.href,playable:playable});
                } catch(e) {}
            }
            add('HLS · página', sd.hlsManifestUrl, true);
            add('DASH · página', sd.dashManifestUrl, true);
            for (const f of formats) {
                if (f.signatureCipher || f.cipher) cipher++;
                if (typeof f.url !== 'string') continue;
                direct++;
                const mime = String(f.mimeType || '');
                const audio = mime.startsWith('audio/') || !!f.audioQuality ||
                    [18,22].includes(Number(f.itag));
                const video = mime.startsWith('video/');
                const kind = audio && video ? 'Vídeo + áudio' :
                    audio ? 'Áudio' : 'Vídeo sem áudio';
                add(kind+' · '+String(f.qualityLabel || f.itag || 'página'), f.url, audio);
            }
            const player = document.querySelector(music ? 'ytmusic-player' : 'ytd-player');
            const media = player && player.querySelector('video, audio') ||
                document.querySelector('video, audio');
            const dom = media ? {ready:media.readyState,network:media.networkState,
                playing:!media.paused,source:media.currentSrc && media.currentSrc.startsWith('blob:')
                    ? 'blob' : media.currentSrc && media.currentSrc.startsWith('https:')
                    ? 'https' : 'indisponível'} : null;
            return JSON.stringify({origin:origin,music:music,playerPresent:!!player,dom:dom,
                playability:String(response &&
                response.playabilityStatus && response.playabilityStatus.status || 'desconhecido'),
                total:formats.length,cipher:cipher,direct:direct,sources:sources});
        })()"""
        view.evaluateJavascript(script) { value ->
            if (isFinishing || isDestroyed || webView !== view || view.url != page)
                return@evaluateJavascript
            val report = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$value]").getString(0))
            }.getOrNull()
            if (report == null) {
                AlertDialog.Builder(this).setTitle("Diagnóstico da página")
                    .setMessage("Não foi possível consultar os dados de reprodução da página.")
                    .setPositiveButton("Fechar", null).show()
                return@evaluateJavascript
            }
            val array = report.optJSONArray("sources") ?: org.json.JSONArray()
            val usable = mutableListOf<YoutubeExtractor.Source>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.startsWith("https://")) usable += YoutubeExtractor.Source(
                    item.optString("kind").take(80), url, item.optBoolean("playable"))
            }
            val html5 = report.optJSONObject("dom")
            val summary = "Serviço: ${if (report.optBoolean("music")) "YouTube Music" else "YouTube"}\n" +
                "Player da página: ${if (report.optBoolean("playerPresent")) "detectado" else "não detectado"}\n" +
                "Origem dos dados: ${report.optString("origin")}\n" +
                "Estado: ${report.optString("playability")}\n" +
                "Formatos declarados: ${report.optInt("total")}\n" +
                "URLs diretas: ${report.optInt("direct")}\n" +
                "Formatos com assinatura: ${report.optInt("cipher")}\n" +
                "Fontes utilizáveis identificadas: ${usable.size}\n" +
                "HTML5: ${if (html5 == null) "ausente" else "${html5.optString("source")} · " +
                    "ready=${html5.optInt("ready")} · network=${html5.optInt("network")}"}"
            android.util.Log.i("NavegadorMedia3", "YouTubePagina: " + summary.replace('\n', ';'))
            AlertDialog.Builder(this).setTitle(if (report.optBoolean("music"))
                "YouTube Music · diagnóstico da página" else "YouTube · diagnóstico da página")
                .setMessage(summary)
                .setPositiveButton("Ver fontes (${usable.size})") { _, _ ->
                    if (usable.isEmpty()) {
                        Toast.makeText(this, "Nenhuma URL direta declarada pela página.",
                            Toast.LENGTH_LONG).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("Formatos da página")
                            .setItems(usable.map { it.label +
                                (if (it.playable) "" else " · exige áudio separado") }.toTypedArray()) { _, index ->
                                val chosen = usable[index]
                                if (chosen.playable) verifyExtractedYouTubeSource(view, page, chosen)
                                else Toast.makeText(this, "Áudio separado necessário.",
                                    Toast.LENGTH_LONG).show()
                            }.setNegativeButton("Fechar", null).show()
                    }
                }.setNegativeButton("Fechar", null).show()
        }
    }

    /** Comprova a autorização HTTP antes de enviar uma URL temporária ao Media3. */
    private fun verifyExtractedYouTubeSource(
        originalView: WebView, page: String, source: YoutubeExtractor.Source,
        videoId: String? = youtubeVideoId(page)
    ) {
        Toast.makeText(this, "Verificando acesso ao formato...", Toast.LENGTH_SHORT).show()
        val agent = android.webkit.WebSettings.getDefaultUserAgent(this)
        val pageUri = Uri.parse(page)
        val origin = "https://${pageUri.host}/"
        Thread {
            val check = YoutubeExtractor.checkAccess(source.url, agent, origin)
            runOnUiThread {
                if (isFinishing || isDestroyed || webView !== originalView || originalView.url != page)
                    return@runOnUiThread
                android.util.Log.i("NavegadorMedia3", "FonteYouTube: http=${check.status}; " +
                    "falha=${check.failure ?: "nenhuma"}; tipo=${source.label.substringBefore('·').trim()}")
                youtubeSourceHttpResult = "HTTP ${check.status ?: "indisponível"}" +
                    (check.failure?.let { " · $it" } ?: "")
                if (check.status == 403) {
                    val signals = YoutubeExtractor.inspectSourceSignals(source.url)
                    AlertDialog.Builder(this).setTitle("Diagnóstico YouTube · HTTP 403")
                        .setMessage("A verificação preliminar recebeu HTTP 403. " +
                            "Isso não comprova que o Media3 receberá a mesma resposta. " +
                            "O teste experimental fará uma única tentativa de reprodução " +
                            "sem compartilhar cookies ou alterar a sessão do navegador.\n\n" +
                            signals.description() +
                            "\nA ausência de PO na URL não prova ausência de token na requisição.")
                        .setPositiveButton("Testar no Media3") { _, _ ->
                            val mime = when {
                                source.label.startsWith("HLS") -> "application/x-mpegURL"
                                source.label.startsWith("DASH") -> "application/dash+xml"
                                else -> null
                            }
                            startMedia3(source.url, mime, videoId)
                        }
                        .setNegativeButton("Cancelar", null).show()
                } else if (check.status == 401 || check.status == 410 || check.status == 429) {
                    AlertDialog.Builder(this).setTitle("Fonte do YouTube indisponível")
                        .setMessage("A verificação preliminar recebeu HTTP ${check.status}. " +
                            "O aplicativo não fará novas tentativas automáticas.")
                        .setPositiveButton("OK", null).show()
                } else {
                    // Respostas inconclusivas continuam pelo diagnóstico do Media3.
                    val mime = when {
                        source.label.startsWith("HLS") -> "application/x-mpegURL"
                        source.label.startsWith("DASH") -> "application/dash+xml"
                        else -> null
                    }
                    startMedia3(source.url, mime, videoId)
                }
            }
        }.start()
    }

    private fun showPlayerHistory() {
        val entries = PlayerHistory(this).list()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nenhum vídeo no histórico ainda", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { e ->
            val seconds = e.positionMs / 1000L
            "${e.title} · ${seconds / 60}m ${seconds % 60}s"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Histórico do player")
            .setItems(labels) { _, selected ->
                val entry = entries[selected]
                Attention.checkNavigation(entry.page)?.let { verdict ->
                    Toast.makeText(this, "Controle de Atenção: " + verdict.reason,
                        Toast.LENGTH_LONG).show()
                    return@setItems
                }
                // URLs de mídia podem expirar. Reabre a página original para extrair
                // uma fonte nova; a posição é restaurada ao escolher a reprodução.
                newTab(entry.page)
                Toast.makeText(this, "Escolha a mídia da página para retomar de onde parou",
                    Toast.LENGTH_LONG).show()
            }.setNegativeButton("Fechar", null).show()
    }

    private var queueSyncView: WebView? = null
    private var queueSyncPage: String? = null
    private val queueSyncTicker = object : Runnable {
        override fun run() {
            val view = queueSyncView ?: return
            val page = queueSyncPage ?: return
            if (isDestroyed || isFinishing || view.url != page) {
                queueSyncView = null
                queueSyncPage = null
                return
            }
            if (browserIsResumed) capturePageQueue(view, page) { ids, music ->
                if (ids.isNotEmpty() && queueSyncView === view && view.url == page)
                    Media3PlaybackService.synchronizeQueue(ids, music, youtubeVideoId(page))
            }
            handler.postDelayed(this, 15000L)
        }
    }

    /** Apenas a sequência exposta na página, não os dados privados de recomendações. */
    private fun capturePageQueue(
        view: WebView, page: String, callback: (List<String>, Boolean) -> Unit
    ) {
        if (view.url != page) return
        val script = """(function(){
            const valid=/^[A-Za-z0-9_-]{11}$/;
            const music=location.hostname==='music.youtube.com';
            const roots=music ?
                ['ytmusic-player-queue','ytmusic-player-page #automix','ytmusic-section-list-renderer'] :
                ['ytd-playlist-panel-renderer','ytd-playlist-video-list-renderer'];
            const found=[], seen=new Set();
            for(const selector of roots){
                for(const root of document.querySelectorAll(selector)){
                    for(const a of root.querySelectorAll('a[href*="watch"]')){
                        try {
                            const u=new URL(a.href, location.href);
                            const id=u.searchParams.get('v');
                            if(id && valid.test(id) && !seen.has(id)){
                                seen.add(id); found.push(id);
                                if(found.length>=100) break;
                            }
                        }catch(e){}
                    }
                    if(found.length>=100) break;
                }
                if(found.length) break;
            }
            return JSON.stringify({ids:found,music:music});
        })()"""
        view.evaluateJavascript(script) { encoded ->
            if (isDestroyed || isFinishing || view.url != page) return@evaluateJavascript
            val data = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0))
            }.getOrNull()
            val array = data?.optJSONArray("ids")
            val ids = (0 until (array?.length() ?: 0)).mapNotNull { i ->
                array?.optString(i)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            }.distinct()
            callback(ids, data?.optBoolean("music") == true)
        }
    }

    /** Importa a sequência visível e acompanha suas alterações enquanto a aba estiver aberta. */
    private fun importNativeMediaQueue() {
        val view = webView
        val page = view.url.orEmpty()
        if (!isYouTubeMusic(page) && youtubeVideoId(page) == null) {
            Toast.makeText(this, "Abra uma fila do YouTube ou YouTube Music.", Toast.LENGTH_LONG).show()
            return
        }
        capturePageQueue(view, page) { ids, music ->
            if (view !== webView) return@capturePageQueue
            if (ids.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Fila de reprodução")
                    .setMessage("Nenhuma sequência acessível foi encontrada na página. Abra a fila ou playlist do site e mantenha a lista visível.")
                    .setPositiveButton("OK", null).show()
                return@capturePageQueue
            }
            AlertDialog.Builder(this).setTitle("Importar fila · ${ids.size} faixa(s)")
                .setMessage("A fila visível será importada e atualizada a cada 15 segundos enquanto esta página estiver aberta no navegador. O Media3 continuará responsável pela reprodução.")
                .setPositiveButton("Adicionar") { _, _ ->
                    val currentId = youtubeVideoId(page)
                    val position = ids.indexOf(currentId)
                    val next = if (position >= 0) ids.drop(position + 1) else ids
                    val added = Media3PlaybackService.appendQueue(next, music)
                    if (added > 0 || Media3PlaybackService.queueStatus().contains("pendente(s)")) {
                        queueSyncView = view
                        queueSyncPage = page
                        handler.removeCallbacks(queueSyncTicker)
                        handler.postDelayed(queueSyncTicker, 15000L)
                    }
                    Toast.makeText(this, "Fila: $added faixa(s) adicionada(s). " +
                        Media3PlaybackService.queueStatus(), Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun showMediaMenu() {
        showActionHub("Reprodução e mídia", "O player nativo tem prioridade sobre as fontes da página", listOf(
            MenuAction("Reproduzir", "Mídia desta página", "Procurar fonte de áudio ou vídeo") { playPageMediaDirectly() },
            MenuAction("Reproduzir", "YouTube e YouTube Music", "Extrair e tocar no Media3") { showYouTubeExtractor() },
            MenuAction("Reproduzir", "Controles do player", "Abrir o player nativo") { showNativeVideoPlayer() },
            MenuAction("Reproduzir", "Janela flutuante", "Reprodução em Picture-in-Picture") { enterPip() },
            MenuAction("Fila", "Importar sequência", "Importar músicas e vídeos visíveis") { importNativeMediaQueue() },
            MenuAction("Fila", "Visualizar ou limpar fila", "${Media3PlaybackService.queueStatus()}") {
                showNativeQueue()
            },
            MenuAction("Histórico", "Histórico do player", "Retomar a partir da página original") { showPlayerHistory() },
            MenuAction("Configuração", "Opções de reprodução", "Segundo plano e recuperação") { showMediaOptions() },
            MenuAction("Avançado", "Diagnósticos de mídia", "Inspecionar elementos e formatos") { showMediaDiagnosticsMenu() },
            MenuAction("Segurança", "Parar todas as mídias", "Interromper sessões e limpar fila",
                true) { forceStopAllMediaFromMenu() }
        ))
    }

    private fun showNativeQueue() {
        AlertDialog.Builder(this).setTitle("Fila de reprodução")
            .setMessage(Media3PlaybackService.queueStatus() +
                "\nA próxima fonte é obtida quando a mídia atual termina.")
            .setPositiveButton("Limpar fila") { _, _ ->
                queueSyncView = null
                queueSyncPage = null
                handler.removeCallbacks(queueSyncTicker)
                Media3PlaybackService.clearPendingQueue()
            }.setNegativeButton("Fechar", null).show()
    }

    private fun showMediaDiagnosticsMenu() {
        showActionHub("Diagnósticos de mídia", "Investigação técnica sem alterar a reprodução atual", listOf(
            MenuAction("YouTube", "Diagnóstico de extração", "IDs, PO Token, HTTP e Media3") { showYouTubeTestPanel() },
            MenuAction("Fontes", "Vídeos detectados", "Listar fontes de mídia acessíveis") { showRequestMediaCandidates() },
            MenuAction("Fontes", "Manifestos HLS / DASH", "Inspecionar fluxos disponíveis") { showStreamingManifests() },
            MenuAction("HTML5", "Mídia da página", "Verificar elementos de áudio e vídeo") { tryNativeMediaFromPage() }
        ))
    }

    private fun showMediaOptions() {
        val audioLabel = "Áudio da WebView em segundo plano: " +
            (if (backgroundAudioEnabled) "ligado" else "desligado")
        showActionHub("Opções de mídia", "Preferências e ações da reprodução", listOf(
            MenuAction("Segundo plano", audioLabel, "Toque para alternar a permissão") { toggleBackgroundAudio() },
            MenuAction("Fonte", "Abrir endereço de mídia", "Tocar uma URL direta") { promptNativeMediaUrl() },
            MenuAction("Fonte", "Tentar mídia da página", "Detectar arquivo HTML5") { tryNativeMediaFromPage() },
            MenuAction("Transferência", "Retomar na página", "Devolver reprodução à WebView") { returnMedia3ToPage() },
            MenuAction("Segurança", "Parar todas as mídias", "Encerrar a reprodução em todos os players",
                true) { forceStopAllMediaFromMenu() }
        ))
    }

    private fun showToolsMenu() {
        showActionHub("Ferramentas", "Controle da navegação e das permissões", listOf(
            MenuAction("Reprodução", "YouTube e YouTube Music", "Abrir no player nativo") { showYouTubeExtractor() },
            MenuAction("Reprodução", "Opções de mídia", "Configurar reprodução em segundo plano") { showMediaOptions() },
            MenuAction("Desempenho", "Modo Turbo Maluco", "Otimizar e diagnosticar o site atual") { showTurboMenu() },
            MenuAction("Rede e privacidade", "Cloudflare · DNS e WARP",
                "DNS 1.1.1.1, filtros de segurança e teste da conexão") { showCloudflareMenu() },
            MenuAction("Privacidade", "Controle de Atenção", "Limites de uso por site") {
                startActivity(Intent(this, AttentionActivity::class.java).apply {
                    putExtra("attention_current_page", webView.url ?: tabs[activeIndex].url)
                })
            },
            MenuAction("Navegação", "Extensões", "Gerenciar extensões instaladas") {
                extLauncher.launch(Intent(this, ExtensionsActivity::class.java).apply {
                    putExtra("attention_current_page", webView.url ?: tabs[activeIndex].url)
                })
            },
            MenuAction("Aparência", "Tema claro ou escuro", "Alterar as cores da interface") { showThemePicker() },
            MenuAction("Aparência", "Modo de exibição do site", "Escolher motor ou identificação") { showSiteDisplayModes() },
            MenuAction("Compatibilidade", "Seu Imposto no GeckoView", "Abrir chat no motor alternativo") { openSeuImpostoInGecko() },
            MenuAction("Avançado", "Diagnósticos", "Experimentos e relatórios") { showDiagnosticsMenu() }
        ))
    }

    private fun showTurboMenu() {
        val page = webView.url ?: tabs.getOrNull(activeIndex)?.url
        val host = websiteHost(page)
        if (host == null) {
            Toast.makeText(this, "Abra uma página HTTP(S) para configurar o Modo Turbo", Toast.LENGTH_LONG).show()
            return
        }
        val selected = turboMode(page)
        val effective = effectiveTurboMode(page)
        val labels = mapOf("off" to "Desligado", "compatible" to "Compatível",
            "balanced" to "Equilibrado", "aggressive" to "Agressivo")
        val protection = if (turboProtected(page))
            " · protegido: ajustes fortes limitados" else ""
        showActionHub("Turbo Maluco · $host",
            "Atual: ${labels[selected]} · aplicado: ${labels[effective]}$protection", listOf(
                MenuAction("Perfis", "Desligado", "Navegação original, sem modificações") {
                    setTurboMode(host, "off")
                },
                MenuAction("Perfis", "Compatível", "Reduz animações e rolagem animada") {
                    setTurboMode(host, "compatible")
                },
                MenuAction("Perfis", "Equilibrado", "Prioriza renderização de blocos estáticos fora da tela") {
                    setTurboMode(host, "balanced")
                },
                MenuAction("Perfis", "Agressivo · sem imagens", "Economiza rede, mas páginas podem ficar incompletas") {
                    AlertDialog.Builder(this).setTitle("Turbo agressivo · $host")
                        .setMessage("Bloqueia downloads de imagens de rede neste site e reduz a renderização " +
                            "de partes estáticas. Pode quebrar botões, capas e elementos gráficos. " +
                            "YouTube, Instagram, Google e Seu Imposto recebem somente o perfil compatível.")
                        .setPositiveButton("Ativar e recarregar") { _, _ -> setTurboMode(host, "aggressive") }
                        .setNegativeButton("Cancelar", null).show()
                },
                MenuAction("Diagnóstico", "Medir página atual", "Tempo, recursos e peso aproximado do JavaScript") {
                    showTurboDiagnostics()
                },
                MenuAction("Informações", "Segurança e limitações", "Entender alterações por site") {
                    AlertDialog.Builder(this).setTitle("Modo Turbo Maluco")
                        .setMessage("As preferências ficam salvas por domínio. O diagnóstico usa dados " +
                            "locais do navegador, sem enviar formulários, cookies ou endereços para terceiros. " +
                            "O perfil Equilibrado só age em trechos longos e sem campos ou mídia, após o carregamento. " +
                            "Isso não acelera servidores lentos nem substitui correções no site.")
                        .setPositiveButton("Entendi", null).show()
                }
            ))
    }

    private fun setTurboMode(host: String, mode: String) {
        getSharedPreferences("site_turbo", MODE_PRIVATE).edit().putString(host, mode).apply()
        val page = webView.url
        if (websiteHost(page) == host) {
            applyTurboNetworkPolicy(webView, page)
            // Restaura imagens e remove CSS injetado ao recriar o documento.
            webView.reload()
        }
        val label = mapOf("off" to "desligado", "compatible" to "compatível",
            "balanced" to "equilibrado", "aggressive" to "agressivo")[mode] ?: mode
        Toast.makeText(this, "Turbo $label configurado para $host", Toast.LENGTH_SHORT).show()
    }

    private fun showTurboDiagnostics() {
        val view = webView
        val page = view.url ?: return
        val script = """(function(){
            try {
              const nav=performance.getEntriesByType('navigation')[0];
              const resources=performance.getEntriesByType('resource');
              const memory=performance.memory;
              return JSON.stringify({
                pageMs:Math.round(nav&&nav.duration||performance.now()),
                responseMs:nav?Math.round(nav.responseEnd-nav.requestStart):-1,
                domMs:nav?Math.round(nav.domContentLoadedEventEnd-nav.startTime):-1,
                resources:resources.length,
                images:document.images.length,
                scripts:document.scripts.length,
                heapMb:memory?Math.round(memory.usedJSHeapSize/1048576):-1
              });
            } catch (_) {return '{}';}
        })()"""
        view.evaluateJavascript(script) { encoded ->
            if (view !== webView || view.url != page || isFinishing || isDestroyed) return@evaluateJavascript
            val data = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$encoded]").getString(0))
            }.getOrNull()
            if (data == null || !data.has("pageMs")) {
                Toast.makeText(this, "Diagnóstico indisponível nesta página", Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            val heap = data.optInt("heapMb", -1).let { if (it < 0) "não informado" else "$it MB" }
            AlertDialog.Builder(this).setTitle("Desempenho · ${websiteHost(page)}")
                .setMessage("Carregamento observado: ${data.optInt("pageMs")} ms\n" +
                    "Resposta da rede: ${data.optInt("responseMs")} ms\n" +
                    "DOM pronto: ${data.optInt("domMs")} ms\n" +
                    "Recursos registrados: ${data.optInt("resources")}\n" +
                    "Imagens no documento: ${data.optInt("images")}\n" +
                    "Scripts no documento: ${data.optInt("scripts")}\n" +
                    "Memória JS aproximada: $heap\n\n" +
                    "Os valores são estimativas expostas pelo site ao WebView. " +
                    "Não equivalem a uma auditoria Lighthouse.")
                .setPositiveButton("Fechar", null).show()
        }
    }

    /** O WebView usa a rede do Android: mudar DNS requer ação do usuário no sistema. */
    private fun showCloudflareMenu() {
        showActionHub("Cloudflare", "Integração com os serviços oficiais do Android", listOf(
            MenuAction("DNS privado", "1.1.1.1 · padrão",
                "DNS criptografado sem filtragem de conteúdo") {
                configureCloudflareDns("one.one.one.one", "1.1.1.1 padrão")
            },
            MenuAction("DNS privado", "1.1.1.1 · bloquear malware",
                "Filtro DNS para domínios associados a malware") {
                configureCloudflareDns("security.cloudflare-dns.com", "1.1.1.1 para segurança")
            },
            MenuAction("DNS privado", "1.1.1.1 · famílias",
                "Filtro DNS de malware e conteúdo adulto") {
                configureCloudflareDns("family.cloudflare-dns.com", "1.1.1.1 para famílias")
            },
            MenuAction("WARP", "Abrir ou instalar WARP",
                "Usar o aplicativo oficial para proteger também o tráfego de rede") {
                openCloudflareWarp()
            },
            MenuAction("Diagnóstico", "Verificar conexão Cloudflare",
                "Abrir o teste oficial 1.1.1.1/help no navegador") {
                navigate("https://1.1.1.1/help")
            },
            MenuAction("Informações", "Como funciona no Navegador Maluco",
                "Limitações do DNS, WARP e desafios de sites") {
                AlertDialog.Builder(this)
                    .setTitle("Cloudflare no Android")
                    .setMessage("O DNS privado é uma configuração do aparelho, não um proxy " +
                        "interno do navegador. Nenhum perfil é ativado apenas ao selecioná-lo aqui.\n\n" +
                        "O modo WARP depende do aplicativo oficial da Cloudflare e da autorização VPN do Android. " +
                        "Filtros DNS não substituem antivírus nem bloqueiam todo anúncio.\n\n" +
                        "Os desafios Cloudflare/Turnstile pertencem aos sites visitados; " +
                        "o Navegador Maluco não os ignora nem os resolve artificialmente.")
                    .setPositiveButton("Entendi", null).show()
            }
        ))
    }

    private fun configureCloudflareDns(hostname: String, profile: String) {
        AlertDialog.Builder(this).setTitle("Cloudflare · $profile")
            .setMessage("Endereço do provedor DNS privado:\n$hostname\n\n" +
                "Toque em Copiar e abrir configurações. No Android, selecione " +
                "'Nome do host do provedor de DNS privado', cole o endereço e salve. " +
                "A mudança afeta a rede do aparelho, inclusive outros aplicativos.")
            .setPositiveButton("Copiar e abrir configurações") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DNS Cloudflare", hostname))
                openAndroidPrivateDnsSettings()
            }
            .setNeutralButton("Copiar endereço") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DNS Cloudflare", hostname))
                Toast.makeText(this, "DNS Cloudflare copiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun openAndroidPrivateDnsSettings() {
        // O atalho direto não é padronizado em todos os fabricantes Android.
        val actions = listOf("android.settings.PRIVATE_DNS_SETTINGS",
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS)
        val opened = actions.any { action ->
            runCatching { startActivity(Intent(action)); true }.getOrDefault(false)
        }
        if (!opened) Toast.makeText(this,
            "Abra Configurações do Android → Rede → DNS privado", Toast.LENGTH_LONG).show()
    }

    private fun openCloudflareWarp() {
        val packageName = "com.cloudflare.onedotonedotonedotone"
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null && runCatching { startActivity(launch); true }.getOrDefault(false)) return
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        if (!runCatching { startActivity(market); true }.getOrDefault(false))
            navigate("https://play.google.com/store/apps/details?id=$packageName")
    }

    private fun showDiagnosticsMenu() {
        showActionHub("Diagnósticos e testes", "Ferramentas avançadas de investigação", listOf(
            MenuAction("Reprodução", "YouTube e Music", "Extração, PO Token e HTTP") { showYouTubeTestPanel() },
            MenuAction("Reprodução", "Mídia HTML5", "Fontes e manifestos") { showMediaDiagnosticsMenu() },
            MenuAction("Compatibilidade", "Seu Imposto", "Foco, chat e rede") { showSeuImpostoToolsMenu() },
            MenuAction("Compatibilidade", "Teclado isolado", "Executar teste HTML local") { openIsolatedKeyboardTest() },
            MenuAction("Compatibilidade", "Experimentos anteriores", "Diagnósticos legados de Seu Imposto") { showSeuImpostoLegacyTests() },
            MenuAction("Conta", "Login Google", "Inspecionar sessões e tentativas") { showGoogleLoginAuditMenu() },
            MenuAction("Conta", if (googleLoginAuditEnabled) "Parar monitor Google" else "Iniciar monitor Google",
                "Alternar coleta local de eventos de diagnóstico") {
                toggleGoogleLoginAudit(); showGoogleLoginAuditMenu()
            }
        ))
    }

    private fun toggleGoogleLoginAudit() {
        googleLoginAuditEnabled = !googleLoginAuditEnabled
        if (googleLoginAuditEnabled) {
            googleLoginAuditEvents.clear()
            googleLoginAuditStarted = SystemClock.elapsedRealtime()
            googleLoginLastPage.clear()
            googleLoginRedirectCount = 0
            googleLoginPopupCount = 0
            googleLoginAppNavigationCount = 0
            recordGoogleLoginAudit("Monitor iniciado · política de cookies observada", webView.url, webView)
        }
        Toast.makeText(this, if (googleLoginAuditEnabled) "Monitor Google iniciado"
            else "Monitor Google encerrado", Toast.LENGTH_SHORT).show()
    }

    private fun showGoogleLoginAuditMenu() {
        val monitorLabel = if (googleLoginAuditEnabled) "■ Parar monitoramento" else "▶ Iniciar monitoramento"
        val cookieLabel = if (googleCookieCompatEnabled())
            "Compatibilidade Google · ATIVA (desativar)"
        else "Compatibilidade Google · DESATIVADA (testar)"
        AlertDialog.Builder(this).setTitle("Google · monitor ${if (googleLoginAuditEnabled) "ATIVO" else "PARADO"}")
            .setItems(arrayOf("Ver relatório", "Copiar relatório sem dados sensíveis",
                "Abrir página de contas Google", cookieLabel,
                "Registrar resultado da tentativa", "Limpar eventos",
                "Abrir perfil Google isolado · EXPERIMENTAL")) { _, option ->
                when (option) {
                    0 -> showGoogleLoginAuditReport()
                    1 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Diagnóstico de login Google", googleLoginAuditReport()))
                        Toast.makeText(this, "Relatório técnico copiado", Toast.LENGTH_SHORT).show()
                    }
                    2 -> navigate("https://myaccount.google.com/")
                    3 -> {
                        val updated = !googleCookieCompatEnabled()
                        getSharedPreferences("google_login_diagnostic", MODE_PRIVATE).edit()
                            .putBoolean("consistent_auth_cookies", updated).apply()
                        if (googleLoginAuditEnabled) {
                            val t = (SystemClock.elapsedRealtime() - googleLoginAuditStarted).coerceAtLeast(0)
                            if (googleLoginAuditEvents.size >= 90) googleLoginAuditEvents.removeFirst()
                            googleLoginAuditEvents.addLast("+${t}ms · Configuração · Compatibilidade Google ${if (updated) "ativada" else "desativada"}")
                        }
                        AlertDialog.Builder(this)
                            .setTitle(if (updated) "Compatibilidade ativada" else "Compatibilidade desativada")
                            .setMessage("Na próxima navegação, accounts.google.com e myaccount.google.com terão uma política de cookies consistente. " +
                                "A configuração é experimental e não elimina restrições OAuth do Google. " +
                                "Ela não apaga nem copia cookies e não recarrega páginas automaticamente.")
                            .setPositiveButton("Entendi", null).show()
                    }
                    4 -> showGoogleLoginAuditResultPicker()
                    5 -> { googleLoginAuditEvents.clear(); Toast.makeText(this, "Eventos limpos", Toast.LENGTH_SHORT).show() }
                    6 -> openIsolatedGoogleProfile()
                }
            }
            .setPositiveButton(monitorLabel) { _, _ -> toggleGoogleLoginAudit(); showGoogleLoginAuditMenu() }
            .setNegativeButton("Fechar", null).show()
    }

    private fun openIsolatedGoogleProfile() {
        if (!supportedGoogleProfile()) {
            AlertDialog.Builder(this).setTitle("Perfis independentes indisponíveis")
                .setMessage("Este motor WebView não oferece MULTI_PROFILE. " +
                    "A sessão principal foi preservada e nenhuma conta foi modificada.")
                .setPositiveButton("Entendi", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Perfil Google isolado · teste")
            .setMessage("Abre uma nova aba com armazenamento e cookies separados dos da sessão atual. " +
                "A conta principal continua intacta. O Google ainda pode restringir login em WebViews. " +
                "Não copia credenciais nem transfere sessões de outros navegadores.")
            .setPositiveButton("Abrir aba isolada") { _, _ ->
                newTab("https://accounts.google.com/", profile = isolatedGoogleProfile)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showGoogleLoginAuditResultPicker() {
        val options = arrayOf("Segunda conta apareceu no seletor",
            "Google recusou o login", "O navegador voltou à primeira conta",
            "A conta foi adicionada, mas desapareceu depois", "Resultado indefinido")
        AlertDialog.Builder(this).setTitle("Resultado do teste Google")
            .setSingleChoiceItems(options, -1) { dialog, selected ->
                if (googleLoginAuditEnabled) {
                    val elapsed = (SystemClock.elapsedRealtime() - googleLoginAuditStarted).coerceAtLeast(0)
                    if (googleLoginAuditEvents.size >= 90) googleLoginAuditEvents.removeFirst()
                    googleLoginAuditEvents.addLast("+${elapsed}ms · Resultado informado · ${options[selected]}")
                } else Toast.makeText(this, "Inicie o monitor para registrar o resultado", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showGoogleLoginAuditReport() {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiBackground())
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Google · relatório técnico"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(uiForeground())
            setPadding(0, 0, 0, dp(12))
        })
        val scroll = ScrollView(this)
        val body = TextView(this).apply {
            text = googleLoginAuditReport()
            textSize = 13f
            setTextColor(uiForeground())
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = uiShape(uiSurface(), 12, true)
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(uiAction("Copiar", true).apply {
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Diagnóstico de login Google", googleLoginAuditReport()))
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(uiAction("Atualizar").apply {
            setOnClickListener { body.text = googleLoginAuditReport(); scroll.scrollTo(0, 0) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        actions.addView(uiAction("Fechar").apply { setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply {
            topMargin = dp(10)
        })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(720)),
            minOf(resources.displayMetrics.heightPixels * 85 / 100, dp(760)))
    }

    private fun showSeuImpostoToolsMenu() {
        showActionHub("Seu Imposto", "Compatibilidade do chat e diagnóstico técnico", listOf(
            MenuAction("Acesso", "Chat no GeckoView", "Abrir em motor alternativo") { openSeuImpostoInGecko() },
            MenuAction("Acesso", "Entrada Android", "Digitar pelo controle nativo") { showSeuImpostoNativeChatInput() },
            MenuAction("Diagnóstico", "Resumo de foco e rede", "Examinar eventos sem dados sensíveis") { showSeuImpostoDiagnostic() },
            MenuAction("Diagnóstico", "Rastrear reconstrução", "Identificar a origem de mudanças no DOM") { startSeuImpostoOriginTrace() },
            MenuAction("Experimentos", "Identificação do navegador", "Alternar modo de compatibilidade") { toggleSeuImpostoPlainChromeUa() },
            MenuAction("Experimentos", "Testes anteriores", "Ajustes legados de teclado e DOM") { showSeuImpostoLegacyTests() }
        ))
    }

    private fun openSeuImpostoInGecko() {
        val currentUrl = webView.url ?: tabs.getOrNull(activeIndex)?.url
        val target = currentUrl?.takeIf { isSeuImposto(it) }
            ?: "https://seuimposto.com/#chat"
        startActivity(Intent(this, GeckoBrowserActivity::class.java).apply {
            putExtra(GeckoBrowserActivity.EXTRA_URL, target)
        })
    }

    private fun showSeuImpostoLegacyTests() {
        showActionHub("Experimentos anteriores", "Ajustes técnicos que podem alterar o comportamento da página", listOf(
            MenuAction("Teclado", "Compatibilidade de teclado", "Ativar ou desativar ajuste legado") { toggleSeuImpostoKeyboardCompat() },
            MenuAction("Teclado", "Recuperação de foco", "Experimento de foco automático") { toggleSeuImpostoFocusRecovery() },
            MenuAction("Teclado", "Janela sem redimensionamento", "Alternar adjustPan") { toggleSeuImpostoKeyboardPan() },
            MenuAction("Teclado", "VirtualKeyboard overlaysContent", "Experimento de viewport") { toggleSeuImpostoViewportOverlay() },
            MenuAction("DOM", "Eventos resize", "Testar bloqueio de redimensionamento") { showSeuImpostoResizeExperiment() },
            MenuAction("DOM", "Rastrear remoções", "Coletar eventos de elementos removidos") { toggleSeuImpostoRemovalTrace() },
            MenuAction("DOM", "Proteger FORM", "Experimento de bloqueio de remoção") { toggleSeuImpostoFormGuard() },
            MenuAction("DOM", "Proteger FORM e SECTION", "Experimento de proteção de subárvores") { toggleSeuImpostoSubtreeGuard() }
        ))
    }

    private fun openIsolatedKeyboardTest() {
        if (!BrowserTabPolicy.canCreate(totalTabCount)) {
            Toast.makeText(this, "Limite de abas atingido", Toast.LENGTH_LONG).show()
            return
        }
        val html = """<!doctype html><html lang="pt-br"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Teste isolado · teclado</title>
            <style>body{font:16px sans-serif;margin:20px;background:#f5f6f8;color:#111}
            input,textarea{box-sizing:border-box;width:100%;font:18px sans-serif;
            padding:14px;margin:10px 0;border:1px solid #777;border-radius:8px}
            pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#e6e8ec;padding:12px;
            min-height:130px;font:12px monospace}</style></head><body>
            <h2>Teste HTML isolado</h2>
            <p>Digite nos campos. Nenhum conteúdo é registrado ou enviado.</p>
            <input type="text" aria-label="Campo simples" placeholder="Campo de texto">
            <textarea aria-label="Área de texto" placeholder="Área de texto"></textarea>
            <h3>Eventos do teclado</h3><pre id="events"></pre>
            <script>
            const events=document.getElementById('events');
            const started=Date.now();
            const log=(kind)=>{
                const viewport=window.visualViewport?Math.round(visualViewport.height):null;
                const active=document.activeElement;
                events.textContent+=(Date.now()-started)+'ms '+kind+' viewport='+viewport+
                    ' janela='+innerHeight+' foco='+(active?active.tagName:'nenhum')+'\n';
            };
            document.addEventListener('focusin',()=>log('focusin'),true);
            document.addEventListener('focusout',()=>log('focusout'),true);
            if(window.visualViewport)
                visualViewport.addEventListener('resize',()=>log('viewport-resize'));
            log('início');
            </script></body></html>"""
        newTab("about:blank") { webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
    }

    private fun toggleSeuImpostoFocusRecovery() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste ajuste", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("focus_recovery", false)
        prefs.edit().putBoolean("focus_recovery", enabled).apply()
        webView.reload()
        Toast.makeText(this,
            if (enabled) "Recuperação de foco ativada para Seu Imposto"
            else "Recuperação de foco desativada",
            Toast.LENGTH_LONG).show()
    }

    private fun toggleSeuImpostoKeyboardPan() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste ajuste", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
        val enabled = !seuImpostoPanEnabled()
        prefs.edit().putBoolean("keyboard_pan", enabled).apply()
        updateKeyboardWindowMode(webView.url)
        recordSiteDiagnostic(webView, "Ajuste de teclado " +
            if (enabled) "sem redimensionamento (pan)" else "padrão (resize)")
        Toast.makeText(this,
            if (enabled) "Modo sem redimensionamento ligado para Seu Imposto"
            else "Redimensionamento padrão restaurado para Seu Imposto",
            Toast.LENGTH_LONG).show()
    }

    private fun toggleSeuImpostoViewportOverlay() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste ajuste", Toast.LENGTH_LONG).show()
            return
        }
        // A sessão conserva a escolha inclusive após recarregar a página.
        webView.evaluateJavascript("""(function(){
            try {
                const vk=navigator.virtualKeyboard;
                if(!vk)return 'indisponivel';
                const next=!vk.overlaysContent;
                sessionStorage.setItem('__nmVkOverlayOff',next?'0':'1');
                vk.overlaysContent=next;
                return next?'ligado':'desligado';
            }catch(_){return 'erro';}
        })()""") { raw ->
            val result = raw.trim('"')
            Toast.makeText(this,
                "Área visual estável · $result", Toast.LENGTH_LONG).show()
            recordSiteDiagnostic(webView, "VirtualKeyboard overlaysContent · $result")
        }
    }

    private fun showSeuImpostoResizeExperiment() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste experimento", Toast.LENGTH_LONG).show()
            return
        }
        val modes = arrayOf("normal", "window", "visual", "both")
        val names = arrayOf("Normal · nenhum evento bloqueado",
            "Teste A · bloquear window.resize",
            "Teste B · bloquear visualViewport.resize",
            "Teste C · bloquear os dois resize")
        AlertDialog.Builder(this).setTitle("Experimento de eventos resize")
            .setItems(names) { _, selected ->
                val mode = modes[selected]
                webView.evaluateJavascript("""(function(){
                    sessionStorage.setItem('__nmResizeProbeMode','$mode');
                    return true;
                })()""") {
                    // Recarrega para instalar o filtro antes dos scripts do site.
                    webView.reload()
                }
            }.show()
    }

    private fun toggleSeuImpostoRemovalTrace() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste diagnóstico", Toast.LENGTH_LONG).show()
            return
        }
        webView.evaluateJavascript("""(function(){
            const enabled=sessionStorage.getItem('__nmTraceFormRemoval')==='1';
            sessionStorage.setItem('__nmTraceFormRemoval',enabled?'0':'1');
            sessionStorage.setItem('__nmResizeProbeMode','normal');
            return enabled?'desligado':'ligado';
        })()""") { raw ->
            Toast.makeText(this, "Rastreamento do formulário · ${raw.trim('"')}",
                Toast.LENGTH_LONG).show()
            webView.reload()
        }
    }

    private fun toggleSeuImpostoFormGuard() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste experimento", Toast.LENGTH_LONG).show()
            return
        }
        webView.evaluateJavascript("""(function(){
            const enabled=sessionStorage.getItem('__nmGuardFormRemoval')==='1';
            sessionStorage.setItem('__nmGuardFormRemoval',enabled?'0':'1');
            sessionStorage.setItem('__nmTraceFormRemoval','1');
            sessionStorage.setItem('__nmResizeProbeMode','normal');
            return enabled?'desligado':'ligado';
        })()""") { raw ->
            Toast.makeText(this, "Bloqueio experimental do FORM · ${raw.trim('"')}",
                Toast.LENGTH_LONG).show()
            webView.reload()
        }
    }

    private fun startSeuImpostoOriginTrace() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes do diagnóstico", Toast.LENGTH_LONG).show()
            return
        }
        webView.evaluateJavascript("""(function(){
            sessionStorage.setItem('__nmTraceFormRemoval','1');
            sessionStorage.setItem('__nmGuardFormRemoval','0');
            sessionStorage.setItem('__nmGuardChatSubtree','0');
            sessionStorage.setItem('__nmResizeProbeMode','normal');
            return true;
        })()""") {
            Toast.makeText(this, "Rastreamento de origem ligado, sem bloqueios",
                Toast.LENGTH_LONG).show()
            webView.reload()
        }
    }

    private fun toggleSeuImpostoSubtreeGuard() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste teste", Toast.LENGTH_LONG).show()
            return
        }
        webView.evaluateJavascript("""(function(){
            const enabled=sessionStorage.getItem('__nmGuardChatSubtree')==='1';
            sessionStorage.setItem('__nmGuardChatSubtree',enabled?'0':'1');
            sessionStorage.setItem('__nmGuardFormRemoval','0');
            sessionStorage.setItem('__nmTraceFormRemoval','1');
            sessionStorage.setItem('__nmResizeProbeMode','normal');
            return enabled?'desligada':'ligada';
        })()""") { raw ->
            Toast.makeText(this, "Proteção de FORM e SECTION · ${raw.trim('"')}",
                Toast.LENGTH_LONG).show()
            webView.reload()
        }
    }

    private fun showSeuImpostoNativeChatInput() {
        val page = webView
        if (!isSeuImposto(page.url)) {
            Toast.makeText(this, "Abra o chat do Seu Imposto primeiro", Toast.LENGTH_LONG).show()
            return
        }
        val field = EditText(this).apply {
            styleInput(this)
            hint = "Mensagem para o chat"
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(this).setTitle("Digitar pelo Android · Seu Imposto")
            .setMessage("O texto será preenchido no formulário do site. O envio não é automático.")
            .setView(field)
            .setPositiveButton("Preencher no chat", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val message = field.text.toString()
            if (message.isBlank()) return@setOnClickListener
            if (page !== webView || !isSeuImposto(page.url)) {
                dialog.setMessage("A aba do Seu Imposto não está mais ativa.")
                return@setOnClickListener
            }
            // Esconde primeiro o IME nativo e aguarda o campo Web estabilizar.
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val quoted = org.json.JSONObject.quote(message)
            fun tryFill(attempt: Int) {
                if (!dialog.isShowing || page !== webView || !isSeuImposto(page.url)) return
                // Não registra texto e jamais chama submit ou click no site.
                page.evaluateJavascript("""(function(){
                    const all=[...document.querySelectorAll('form input,form textarea')]
                        .filter(el=>el.matches('textarea,input[type="text"],input:not([type])'));
                    const visible=all.filter(el=>{const rect=el.getBoundingClientRect();
                        const style=getComputedStyle(el);
                        return el.isConnected&&rect.width>0&&rect.height>0&&
                            style.display!=='none'&&style.visibility!=='hidden';});
                    const ready=visible.filter(el=>!el.disabled&&!el.readOnly);
                    if(ready.length!==1||visible.length!==1)
                        return 'aguardar:total='+all.length+',visiveis='+visible.length+
                            ',habilitados='+ready.length;
                    const el=ready[0];
                    const proto=el instanceof HTMLTextAreaElement?
                        HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                    const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;
                    if(!setter)return 'sem-setter';
                    setter.call(el,$quoted);
                    el.dispatchEvent(new Event('input',{bubbles:true}));
                    el.dispatchEvent(new Event('change',{bubbles:true}));
                    return el.value===$quoted?'preenchido':'não-confirmado';
                })()""") { raw ->
                    if (!dialog.isShowing) return@evaluateJavascript
                    val status = raw.trim('"')
                    if (status == "preenchido") {
                        dialog.dismiss()
                        Toast.makeText(this, "Texto preenchido. Envio pelo site.",
                            Toast.LENGTH_LONG).show()
                    } else if (status.startsWith("aguardar:") && attempt < 24) {
                        page.postDelayed({ tryFill(attempt + 1) }, 250)
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.setMessage("O site não aceitou o preenchimento. " +
                            "Estado: $status. O texto continua neste campo Android.")
                    }
                    // Somente o estado do DOM é registrado, nunca a mensagem.
                    if (status == "preenchido" || attempt >= 24 ||
                        !status.startsWith("aguardar:"))
                        recordSiteDiagnostic(page, "Entrada Android · resultado=$status · tentativas=${attempt + 1}")
                }
            }
            page.postDelayed({ tryFill(0) }, 350)
        }
    }

    private fun toggleSeuImpostoPlainChromeUa() {
        val page = webView
        if (!isSeuImposto(page.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste teste", Toast.LENGTH_LONG).show()
            return
        }
        val modes = arrayOf("webview", "chrome", "firefox")
        val labels = arrayOf("WebView original", "Chrome móvel (simulado)",
            "Firefox móvel (simulado)")
        val selected = modes.indexOf(seuImpostoUaProbe()).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Comparar identificação do navegador")
            .setSingleChoiceItems(labels, selected) { dialog, chosen ->
                dialog.dismiss()
                getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE).edit()
                    .putString("ua_probe", modes[chosen]).apply()
                configureSiteDisplay(page, page.url)
                // Conserva cookies e storage, mudando somente a identificação.
                page.reload()
                Toast.makeText(this, "Identificação: ${labels[chosen]}",
                    Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun toggleSeuImpostoKeyboardCompat() {
        if (!isSeuImposto(webView.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes deste teste", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("keyboard_compat", false)
        prefs.edit().putBoolean("keyboard_compat", enabled).apply()
        lastSeuImpostoImeVisible = null
        ViewCompat.requestApplyInsets(window.decorView)
        webView.reload()
        Toast.makeText(this,
            if (enabled) "Teste de teclado ativado apenas para Seu Imposto"
            else "Tratamento normal do teclado restaurado",
            Toast.LENGTH_LONG).show()
    }

    private fun showSeuImpostoDiagnostic() {
        val page = webView
        if (!isSeuImposto(page.url)) {
            Toast.makeText(this, "Abra seuimposto.com antes do diagnóstico",
                Toast.LENGTH_LONG).show()
            return
        }
        val script = """(function(){
            const body=document.body;
            const text=body?body.innerText||'':'';
            const audit=window.__nmKeyboardAudit;
            const resizeProbe=window.__nmResizeProbe||{};
            const removalTrace=window.__nmRemovalTrace||{};
            // Resource Timing é observacional: não intercepta fetch/XHR nem lê mensagens.
            const allChatRequests=performance.getEntriesByType('resource').filter(entry=>{
                try{const u=new URL(entry.name);return u.origin===location.origin&&
                    (u.pathname==='/api/chat/eu'||u.pathname==='/api/chat/sala');}
                catch(_){return false;}
            }).slice(-100).map(entry=>({
                route:new URL(entry.name).pathname==='/api/chat/sala'?'sala':'eu',
                started:Math.round((performance.timeOrigin+entry.startTime)%600000),
                finished:Math.round((performance.timeOrigin+entry.responseEnd)%600000),
                status:Number(entry.responseStatus)||0,
                duration:Math.round(entry.duration),
                initiator:entry.initiatorType||'?'
            }));
            const chatRequests=allChatRequests.slice(-35);
            const delta=(later,earlier)=>(later-earlier+600000)%600000;
            const correlations=(audit?audit.logs:[])
                .filter(e=>e.event==='focused-node-removal').slice(-12)
                .map(e=>{
                    const viewport=(audit?audit.logs:[]).filter(x=>
                        x.event==='viewport-resize'&&delta(e.t,x.t)<=2000)
                        .sort((a,b)=>delta(e.t,a.t)-delta(e.t,b.t))[0];
                    const prior=allChatRequests.filter(x=>x.route==='sala'&&
                        delta(e.t,x.finished)<=1500)
                        .sort((a,b)=>delta(e.t,a.finished)-delta(e.t,b.finished))[0];
                    const next=allChatRequests.filter(x=>x.route==='sala'&&
                        delta(x.started,e.t)<=1500)
                        .sort((a,b)=>delta(a.started,e.t)-delta(b.started,e.t))[0];
                    return {t:e.t,
                        afterViewportMs:viewport?delta(e.t,viewport.t):null,
                        afterSalaResponseMs:prior?delta(e.t,prior.finished):null,
                        priorSalaHttp:prior?prior.status:null,
                        beforeNextSalaRequestMs:next?delta(next.started,e.t):null};
                });
            return JSON.stringify({
                title:document.title,
                blockedText:text.includes('Não deu para abrir a apuração neste navegador'),
                scripts:document.scripts.length,
                frames:document.querySelectorAll('iframe').length,
                bodyCharacters:text.length,
                webSocket:typeof WebSocket!=='undefined',
                eventSource:typeof EventSource!=='undefined',
                localStorage:(()=>{try{return !!window.localStorage}catch(e){return false}})(),
                cryptoSubtle:!!(window.crypto&&crypto.subtle),
                webRTC:typeof RTCPeerConnection!=='undefined',
                networkOnline:navigator.onLine,
                virtualKeyboardAvailable:!!navigator.virtualKeyboard,
                overlaysContent:navigator.virtualKeyboard?
                    navigator.virtualKeyboard.overlaysContent:null,
                resizeProbeMode:resizeProbe.mode||'indisponível',
                resizeBlockedWindow:resizeProbe.blockedWindow||0,
                resizeBlockedVisual:resizeProbe.blockedVisual||0,
                removalTraceEnabled:!!removalTrace.enabled,
                formGuardEnabled:!!removalTrace.guardEnabled,
                subtreeGuardEnabled:!!removalTrace.subtreeGuard,
                formGuardBlocked:removalTrace.guardBlocked||0,
                removalTraceEvents:removalTrace.events||[],
                activeTag:document.activeElement&&document.activeElement.tagName,
                keyboardEvents:audit?audit.logs:[],chatRequests:chatRequests,
                correlations:correlations
            });
        })()"""
        page.evaluateJavascript(script) { raw ->
            if (page !== webView || !isSeuImposto(page.url)) return@evaluateJavascript
            val data = runCatching {
                org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
            }.getOrNull()
            val events = synchronized(siteDiagnostics) { siteDiagnostics[page]?.toList().orEmpty() }
            val packageInfo = WebView.getCurrentWebViewPackage()
            val report = buildString {
                appendLine("Seu Imposto · diagnóstico WebView")
                appendLine("Motor: ${packageInfo?.packageName ?: "indisponível"} " +
                    "${packageInfo?.versionName ?: ""}")
                appendLine("Modo: ${siteDisplayMode(page.url)}")
                appendLine("User-Agent: ${page.settings.userAgentString}")
                appendLine("Identificação em teste: ${seuImpostoUaProbe()}")
                appendLine("JavaScript: ${page.settings.javaScriptEnabled}")
                appendLine("DOM Storage: ${page.settings.domStorageEnabled}")
                appendLine("Cookies de terceiros: " +
                    CookieManager.getInstance().acceptThirdPartyCookies(page))
                listOf("title", "blockedText", "scripts", "frames", "bodyCharacters",
                    "webSocket", "eventSource", "localStorage", "cryptoSubtle", "webRTC",
                    "virtualKeyboardAvailable", "overlaysContent", "networkOnline", "activeTag",
                    "resizeProbeMode", "resizeBlockedWindow", "resizeBlockedVisual")
                    .forEach { appendLine("$it: ${data?.optString(it, "indisponível") ?: "indisponível"}") }
                appendLine("Rastreamento de remoções: ${data?.optString("removalTraceEnabled", "false")}")
                appendLine("Bloqueio experimental FORM: ${data?.optString("formGuardEnabled", "false")}")
                appendLine("Proteção FORM + SECTION: ${data?.optString("subtreeGuardEnabled", "false")}")
                appendLine("Remoções impedidas: ${data?.optString("formGuardBlocked", "0")}")
                val removalEvents = data?.optJSONArray("removalTraceEvents")
                for (i in 0 until (removalEvents?.length() ?: 0)) {
                    val e = removalEvents?.optJSONObject(i) ?: continue
                    appendLine("- remoção t=${e.optString("t")} " +
                        "método=${e.optString("method")} " +
                        "nó=${e.optString("removedTag")}")
                    if (e.has("msSinceFocus"))
                        appendLine("  contexto focoHá=${e.optString("msSinceFocus")}ms " +
                            "resizeJanelaHá=${e.optString("msSinceWindowResize")}ms " +
                            "resizeVisualHá=${e.optString("msSinceVisualResize")}ms " +
                            "focoAtual=${e.optString("activeTag")} " +
                            "viewport=${e.optString("viewport")}")
                    appendLine("  funções=${e.optJSONArray("functions")?.toString() ?: "[]"}")
                    val frames = e.optJSONArray("frames")
                    if (frames != null) {
                        for (j in 0 until frames.length()) {
                            val frame = frames.optJSONObject(j) ?: continue
                            appendLine("  origem=${frame.optString("fn")} @ ${frame.optString("source")}")
                        }
                    }
                }
                appendLine("Compatibilidade teclado: " +
                    getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                        .getBoolean("keyboard_compat", false))
                appendLine("Recuperação de foco: " +
                    getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                        .getBoolean("focus_recovery", false))
                appendLine("Teclado sem redimensionamento (pan): ${seuImpostoPanEnabled()}")
                appendLine("Eventos de foco/teclado:")
                val keyboardEvents = data?.optJSONArray("keyboardEvents")
                for (i in 0 until (keyboardEvents?.length() ?: 0)) {
                    val e = keyboardEvents?.optJSONObject(i) ?: continue
                    appendLine("- t=${e.optString("t")} ${e.optString("event")} ${e.optString("tag")}" +
                        " tipo=${e.optString("type")} viewport=${e.optString("viewport")}" +
                        " janela=${e.optString("screen")}" +
                        " docAltura=${e.optString("documentHeight")}" +
                        " deslocamento=${e.optString("viewportOffset")}" +
                        (if (e.has("connected"))
                            " conectado=${e.optString("connected")}" +
                            " habilitado=${!e.optBoolean("disabled")}" +
                            " somenteLeitura=${e.optString("readOnly")}" +
                            " inerte=${e.optString("inert")}" +
                            " visível=${e.optString("visible")}" +
                            " docFocado=${e.optString("documentFocus")}" +
                            " elementoAtivo=${e.optString("activeTag")}" else ""))
                    if (e.has("ariaDisabled") || e.has("previousDisabled"))
                        appendLine("  ariaDisabled=${e.optString("ariaDisabled", "?")}" +
                            " disabledAnterior=${e.optString("previousDisabled", "?")}")
                    if (e.has("tree") || e.has("removedTree"))
                        appendLine("  estrutura=${e.optString("tree", "?")}" +
                            " removidoDe=${e.optString("removedTree", "?")}" +
                            " nóRemovido=${e.optString("removedTag", "?")}" +
                            " novosIrmãos=${e.optString("addedSiblings", "?")}")
                    if (e.has("compactHeight"))
                        appendLine("  layout largura=${e.optString("width", "?")}" +
                            " alturaAté400=${e.optString("compactHeight", "?")}" +
                            " alturaAté600=${e.optString("mediumHeight", "?")}" +
                            " larguraAté600=${e.optString("narrowWidth", "?")}")
                }
                appendLine("Linha do tempo da API de chat (Resource Timing):")
                val chatRequests = data?.optJSONArray("chatRequests")
                if (chatRequests == null || chatRequests.length() == 0)
                    appendLine("- Nenhuma chamada registrada pelo navegador")
                for (i in 0 until (chatRequests?.length() ?: 0)) {
                    val e = chatRequests?.optJSONObject(i) ?: continue
                    val status = e.optInt("status")
                    appendLine("- rota=${e.optString("route")} " +
                        "início=${e.optString("started")} " +
                        "fim=${e.optString("finished")} " +
                        "HTTP=${if (status == 0) "indisponível" else status.toString()} " +
                        "duração=${e.optString("duration")}ms " +
                        "origem=${e.optString("initiator")}")
                }
                appendLine("Correlação temporal · viewport × sala × remoção:")
                val correlations = data?.optJSONArray("correlations")
                if (correlations == null || correlations.length() == 0)
                    appendLine("- Nenhuma remoção correlacionável registrada")
                for (i in 0 until (correlations?.length() ?: 0)) {
                    val e = correlations?.optJSONObject(i) ?: continue
                    fun interval(key: String): String =
                        if (e.isNull(key)) "—" else "${e.optLong(key)}ms"
                    appendLine("- remoção t=${e.optString("t")} " +
                        "desdeViewport=${interval("afterViewportMs")} " +
                        "desdeRespostaSala=${interval("afterSalaResponseMs")} " +
                        "HTTPsala=${if (e.isNull("priorSalaHttp")) "—" else e.optInt("priorSalaHttp")} " +
                        "atéPróximaConsulta=${interval("beforeNextSalaRequestMs")}")
                }
                appendLine("Eventos de carregamento (${events.size}):")
                events.forEach { appendLine("- $it") }
                appendLine("Não inclui mensagens, cookies, URLs completas ou dados do chat.")
            }
            val keyboardEvents = data?.optJSONArray("keyboardEvents")
            val removed = (0 until (keyboardEvents?.length() ?: 0)).count { index ->
                keyboardEvents?.optJSONObject(index)?.optString("event") ==
                    "focused-node-removal"
            }
            val focusAttempts = (0 until (keyboardEvents?.length() ?: 0)).count { index ->
                keyboardEvents?.optJSONObject(index)?.optString("event") == "focusin" &&
                    keyboardEvents?.optJSONObject(index)?.optString("tag") == "INPUT"
            }
            val http503 = events.filter { "HTTP 503" in it }
            val chatRequests = data?.optJSONArray("chatRequests")
            val chatFailures = (0 until (chatRequests?.length() ?: 0)).count { index ->
                (chatRequests?.optJSONObject(index)?.optInt("status") ?: 0) >= 500
            }
            val keyboardTimeline = (0 until (keyboardEvents?.length() ?: 0)).mapNotNull { index ->
                keyboardEvents?.optJSONObject(index)
            }
            val firstRemovalIndex = keyboardTimeline.indexOfFirst {
                it.optString("event") == "focused-node-removal"
            }
            val resizeToRemovalMs = if (firstRemovalIndex >= 0) {
                val precedingResize = keyboardTimeline.take(firstRemovalIndex).lastOrNull {
                    it.optString("event") == "viewport-resize"
                }
                val from = precedingResize?.optLong("t", -1) ?: -1L
                val to = keyboardTimeline[firstRemovalIndex].optLong("t", -1)
                if (from >= 0 && to >= from) to - from else null
            } else null
            val overview = buildString {
                appendLine("SEU IMPOSTO · RESUMO")
                appendLine("Motor: ${packageInfo?.versionName ?: "indisponível"}")
                appendLine("Identificação: ${seuImpostoUaProbe()}")
                appendLine("Focos em INPUT registrados: $focusAttempts")
                appendLine("Remoções do elemento focado: $removed")
                appendLine("Viewport → primeira remoção: " +
                    (resizeToRemovalMs?.let { "${it}ms" } ?: "sem correlação registrada"))
                appendLine("Erros HTTP 503 observados: ${http503.size}")
                appendLine("Falhas 5xx nas chamadas de chat monitoradas: $chatFailures")
                appendLine("Entrada Android: disponível no menu anterior")
                appendLine()
                appendLine("LEITURA DOS RESULTADOS")
                appendLine(if (removed > 0)
                    "O site removeu um elemento de entrada durante o teste. " +
                        "A substituição do formulário é a causa imediata mais bem documentada " +
                        "da perda de foco."
                    else "Nenhuma remoção de campo foi registrada nesta janela de eventos. " +
                        "Isso não comprova que o problema desapareceu.")
                appendLine(if (http503.isNotEmpty())
                    "Há respostas 503 de subrecursos. Não atribua a elas a perda de foco " +
                        "sem correlação temporal."
                    else "Nenhum HTTP 503 foi registrado nesta janela de eventos.")
                appendLine()
                appendLine("CONFIGURAÇÃO ATIVA")
                appendLine("Pan: ${seuImpostoPanEnabled()}; " +
                    "resize: ${data?.optString("resizeProbeMode", "?")}; " +
                    "guarda FORM: ${data?.optString("formGuardEnabled", "?")}; " +
                    "guarda SECTION: ${data?.optString("subtreeGuardEnabled", "?")}")
                appendLine("Cookies extras: " +
                    CookieManager.getInstance().acceptThirdPartyCookies(page))
                appendLine()
                appendLine("Os dados não incluem mensagens ou identificadores de sessão.")
            }
            val focusDetails = buildString {
                appendLine("FOCO, TECLADO E DOM")
                appendLine(report.substringAfter("Rastreamento de remoções:")
                    .substringBefore("Compatibilidade teclado:"))
                appendLine(report.substringAfter("Eventos de foco/teclado:")
                    .substringBefore("Linha do tempo da API de chat"))
            }
            val networkDetails = buildString {
                appendLine("REDE E REQUISIÇÕES")
                appendLine(report.substringAfter("Linha do tempo da API de chat (Resource Timing):"))
            }
            val sections = linkedMapOf("Resumo" to overview, "Foco" to focusDetails,
                "Rede" to networkDetails, "Completo" to report)
            val density = resources.displayMetrics.density
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (8 * density).toInt(),
                    (12 * density).toInt(), (8 * density).toInt())
            }
            val sectionButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val textPanel = TextView(this).apply {
                textSize = 13f
                setTextIsSelectable(true)
                text = overview
                setPadding(4, (8 * density).toInt(), 4, (8 * density).toInt())
            }
            val scroll = ScrollView(this).apply {
                addView(textPanel)
            }
            sections.forEach { (label, content) ->
                sectionButtons.addView(Button(this).apply {
                    text = label
                    textSize = 11f
                    isAllCaps = false
                    setOnClickListener {
                        textPanel.text = content
                        scroll.scrollTo(0, 0)
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            container.addView(sectionButtons)
            container.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT,
                (440 * density).toInt()))
            AlertDialog.Builder(this)
                .setTitle("Diagnóstico · Seu Imposto")
                .setView(container)
                .setPositiveButton("Fechar", null)
                .setNegativeButton(if (CookieManager.getInstance().acceptThirdPartyCookies(page))
                    "Desligar cookies extras" else "Testar cookies extras") { _, _ ->
                    val prefs = getSharedPreferences("seuimposto_diagnostic", MODE_PRIVATE)
                    val enable = !prefs.getBoolean("third_party_cookies", false)
                    prefs.edit().putBoolean("third_party_cookies", enable).apply()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(page, enable)
                    Toast.makeText(this,
                        if (enable) "Cookies de terceiros habilitados apenas para este site"
                        else "Cookies de terceiros desativados para este site",
                        Toast.LENGTH_LONG).show()
                    // Recarrega para comparar o comportamento com a opção anterior.
                    page.reload()
                }
                .setNeutralButton("Copiar relatório") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Diagnóstico Seu Imposto", report))
                }.show()
        }
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
    /** Identifica páginas de vídeo do YouTube, não URLs diretas para o Media3. */
    private fun youtubeVideoId(pageUrl: String?): String? {
        val uri = runCatching { Uri.parse(pageUrl ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> uri.pathSegments.firstOrNull()
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                uri.path == "/watch" -> uri.getQueryParameter("v")
                uri.pathSegments.firstOrNull() in listOf("shorts", "live", "embed") ->
                    uri.pathSegments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    /** Inspeção somente de estados HTMLMediaElement acessíveis no documento atual. */
    private fun inspectYouTubePlayer(view: WebView, onResult: (String) -> Unit) {
        val js = """(function() {
            const videos = Array.from(document.querySelectorAll('video'));
            const audios = Array.from(document.querySelectorAll('audio'));
            const frames = document.querySelectorAll('iframe').length;
            const protocols = { blob: 0, https: 0, other: 0, empty: 0 };
            let playing = 0, paused = 0, withDimensions = 0, buffered = 0;
            for (const el of [...videos, ...audios]) {
                const src = el.currentSrc || '';
                if (!src) protocols.empty++;
                else if (src.startsWith('blob:')) protocols.blob++;
                else if (src.startsWith('https:')) protocols.https++;
                else protocols.other++;
                if (el.paused) paused++; else playing++;
                if (el.videoWidth > 0 && el.videoHeight > 0) withDimensions++;
                if (el.buffered && el.buffered.length > 0) buffered++;
            }
            const primary = videos[0] || audios[0] || null;
            // Somente métricas numéricas e estados padronizados. Nenhum endereço é exposto.
            const state = primary ? {
                ready: primary.readyState,
                network: primary.networkState,
                duration: Number.isFinite(primary.duration) ?
                    Math.round(primary.duration) : -1,
                current: Math.round(primary.currentTime || 0),
                seekable: primary.seekable.length,
                bufferAhead: (() => {
                    for (let i = 0; i < primary.buffered.length; i++) {
                        if (primary.buffered.start(i) <= primary.currentTime &&
                            primary.buffered.end(i) >= primary.currentTime)
                            return Math.round(primary.buffered.end(i) - primary.currentTime);
                    }
                    return 0;
                })(),
                width: primary.videoWidth || 0,
                height: primary.videoHeight || 0
            } : null;
            return JSON.stringify({video:videos.length,audio:audios.length,iframes:frames,
                playing:playing,paused:paused,dimensions:withDimensions,buffered:buffered,
                blob:protocols.blob,https:protocols.https,
                other:protocols.other,empty:protocols.empty,state:state});
        })()"""
        view.evaluateJavascript(js) { result ->
            val summary = runCatching {
                val decoded = org.json.JSONArray("[$result]").getString(0)
                val data = org.json.JSONObject(decoded)
                "Elementos: ${data.optInt("video")} vídeo(s), ${data.optInt("audio")} áudio(s), " +
                    "${data.optInt("iframes")} iframe(s)\n" +
                    "Estado: ${data.optInt("playing")} reproduzindo, ${data.optInt("paused")} pausado(s)\n" +
                    "Fonte: ${data.optInt("blob")} blob, ${data.optInt("https")} HTTPS, " +
                    "${data.optInt("empty")} vazia, ${data.optInt("other")} outra(s)\n" +
                    "Com dimensões: ${data.optInt("dimensions")}; com buffer: ${data.optInt("buffered")}" +
                    data.optJSONObject("state")?.let { st ->
                        "\nreadyState=${st.optInt("ready")}; networkState=${st.optInt("network")}" +
                            "\nposição=${st.optInt("current")}s; duração=${st.optInt("duration")}s " +
                            "(-1 = desconhecida/ao vivo)" +
                            "\nbuffer à frente=${st.optInt("bufferAhead")}s; " +
                            "intervalos seekable=${st.optInt("seekable")}" +
                            "\nresolução=${st.optInt("width")}x${st.optInt("height")}"
                    }.orEmpty()
            }.getOrElse { "Estados do player indisponíveis neste documento." }
            onResult(summary)
        }
    }

    /**
     * Adaptador experimental: lê somente manifestos declarados pela página atual.
     * Não decifra assinaturas nem acessa recursos privados ou protegidos.
     */
    private fun scanYouTubeDeclaredManifests(view: WebView, after: () -> Unit) {
        if (youtubeVideoId(view.url) == null) { after(); return }
        val original = view.url
        val js = """(function() {
            const out = [];
            try {
                const data = window.ytInitialPlayerResponse;
                const stream = data && data.streamingData;
                for (const key of ['hlsManifestUrl', 'dashManifestUrl']) {
                    const url = stream && stream[key];
                    if (typeof url !== 'string') continue;
                    const u = new URL(url, location.href);
                    if (u.protocol !== 'https:') continue;
                    out.push({kind:key, url:u.href});
                }
            } catch(e) {}
            return JSON.stringify(out);
        })()"""
        view.evaluateJavascript(js) { result ->
            if (view.url == original && !isDestroyed) {
                runCatching {
                    val decoded = org.json.JSONArray("[$result]").getString(0)
                    val list = org.json.JSONArray(decoded)
                    var accepted = 0
                    synchronized(mediaCandidateLock) {
                        val found = mediaCandidates.getOrPut(view) { LinkedHashSet() }
                        for (i in 0 until list.length()) {
                            val obj = list.optJSONObject(i) ?: continue
                            val kind = obj.optString("kind")
                            val url = obj.optString("url")
                            val parsed = Uri.parse(url)
                            val suffix = if (kind == "hlsManifestUrl") ".m3u8" else ".mpd"
                            if (kind !in listOf("hlsManifestUrl", "dashManifestUrl") ||
                                parsed.scheme != "https" || parsed.host.isNullOrBlank()) continue
                            // Este catálogo existente reconhece apenas manifestos com extensão.
                            // Se a plataforma usar URLs sem extensão, registrar só a contagem.
                            if (parsed.path?.lowercase(java.util.Locale.ROOT)?.endsWith(suffix) == true) {
                                if (found.size >= 60) found.remove(found.first())
                                if (found.add(url)) accepted++
                            }
                        }
                    }
                    android.util.Log.i("NavegadorMedia3",
                        "YouTubeManifestosDeclarados: recebidos=${list.length()}, catalogados=$accepted")
                }.onFailure {
                    android.util.Log.w("NavegadorMedia3",
                        "Erro ao ler manifestos declarados: ${it.javaClass.simpleName}")
                }
            }
            after()
        }
    }

    private fun showYouTubeDetectedNotice() {
        val current = webView
        val signals = synchronized(mediaCandidateLock) {
            mediaNetworkSignals[current]?.toMap().orEmpty()
        }
        val summary = if (signals.isEmpty()) "Nenhum sinal de mídia observado." else
            signals.entries.joinToString("\n") { "${it.key}: ${it.value} requisição(ões)" }
        android.util.Log.i("NavegadorMedia3", "DiagnosticoYouTube: " +
            (if (signals.isEmpty()) "sem_sinais" else
                signals.entries.joinToString("; ") { "${it.key}=${it.value}" }))
        inspectYouTubePlayer(current) { dom ->
            if (isFinishing || isDestroyed || current !== webView) return@inspectYouTubePlayer
            android.util.Log.i("NavegadorMedia3", "DiagnosticoDOMYouTube: " +
                dom.replace('\n', ';'))
            AlertDialog.Builder(this).setTitle("Diagnóstico de vídeo YouTube")
                .setMessage("Rede (requisições observadas):\n$summary\n\n" +
                    "Player HTML5 (documento atual):\n$dom\n\n" +
                    "O diagnóstico não inspeciona iframes de outra origem, URLs privadas, " +
                    "respostas HTTP ou recursos blob internos. Não confirma fonte reproduzível no Media3.")
                .setPositiveButton("Voltar ao vídeo") { _, _ -> current.requestFocus() }
                .setNegativeButton("Fechar", null).show()
        }
    }

    private fun showRequestMediaCandidates() {
        val current = webView
        scanDeclaredMedia(current) {
            scanYouTubeDeclaredManifests(current) {
                if (current === webView && !isFinishing && !isDestroyed) showDetectedMediaDialog()
            }
        }
    }

    private fun showDetectedMediaDialog() {
        val found = synchronized(mediaCandidateLock) {
            mediaCandidates[webView]?.toList().orEmpty().reversed()
        }
        if (found.isEmpty() && youtubeVideoId(webView.url) != null) {
            showYouTubeDetectedNotice()
            return
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
    private lateinit var miniTitle: TextView
    private var miniController: MediaController? = null
    private var miniControllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val miniRefresh = object : Runnable {
        override fun run() {
            val c = miniController
            if (c != null && ::miniPlayerBar.isInitialized) {
                val active = c.currentMediaItem != null &&
                    c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                    c.playbackState != androidx.media3.common.Player.STATE_ENDED
                val display = playerDisplay.browserState(
                    active, playerPanel != null, VideoPipActivity.active)
                miniPlayerBar.visibility = if (display == PlayerDisplayCoordinator.Mode.MINI)
                    View.VISIBLE else View.GONE
                val trackTitle = c.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Mídia em reprodução"
                miniTitle.text = "▶ $trackTitle"
                miniTitle.contentDescription = "Abrir player: $trackTitle"
                miniPlayButton.text = if (c.isPlaying) "Pausar" else "Play"
                miniPlayButton.contentDescription = if (c.isPlaying) "Pausar reprodução"
                    else "Retomar reprodução"
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
    private val playerDisplay = PlayerDisplayCoordinator()
    private var instagramOverlay: LinearLayout? = null
    private var instagramOverlayExpanded = false
    private var instagramAudioButton: Button? = null
    private fun applyInstagramAudioIntent(view: WebView, soundOn: Boolean,
                                          callback: ((String?) -> Unit)? = null) {
        val enabled = if (soundOn) "true" else "false"
        view.evaluateJavascript("""(function(){
            const soundOn=$enabled;
            const pick=()=>{
                const candidates=[...document.querySelectorAll('video')]
                    .filter(v=>!v.ended)
                    .map(v=>{
                        const r=v.getBoundingClientRect();
                        const w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(0,r.left));
                        const h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(0,r.top));
                        return {v,area:w*h};
                    }).filter(x=>x.area>0).sort((a,b)=>b.area-a.area);
                return (candidates.find(x=>!x.v.paused)||candidates[0]||{}).v;
            };
            const existing=window.__nmInstagramAudioState;
            if(existing&&existing.interval)clearInterval(existing.interval);
            if(window.__nmInstagramAudioResumeCleanup)
                window.__nmInstagramAudioResumeCleanup();
            window.__nmInstagramAudioResumeCleanup=null;
            const state={soundOn,interval:null,checks:0,reassertions:0};
            window.__nmInstagramAudioState=state;
            const apply=()=>{
                if(window.__nmInstagramAudioState!==state||document.visibilityState==='hidden')return;
                const v=pick();
                if(!v)return;
                window.__nmInstagramActiveVideo=v;
                state.checks++;
                if(soundOn&&v.muted){v.muted=false;state.reassertions++;}
            };
            const current=pick();
            if(current){
                window.__nmInstagramActiveVideo=current;
                current.muted=!soundOn;
            }
            if(soundOn){state.interval=setInterval(apply,750);apply();}
            return JSON.stringify({status:'applied',soundOn,videoFound:!!current});
        })()""") { raw -> callback?.invoke(raw) }
    }
    private fun refreshInstagramAudioButton() {
        val enabled = getSharedPreferences("instagram_controls", MODE_PRIVATE)
            .getBoolean("sound_on", false)
        instagramAudioButton?.apply {
            text = if (enabled) "🔊" else "🔇"
            contentDescription = if (enabled)
                "Som ligado; tocar para silenciar e parar acompanhamento"
            else "Som silenciado; tocar para ativar acompanhamento"
        }
    }
    private fun updateInstagramOverlay() {
        val overlay = instagramOverlay ?: return
        val url = tabs.getOrNull(activeIndex)?.view?.url ?: tabs.getOrNull(activeIndex)?.url
        val host = websiteHost(url)
        val instagram = host == "instagram.com" || host?.endsWith(".instagram.com") == true
        overlay.visibility = if (instagram && siteDisplayMode(url) == "hybrid" &&
            playerPanel == null && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        (overlay.layoutParams as? FrameLayout.LayoutParams)?.let {
            val side = getSharedPreferences("instagram_controls", MODE_PRIVATE)
                .getString("side", "right")
            val vertical = getSharedPreferences("instagram_controls", MODE_PRIVATE)
                .getString("vertical", "center")
            it.gravity = (if (side == "left") android.view.Gravity.START else android.view.Gravity.END) or
                when (vertical) {
                    "top" -> android.view.Gravity.TOP
                    "bottom" -> android.view.Gravity.BOTTOM
                    else -> android.view.Gravity.CENTER_VERTICAL
                }
            it.topMargin = if (vertical == "top") (96 * resources.displayMetrics.density).toInt() else 0
            it.bottomMargin = if (vertical == "bottom") (96 * resources.displayMetrics.density).toInt() else 0
            val availableDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
            it.width = (if (instagramOverlayExpanded) (availableDp - 24).coerceIn(60, 224) else 60).let { width ->
                (width * resources.displayMetrics.density).toInt()
            }
            overlay.layoutParams = it
        }
    }

    private fun createInstagramOverlay() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(0xEE17202B.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF43566B.toInt())
            }
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        val pauseButton = Button(this).apply {
            text = "⏯"
            contentDescription = "Pausar ou retomar vídeo principal visível"
            setOnClickListener {
                if (Attention.checkNavigation(webView.url ?: "") != null) {
                    Toast.makeText(this@MainActivity, "Play bloqueado pelo Controle de Atenção",
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(this@MainActivity, "⏯ Comando recebido",
                    Toast.LENGTH_SHORT).show()
                // Reutiliza a publicação identificada pelo botão de áudio enquanto
                // ela continuar visível. Não escreve em muted nem em volume.
                val page = webView
                page.evaluateJavascript("""(function(){
                    const videos=[...document.querySelectorAll('video')]
                        .filter(v=>!v.ended)
                        .map(v=>{
                            const r=v.getBoundingClientRect();
                            const w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
                            const h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
                            const area=w*h;
                            const cx=(Math.max(0,r.left)+Math.min(innerWidth,r.right))/2;
                            const cy=(Math.max(0,r.top)+Math.min(innerHeight,r.bottom))/2;
                            const distance=Math.abs(cx-innerWidth/2)/Math.max(1,innerWidth)+
                                Math.abs(cy-innerHeight/2)/Math.max(1,innerHeight);
                            return {v,area,score:area/(1+distance)};
                        }).filter(x=>x.area>0).sort((a,b)=>b.score-a.score);
                    if(!videos.length)return JSON.stringify({status:'missing'});
                    const shared=window.__nmInstagramActiveVideo;
                    const selected=videos.find(x=>x.v===shared);
                    const v=(selected||videos[0]).v;
                    window.__nmInstagramActiveVideo=v;
                    const audit=window.__nmInstagramPlaybackAudit||
                        (window.__nmInstagramPlaybackAudit={next:1,ids:new WeakMap(),logs:[]});
                    if(!audit.ids.has(v))audit.ids.set(v,audit.next++);
                    const id=audit.ids.get(v);
                    const snapshot=()=>({paused:v.paused,muted:v.muted,volume:v.volume,
                        connected:v.isConnected,readyState:v.readyState,currentTime:v.currentTime});
                    const entry={id,at:Date.now(),action:v.paused?'play':'pause',
                        before:snapshot(),promise:'not_called',error:'',errorMessage:'',
                        visibility:document.visibilityState,
                        userActivation:navigator.userActivation?navigator.userActivation.isActive:null,
                        after:null,replaced:false};
                    audit.logs.push(entry);
                    if(audit.logs.length>40)audit.logs.shift();
                    const finish=()=>{entry.after=snapshot();entry.replaced=!v.isConnected||
                        window.__nmInstagramActiveVideo!==v;};
                    setTimeout(finish,700);
                    if(!v.paused){
                        try{v.pause();entry.promise='not_applicable';}
                        catch(e){entry.error=e&&e.name||'unknown';}
                        return JSON.stringify({status:v.paused?'paused':'ignored',id});
                    }
                    const probe={video:v,result:'pending',error:'',id};
                    window.__nmPlayProbe=probe;
                    try {
                        const promise=v.play();
                        if(promise && typeof promise.then==='function'){
                            promise.then(()=>{probe.result='resolved';entry.promise='resolved';},
                                e=>{probe.result='rejected';probe.error=e&&e.name?e.name:'unknown';
                                    entry.promise='rejected';entry.error=probe.error;
                                    entry.errorMessage=e&&e.message||'';});
                        } else {
                            probe.result='no_promise';
                            entry.promise='no_promise';
                        }
                        return JSON.stringify({status:'requested_play',id});
                    } catch(e) {
                        probe.result='threw';
                        probe.error=e&&e.name?e.name:'unknown';
                        entry.promise='threw';entry.error=probe.error;
                        entry.errorMessage=e&&e.message||'';
                        return JSON.stringify({status:'denied'});
                    }
                })()""") { raw ->
                    val state = runCatching {
                        org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
                            .optString("status")
                    }.getOrNull()
                    when (state) {
                        "missing" -> Toast.makeText(this@MainActivity,
                            "Vídeo não localizado no feed", Toast.LENGTH_SHORT).show()
                        "denied" -> Toast.makeText(this@MainActivity,
                            "Instagram recusou iniciar o vídeo", Toast.LENGTH_SHORT).show()
                        "paused" -> Toast.makeText(this@MainActivity,
                            "Vídeo pausado", Toast.LENGTH_SHORT).show()
                        "ignored" -> Toast.makeText(this@MainActivity,
                            "Instagram não aceitou pausar", Toast.LENGTH_SHORT).show()
                        "requested_play" -> page.postDelayed({
                            if (page !== webView) return@postDelayed
                            page.evaluateJavascript("""(function(){
                                const probe=window.__nmPlayProbe;
                                if(!probe||!probe.video.isConnected)
                                    return JSON.stringify({status:'changed'});
                                return JSON.stringify({
                                    status:probe.video.paused?'paused':'playing',
                                    promise:probe.result,error:probe.error,
                                    readyState:probe.video.readyState
                                });
                            })()""") { observed ->
                                val info = runCatching {
                                    org.json.JSONObject(org.json.JSONArray("[$observed]").getString(0))
                                }.getOrNull()
                                if (info?.optString("status") != "playing") {
                                    val message = when (info?.optString("promise")) {
                                        "rejected", "threw" -> "Play recusado: ${info.optString("error")}"
                                        "resolved" -> "Play aceito, mas Instagram pausou o vídeo"
                                        else -> if (info?.optString("status") == "changed")
                                            "Instagram substituiu o vídeo"
                                        else "Vídeo permanece pausado (Play pendente)"
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }, 700L)
                        else -> Toast.makeText(this@MainActivity,
                            "Falha ao interpretar retorno do player: " +
                                (raw?.take(90) ?: "sem resposta"),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val volumeButton = Button(this).apply {
            text = "🔊"
            contentDescription = "Abrir ajuste de volume ou deslizar verticalmente"
        }
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        val percent = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
        }
        val slider = android.widget.SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            contentDescription = "Nível do volume da mídia"
        }
        val muteButton = Button(this).apply {
            text = "Silenciar"
            setOnClickListener {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                slider.progress = 0
            }
        }
        // O diagnóstico confirmou vídeo visível com muted=true e volume HTML5=100%.
        // A ação é explícita: não liga o áudio automaticamente ao arrastar a barra,
        // não dispara play e não interfere nos cliques ou no scroll do Instagram.
        // Depuração somente leitura: não altera muted, volume, reprodução nem eventos.
        val debugButton = Button(this).apply {
            text = "Depurar vídeo"
            contentDescription = "Inspecionar estado de áudio e reprodução do Instagram"
            setOnClickListener {
                val currentUrl = webView.url
                val host = websiteHost(currentUrl)
                if ((host != "instagram.com" && host?.endsWith(".instagram.com") != true) ||
                    siteDisplayMode(currentUrl) != "hybrid") {
                    Toast.makeText(this@MainActivity, "Disponível no Instagram híbrido",
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val volumeAndroid = 100 * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume
                val diagnosticScript = """(function(){
                    const clips=[...document.querySelectorAll('video')];
                    const visible=clips.map((v,index)=>{
                        const rect=v.getBoundingClientRect();
                        const width=Math.max(0,Math.min(rect.right,innerWidth)-Math.max(rect.left,0));
                        const height=Math.max(0,Math.min(rect.bottom,innerHeight)-Math.max(rect.top,0));
                        return {v,index,area:width*height};
                    }).filter(x=>x.area>0).sort((a,b)=>b.area-a.area);
                    const item=visible[0],v=item&&item.v;
                    return JSON.stringify({
                        total:clips.length,visible:visible.length,selected:item?item.index:-1,
                        paused:v?v.paused:null,muted:v?v.muted:null,
                        defaultMuted:v?v.defaultMuted:null,
                        volume:v?Math.round(v.volume*100):null,
                        readyState:v?v.readyState:null,
                        currentTime:v?Math.round(v.currentTime*10)/10:null,
                        ended:v?v.ended:null,frames:document.querySelectorAll('iframe').length
                    });
                })()"""
                fun parseResult(raw: String?): org.json.JSONObject? = runCatching {
                    org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
                }.getOrNull()
                webView.evaluateJavascript(diagnosticScript) { firstRaw ->
                    val first = parseResult(firstRaw)
                    // Um segundo retrato permite distinguir vídeo parado e reprodução real.
                    webView.postDelayed({
                        if (webView.url != currentUrl) return@postDelayed
                        webView.evaluateJavascript(diagnosticScript) { lastRaw ->
                            val latest = parseResult(lastRaw)
                            fun field(key: String) = latest?.optString(key, "indisponível") ?: "indisponível"
                            val before = first?.optDouble("currentTime", Double.NaN) ?: Double.NaN
                            val after = latest?.optDouble("currentTime", Double.NaN) ?: Double.NaN
                            val report = buildString {
                                appendLine("Depuração Instagram · modo híbrido")
                                appendLine("Volume Android: $volumeAndroid%")
                                appendLine("Vídeos DOM: ${field("total")}; visíveis: ${field("visible")}")
                                appendLine("Índice do vídeo visível: ${field("selected")}")
                                appendLine("Pausado: ${field("paused")}; finalizado: ${field("ended")}")
                                appendLine("Mute HTML5: ${field("muted")}")
                                appendLine("Mute padrão: ${field("defaultMuted")}")
                                appendLine("Volume HTML5: ${field("volume")}%")
                                appendLine("ReadyState: ${field("readyState")}")
                                appendLine("Iframes: ${field("frames")}")
                                appendLine("Tempo do vídeo: ${field("currentTime")} s")
                                appendLine("Avançou em 1 segundo: " +
                                    if (before.isNaN() || after.isNaN()) "indisponível"
                                    else if (after > before) "sim" else "não")
                                appendLine("Nota: inspeção de vídeos no documento principal apenas.")
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Modo de depuração · Instagram")
                                .setMessage(report)
                                .setPositiveButton("Fechar", null)
                                .setNeutralButton("Copiar") { _, _ ->
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "Depuração Instagram", report))
                                }.show()
                        }
                    }, 1000L)
                }
            }
        }
        val eventMonitorButton = Button(this).apply {
            text = "Monitorar / parar"
            contentDescription = "Iniciar ou ler depuração passiva por vídeo"
            setOnClickListener {
                val host = websiteHost(webView.url)
                if ((host != "instagram.com" && host?.endsWith(".instagram.com") != true) ||
                    siteDisplayMode(webView.url) != "hybrid") {
                    Toast.makeText(this@MainActivity, "Abra o Instagram híbrido", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                webView.evaluateJavascript("""(function(){
                    if(window.nmVideoAudit){
                        const state=window.nmVideoAudit;
                        clearInterval(state.interval);
                        const commands=(window.__nmInstagramPlaybackAudit||{}).logs||[];
                        const result=JSON.stringify({events:state.logs,commands});
                        delete window.nmVideoAudit;
                        return result;
                    }
                    const sharedIds={next:1,ids:new WeakMap(),logs:[]};
                    const state={started:Date.now(),logs:[],last:new Map()};
                    window.nmVideoAudit=state;
                    window.__nmInstagramPlaybackAudit=sharedIds;
                    const identity=v=>{
                        if(!sharedIds.ids.has(v))sharedIds.ids.set(v,sharedIds.next++);
                        return sharedIds.ids.get(v);
                    };
                    const snapshot=v=>{
                        const r=v.getBoundingClientRect();
                        const w=Math.max(0,Math.min(innerWidth,r.right)-Math.max(0,r.left));
                        const h=Math.max(0,Math.min(innerHeight,r.bottom)-Math.max(0,r.top));
                        return [v.muted,Math.round(v.volume*100),v.paused,Math.round(w*h),v.readyState];
                    };
                    const log=(kind,v)=>{
                        const id=identity(v),now=snapshot(v);
                        state.logs.push([Date.now()-state.started,id,kind,...now]);
                        if(state.logs.length>100)state.logs.shift();
                        state.last.set(id,now);
                    };
                    const attached=new WeakSet();
                    const scan=()=>{
                        document.querySelectorAll('video').forEach(v=>{
                            const id=identity(v);
                            if(!attached.has(v)){
                                attached.add(v);
                                log('detectado',v);
                                ['volumechange','play','playing','pause','ended']
                                    .forEach(name=>v.addEventListener(name,()=>log(name,v),{passive:true}));
                            }
                            const now=snapshot(v),before=state.last.get(id);
                            if(before && (now[0]!==before[0]||now[1]!==before[1]||now[2]!==before[2]))
                                log('mudanca',v);
                            else state.last.set(id,now);
                        });
                    };
                    scan();
                    state.interval=setInterval(scan,250);
                    setTimeout(()=>clearInterval(state.interval),30000);
                    return 'started';
                })()""") { raw ->
                    val output = runCatching {
                        org.json.JSONArray("[$raw]").getString(0)
                    }.getOrDefault("Sem dados")
                    val message = if (output == "started") {
                        "Monitor iniciado por 30 segundos. Use Play/Pause e o botão de " +
                            "som; depois toque em Monitorar / parar para copiar os eventos " +
                            "e os comandos com estado anterior/posterior do mesmo vídeo."
                    } else output
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Monitor por vídeo · Instagram")
                        .setMessage(message)
                        .setPositiveButton("Fechar", null)
                        .setNeutralButton("Copiar") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                "Monitor por vídeo Instagram", message))
                        }.show()
                }
            }
        }
        val soundToggleButton = Button(this).apply {
            text = "🔈"
            contentDescription = "Alternar som do vídeo visível sem iniciar ou pausar reprodução"
            setOnClickListener {
                val currentUrl = webView.url
                val host = websiteHost(currentUrl)
                if ((host != "instagram.com" && host?.endsWith(".instagram.com") != true) ||
                    siteDisplayMode(currentUrl) != "hybrid" ||
                    Attention.checkNavigation(currentUrl ?: "") != null) {
                    Toast.makeText(this@MainActivity, "Controle de som indisponível neste momento",
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // A intenção fica salva fora do documento para sobreviver ao F5.
                val page = webView
                val prefs = getSharedPreferences("instagram_controls", MODE_PRIVATE)
                val soundEnabled = !prefs.getBoolean("sound_on", false)
                applyInstagramAudioIntent(page, soundEnabled) { raw ->
                    val result = runCatching {
                        org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
                    }.getOrNull()
                    if (result?.optString("status") != "applied") {
                        Toast.makeText(this@MainActivity,
                            "Falha ao configurar o áudio do Instagram",
                            Toast.LENGTH_LONG).show()
                        return@applyInstagramAudioIntent
                    }
                    prefs.edit().putBoolean("sound_on", soundEnabled).apply()
                    refreshInstagramAudioButton()
                    Toast.makeText(this@MainActivity,
                        if (soundEnabled) "Som ligado, inclusive após recarregar"
                        else "Som silenciado e acompanhamento desligado",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        instagramAudioButton = soundToggleButton
        refreshInstagramAudioButton()
        val debugSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val debugToggle = Button(this).apply {
            text = "⚙ Diagnóstico avançado"
            setOnClickListener {
                debugSection.visibility = if (debugSection.visibility == View.VISIBLE)
                    View.GONE else View.VISIBLE
            }
        }
        val sideButton = Button(this).apply {
            text = "↔ Mudar lateral"
            contentDescription = "Mover controles para a outra lateral"
            setOnClickListener {
                val prefs = getSharedPreferences("instagram_controls", MODE_PRIVATE)
                val next = if (prefs.getString("side", "right") == "right") "left" else "right"
                prefs.edit().putString("side", next).apply()
                updateInstagramOverlay()
            }
        }
        val positionButton = Button(this).apply {
            text = "↕ Mudar altura"
            contentDescription = "Alternar posição superior, central e inferior dos controles"
            setOnClickListener {
                val prefs = getSharedPreferences("instagram_controls", MODE_PRIVATE)
                val next = when (prefs.getString("vertical", "center")) {
                    "center" -> "top"
                    "top" -> "bottom"
                    else -> "center"
                }
                prefs.edit().putString("vertical", next).apply()
                updateInstagramOverlay()
            }
        }
        val collapseButton = Button(this).apply {
            text = "⌃ Recolher painel"
            contentDescription = "Recolher os controles do Instagram"
        }
        lateinit var scroll: ScrollView
        fun setExpanded(expanded: Boolean) {
            instagramOverlayExpanded = expanded
            detail.visibility = View.VISIBLE
            scroll.visibility = if (expanded) View.VISIBLE else View.GONE
            updateInstagramOverlay()
        }
        collapseButton.setOnClickListener { setExpanded(false) }
        fun refreshVolume() {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            slider.max = max
            slider.progress = current
            val value = current * 100 / max
            percent.text = "$value%"
            volumeButton.text = if (current == 0) "🔇" else "🔊"
            volumeButton.contentDescription = "Volume $value por cento"
        }
        fun expand() {
            refreshVolume()
            setExpanded(true)
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                }
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                percent.text = "${value * 100 / max}%"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) { expand() }
        })
        detail.addView(collapseButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(percent, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        detail.addView(muteButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(sideButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(positionButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(debugToggle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        debugSection.addView(debugButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        debugSection.addView(eventMonitorButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        detail.addView(debugSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val stepHeight = dp(160).toFloat()
        var startY = 0f
        var initial = 0
        var dragging = false
        volumeButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    initial = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    dragging = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = startY - event.rawY
                    if (kotlin.math.abs(delta) > dp(8)) dragging = true
                    if (dragging) {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val target = (initial + delta / stepHeight * max).toInt().coerceIn(0, max)
                        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        expand()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) expand() else expand()
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
        column.addView(pauseButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        column.addView(soundToggleButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        column.addView(volumeButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.48f).toInt()
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            detail.visibility = View.VISIBLE
            addView(detail, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            visibility = View.GONE
        }
        // A área rolável pertence ao painel expandido, não ao feed do Instagram.
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        playerLayer.addView(column, FrameLayout.LayoutParams(dp(60), WRAP_CONTENT,
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        instagramOverlay = column
        updateInstagramOverlay()
    }

    private lateinit var playerLayer: FrameLayout
    private var playerPanel: FrameLayout? = null
    private var nativePlayerClose: (() -> Unit)? = null
    private var playerControls: View? = null
    private var playerIsImmersive = false
    private fun closeNativePlayer() { nativePlayerClose?.invoke() }

    /** Interface de vídeo vinculada à sessão que já toca áudio em segundo plano. */
    private fun showNativeVideoPlayer() {
        // Nunca disputar a superfície com o PiP independente.
        if (!playerDisplay.shouldOpenFullscreen(VideoPipActivity.active)) return
        if (playerPanel != null) { playerControls?.visibility = View.VISIBLE; return }
        val panel = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x55000000)
        }
        val videoStage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = android.view.SurfaceView(this)
        videoStage.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT,
            android.view.Gravity.CENTER))
        panel.addView(videoStage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val mediaTitle = TextView(this).apply {
            text = "Reprodução nativa"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        outer.addView(mediaTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val info = TextView(this).apply {
            text = "Conectando ao player..."
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        outer.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val progress = android.widget.SeekBar(this).apply {
            minHeight = dp(48)
            contentDescription = "Posição da reprodução"
            isEnabled = false
        }
        val timeLabel = TextView(this).apply {
            text = "00:00 / --:--"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val back = uiAction("−10 s")
        val play = uiAction("Reproduzir", true)
        val forward = uiAction("+10 s")
        val rotate = uiAction("Girar")
        val fullscreenButton = uiAction("Tela cheia")
        val pipButton = uiAction("PiP")
        val close = uiAction("Fechar")
        val navControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val displayControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        close.contentDescription = "Fechar player e voltar ao navegador"
        rotate.contentDescription = "Girar orientação do vídeo"
        fullscreenButton.contentDescription = "Alternar modo de tela cheia"
        pipButton.contentDescription = "Abrir vídeo em janela flutuante PiP"
        // Duas ações por faixa em todas as orientações: nunca comprime os
        // rótulos ao girar a Activity, que mantém configChanges ligado.
        listOf(close, rotate).forEach { navControls.addView(it,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }) }
        val navSecondRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(fullscreenButton, pipButton).forEach { navSecondRow.addView(it,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }) }
        outer.addView(navControls, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        outer.addView(navSecondRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        outer.addView(displayControls, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        // Controles de recuperação só aparecem durante espera prolongada ou falha.
        val recoveryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        val retryLoadButton = Button(this).apply {
            text = "Recarregar mídia"
            contentDescription = "Reiniciar carregamento na posição atual"
        }
        val refreshYoutubeButton = Button(this).apply {
            text = "Renovar fonte YouTube"
            contentDescription = "Obter nova URL e PO Token preservando a posição"
            visibility = View.GONE
        }
        recoveryRow.addView(retryLoadButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        recoveryRow.addView(refreshYoutubeButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        outer.addView(recoveryRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        listOf(back, play, forward).forEach { controls.addView(it,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        back.contentDescription = "Voltar 10 segundos"
        forward.contentDescription = "Avançar 10 segundos"
        // Hierarquia do player: contador, ajuste do tempo e somente depois
        // retroceder / pausar / avançar. Vale para vertical e horizontal.
        outer.addView(timeLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        outer.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(outer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Aviso independente dos controles que podem sumir na tela cheia.
        val attentionBanner = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xAA202B3A.toInt())
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setPadding(12, 8, 12, 8)
            visibility = View.GONE
        }
        panel.addView(attentionBanner, FrameLayout.LayoutParams(
            MATCH_PARENT, WRAP_CONTENT, android.view.Gravity.TOP))
        playerLayer.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        playerPanel = panel
        updateInstagramOverlay()
        // Android 12+: ao ir para Home, a tela do player vira PiP automaticamente.
        // O navegador sozinho nunca aciona a autoentrada.
        updatePipAutoEnter(true)
        miniPlayerBar.visibility = View.GONE
        playerControls = outer
        browserBar.visibility = View.GONE
        statusView.visibility = View.GONE
        val token = SessionToken(this, ComponentName(this, Media3PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        var player: MediaController? = null
        var playbackIssue: String? = null
        var lastAttentionPage: String? = null
        var attentionReminderShown = false
        var lastAttentionReminderPage: String? = null
        var seeking = false
        var closed = false
        var refreshingYoutube = false
        val hideAttentionReminder = Runnable {
            if (playerPanel === panel && !closed) attentionBanner.visibility = View.GONE
        }
        var videoFullscreen = false
        var playerLandscape = false
        var wideNav: Boolean? = null
        val originalOrientation = requestedOrientation
        var videoWidth = 0
        var videoHeight = 0
        var filling = false
        fun layoutPlayerForWindow() {
            val width = panel.width
            val height = panel.height
            if (width <= 0 || height <= 0) return
            val overlay = width > height || videoFullscreen || isInPictureInPictureMode
            playerLandscape = overlay
            if (!overlay && outer.visibility == View.GONE) outer.visibility = View.VISIBLE
            if (wideNav != overlay) {
                wideNav = overlay
                navControls.removeAllViews()
                navSecondRow.removeAllViews()
                val first = if (overlay) listOf(close, rotate, fullscreenButton, pipButton)
                    else listOf(close, rotate)
                first.forEach { navControls.addView(it,
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginStart = dp(2); marginEnd = dp(2)
                    }) }
                navSecondRow.visibility = if (overlay) View.GONE else View.VISIBLE
                if (!overlay) listOf(fullscreenButton, pipButton).forEach {
                    navSecondRow.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginStart = dp(2); marginEnd = dp(2)
                    })
                }
            }
            val stageHeight = if (overlay) height else
                minOf(width * 9 / 16, (height - dp(350)).coerceAtLeast(dp(100)))
                    .coerceIn(1, height)
            val stageParams = videoStage.layoutParams as FrameLayout.LayoutParams
            if (stageParams.height != stageHeight || stageParams.width != MATCH_PARENT) {
                videoStage.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, stageHeight,
                    android.view.Gravity.TOP)
            }
            val controlsHeight = if (overlay) height else (height - stageHeight).coerceAtLeast(1)
            val controlsTop = if (overlay) 0 else stageHeight
            val controlsParams = outer.layoutParams as FrameLayout.LayoutParams
            if (controlsParams.height != controlsHeight || controlsParams.topMargin != controlsTop) {
                outer.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, controlsHeight).apply {
                    topMargin = controlsTop
                }
            }
            outer.setBackgroundColor(if (overlay) 0x55000000 else 0xFF101820.toInt())
        }
        fun updateVideoAspect() {
            val width = videoStage.width
            val height = videoStage.height
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
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPlayerForWindow() }
        videoStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoAspect() }
        panel.post { layoutPlayerForWindow() }
        val attentionButton = uiAction("Tempo").apply {
            contentDescription = "Consultar tempo do Controle de Atenção"
            setOnClickListener {
                val page = player?.currentMediaItem?.mediaMetadata?.extras
                    ?.getString("nm_attention_page") ?: lastAttentionPage
                val status = Attention.status(page)
                val remaining = Attention.mediaRemainingSeconds(page)
                val message = if (status == null) "Esta mídia não possui limite de atenção configurado."
                else {
                    val daily = if (status.site.dailyMinutes > 0)
                        "${AttentionLogic.formatTime(status.usedSeconds)} de ${status.site.dailyMinutes} min hoje"
                    else "Sem limite diário"
                    val pauseRemaining = Attention.pauseRemainingSeconds(page)
                    val left = if (pauseRemaining > 0L)
                        "Pausa restante: ${AttentionLogic.formatTime(pauseRemaining)}"
                    else if (Attention.checkNavigation(page ?: "") != null)
                        "Acesso limitado. Confira o horário ou limite diário."
                    else if (remaining != null)
                        "Tempo disponível: ${AttentionLogic.formatTime(remaining)}"
                    else "Sem limite de tempo para a sessão"
                    "${status.site.domain}\n$daily\n$left"
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Controle de Atenção")
                    .setMessage(message).setPositiveButton("OK", null).show()
            }
        }
        displayControls.addView(attentionButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
        val aspectButton = uiAction("Ajustar")
        displayControls.addView(aspectButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
        val queueButton = uiAction("Fila").apply {
            contentDescription = "Consultar ou limpar a fila de reprodução"
            setOnClickListener { showNativeQueue() }
        }
        displayControls.addView(queueButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(2); marginEnd = dp(2)
        })
        // Alterar o enquadramento nunca deve reativar áudio de uma sessão limitada.
        fun enforceAttentionForPlayerControls(): Boolean {
            val page = player?.currentMediaItem?.mediaMetadata?.extras
                ?.getString("nm_attention_page") ?: lastAttentionPage
            if (page.isNullOrBlank() || Attention.checkNavigation(page) == null) return false
            player?.let { c ->
                if (c.mediaItemCount > 0) {
                    Media3PlaybackService.rememberPositionBeforeAttentionBlock()
                    c.stop()
                    c.clearMediaItems()
                }
            }
            // Sites podem retomar HTML5 após mudanças de layout/orientação.
            // Pausa os elementos acessíveis na WebView do site limitado.
            tabs.forEach { tab ->
                if (Attention.status(tab.view.url)?.site?.id == Attention.status(page)?.site?.id) {
                    tab.view.evaluateJavascript(
                        "(function(){for(const m of document.querySelectorAll('video,audio'))" +
                            "{try{m.pause()}catch(e){}}})()", null)
                }
            }
            return true
        }
        aspectButton.setOnClickListener {
            enforceAttentionForPlayerControls()
            filling = !filling
            aspectButton.text = if (filling) "Preencher" else "Ajustar"
            updateVideoAspect()
            // Confere de novo depois que a SurfaceView atualizar o enquadramento.
            panel.postDelayed({ if (!closed) enforceAttentionForPlayerControls() }, 250L)
        }
        val hideControls = Runnable { if (!closed && playerLandscape && player?.isPlaying == true &&
            !seeking && !isInPictureInPictureMode) outer.visibility = View.GONE }
        fun showControls() {
            if (!isInPictureInPictureMode) outer.visibility = View.VISIBLE
            handler.removeCallbacks(hideControls)
            if (playerLandscape && !videoFullscreen && player?.isPlaying == true)
                handler.postDelayed(hideControls, 3500L)
        }
        fun setVideoFullscreen(enabled: Boolean) {
            videoFullscreen = enabled
            layoutPlayerForWindow()
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
        outer.setOnClickListener {
            if (!playerLandscape) return@setOnClickListener
            if (outer.visibility == View.VISIBLE) {
                outer.visibility = View.GONE
                handler.removeCallbacks(hideControls)
            } else showControls()
        }

        retryLoadButton.setOnClickListener {
            val c = player ?: return@setOnClickListener
            if (enforceAttentionForPlayerControls() || c.currentMediaItem == null) return@setOnClickListener
            val position = c.currentPosition.coerceAtLeast(0L)
            c.stop()
            c.seekTo(position)
            c.prepare()
            c.play()
            playbackIssue = "Recarregando a mídia em ${position / 1000}s…"
            showControls()
        }
        refreshYoutubeButton.setOnClickListener {
            val c = player ?: return@setOnClickListener
            val original = c.currentMediaItem ?: return@setOnClickListener
            val extras = original.mediaMetadata.extras
            val page = extras?.getString("nm_attention_page")
            val videoId = extras?.getString("nm_youtube_video_id") ?: youtubeVideoId(page)
            if (videoId == null || refreshingYoutube ||
                enforceAttentionForPlayerControls()) return@setOnClickListener
            val previousUri = original.localConfiguration?.uri ?: return@setOnClickListener
            refreshingYoutube = true
            refreshYoutubeButton.isEnabled = false
            playbackIssue = "Renovando URL e PO Token do YouTube…"
            showControls()
            Thread {
                val canonical = "https://www.youtube.com/watch?v=$videoId"
                val fresh = runCatching {
                    val fromPage = page?.let { runCatching {
                        YoutubeExtractor.extractWithReport(it)
                    }.getOrNull() }
                    if (!isYouTubeMusic(page) && fromPage != null && fromPage.sources.isNotEmpty())
                        fromPage
                    else YoutubeExtractor.extractWithReport(canonical)
                }
                runOnUiThread {
                    if (closed || isDestroyed || isFinishing) return@runOnUiThread
                    refreshingYoutube = false
                    refreshYoutubeButton.isEnabled = true
                    if (c.currentMediaItem?.localConfiguration?.uri != previousUri) return@runOnUiThread
                    val sources = fresh.getOrNull()?.sources.orEmpty().filter { it.playable }
                    val oldItag = previousUri.getQueryParameter("itag")
                    val chosen = sources.firstOrNull { oldItag != null &&
                        Uri.parse(it.url).getQueryParameter("itag") == oldItag } ?: sources.firstOrNull {
                        isYouTubeMusic(page) && it.label.startsWith("Áudio")
                    } ?: sources.firstOrNull {
                        it.label.startsWith("Vídeo + áudio")
                    } ?: sources.firstOrNull()
                    if (chosen == null) {
                        playbackIssue = "Não foi obtida uma nova fonte autorizada."
                    } else {
                        val position = c.currentPosition.coerceAtLeast(0L)
                        val renewed = original.buildUpon().setUri(chosen.url).build()
                        c.setMediaItem(renewed, position)
                        c.prepare()
                        c.play()
                        playbackIssue = "Fonte renovada · retomando em ${position / 1000}s…"
                    }
                    showControls()
                }
            }.start()
        }

        val update = object : Runnable {
            override fun run() {
                val c = player
                if (!closed && c != null) {
                    val currentAttentionPage = c.currentMediaItem?.mediaMetadata?.extras
                        ?.getString("nm_attention_page")
                    if (!currentAttentionPage.isNullOrBlank()) lastAttentionPage = currentAttentionPage
                    // O serviço remove a mídia no limite. Conservar a origem na UI
                    // evita que o aviso de bloqueio desapareça junto com o item.
                    val attentionPage = currentAttentionPage ?: lastAttentionPage
                    val attentionStatus = Attention.status(attentionPage)
                    val secondsLeft = Attention.mediaRemainingSeconds(attentionPage)
                    // Aviso único e temporário enquanto há tempo;
                    // ao atingir o limite, mantém a mensagem de bloqueio.
                    val nearEnd = secondsLeft != null && secondsLeft in 1L..300L
                    val ended = secondsLeft == 0L && attentionStatus != null
                    if (attentionPage != lastAttentionReminderPage) {
                        lastAttentionReminderPage = attentionPage
                        attentionReminderShown = false
                        handler.removeCallbacks(hideAttentionReminder)
                        attentionBanner.visibility = View.GONE
                    }
                    if (ended) {
                        handler.removeCallbacks(hideAttentionReminder)
                        val pauseRemaining = Attention.pauseRemainingSeconds(attentionPage)
                        attentionBanner.text = if (pauseRemaining > 0L)
                            "☕ Pausa em andamento · faltam ${AttentionLogic.formatTime(pauseRemaining)}"
                        else "☕ Controle de Atenção · acesso temporariamente limitado."
                        attentionBanner.visibility = View.VISIBLE
                    } else if (nearEnd && !attentionReminderShown &&
                        c.currentMediaItem != null && c.isPlaying) {
                        attentionReminderShown = true
                        attentionBanner.text =
                            "☕ Sua pausa está se aproximando. Aproveite para concluir o vídeo."
                        attentionBanner.visibility = View.VISIBLE
                        handler.removeCallbacks(hideAttentionReminder)
                        handler.postDelayed(hideAttentionReminder, 5000L)
                    } else if (!nearEnd) {
                        handler.removeCallbacks(hideAttentionReminder)
                        attentionBanner.visibility = View.GONE
                        attentionReminderShown = false
                    }
                    val duration = c.duration
                    mediaTitle.text = c.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?.takeIf { it.isNotBlank() } ?: "Reprodução nativa"
                    val pos = c.currentPosition.coerceAtLeast(0L)
                    val total = if (duration > 0L && duration != androidx.media3.common.C.TIME_UNSET)
                        duration else 0L
                    if (!seeking) {
                        progress.max = if (total > 0L) 1000 else 0
                        progress.progress = if (total > 0L)
                            ((pos.coerceAtMost(total) * 1000L) / total).toInt() else 0
                    }
                    progress.isEnabled = total > 0L && !ended
                    fun clock(ms: Long): String {
                        val sec = ms.coerceAtLeast(0L) / 1000L
                        return "%02d:%02d".format(sec / 60, sec % 60)
                    }
                    timeLabel.text = if (total > 0L) "${clock(pos)} / ${clock(total)}"
                        else "${clock(pos)} / --:--"
                    progress.contentDescription = if (total > 0L)
                        "Posição da reprodução: ${clock(pos)} de ${clock(total)}"
                        else "Reprodução ao vivo ou duração desconhecida"
                    val statusText = if (ended)
                        "Controle de Atenção: acesso temporariamente limitado"
                    else playbackIssue ?: Media3PlaybackService.bufferingNotice() ?:
                        ""
                    info.text = statusText
                    info.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
                    val stalled = Media3PlaybackService.bufferingNotice() != null
                    val showRecovery = !ended && c.currentMediaItem != null &&
                        (stalled || c.playerError != null)
                    recoveryRow.visibility = if (showRecovery) View.VISIBLE else View.GONE
                    if (showRecovery && outer.visibility != View.VISIBLE &&
                        !isInPictureInPictureMode) {
                        outer.visibility = View.VISIBLE
                        handler.removeCallbacks(hideControls)
                    }
                    refreshYoutubeButton.visibility = if (showRecovery &&
                        (c.currentMediaItem?.mediaMetadata?.extras
                            ?.getString("nm_youtube_video_id") != null ||
                            youtubeVideoId(c.currentMediaItem?.mediaMetadata?.extras
                                ?.getString("nm_attention_page")) != null))
                        View.VISIBLE else View.GONE
                    play.text = when {
                        c.playerError != null -> "Tentar novamente"
                        c.isPlaying -> "Pausar"
                        else -> "Reproduzir"
                    }
                    play.contentDescription = when {
                        c.playerError != null -> "Tentar reproduzir novamente"
                        c.isPlaying -> "Pausar reprodução"
                        else -> "Iniciar ou retomar reprodução"
                    }
                    if (playerLandscape && c.isPlaying && outer.visibility == View.VISIBLE &&
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
            player?.let { c ->
                if (c.playerError != null) {
                    playbackIssue = null
                    c.prepare()
                    c.play()
                } else if (c.isPlaying) c.pause() else c.play()
            }
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
            enforceAttentionForPlayerControls()
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        fullscreenButton.setOnClickListener {
            enforceAttentionForPlayerControls()
            setVideoFullscreen(!videoFullscreen)
        }
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
                handler.removeCallbacks(hideAttentionReminder)
                player?.let { c -> runCatching { c.clearVideoSurfaceView(surface) } }
                MediaController.releaseFuture(future)
                playerLayer.removeView(panel)
                playerPanel = null
                updateInstagramOverlay()
                updatePipAutoEnter(false)
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
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            playbackIssue = when {
                                error.errorCodeName.contains("NETWORK") ->
                                    "Falha de conexão. Toque em Tentar novamente."
                                error.errorCodeName.contains("DECOD") ->
                                    "Formato não suportado pelo dispositivo."
                                else -> "Falha na reprodução. Toque em Tentar novamente."
                            }
                            outer.visibility = View.VISIBLE
                            handler.removeCallbacks(hideControls)
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_READY)
                                playbackIssue = null
                        }
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

    private fun startMedia3(url: String, mimeType: String? = null, youtubeId: String? = null) {
        val u = Uri.parse(url)
        if (u.scheme !in listOf("http", "https") || u.host.isNullOrBlank()) return
        val sourceView = webView
        Attention.checkNavigation(sourceView.url ?: "")?.let { blocked ->
            Toast.makeText(this, "Controle de Atenção: " + blocked.reason, Toast.LENGTH_LONG).show()
            showBlocked(blocked)
            return
        }
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
                            if (youtubeVideoId(sourceView.url) != null)
                                youtubeMedia3Result = "Reproduzindo no player nativo"
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
                            val visited = HashSet<Throwable>()
                            var cause: Throwable? = error
                            var httpCode: Int? = null
                            while (cause != null && visited.add(cause)) {
                                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                    httpCode = cause.responseCode
                                    break
                                }
                                cause = cause.cause
                            }
                            // O serviço recebe a resposta HTTP diretamente, mesmo
                            // quando a MediaSession não preserva a cadeia de causas.
                            val status = httpCode ?: Media3PlaybackService.latestPlaybackHttpStatus()
                            if (youtubeVideoId(sourceView.url) != null) {
                                youtubeMedia3Result = "Erro ${error.errorCodeName} · " +
                                    "HTTP ${status ?: "indisponível"}"
                                val signals = YoutubeExtractor.inspectSourceSignals(url)
                                android.util.Log.i("NavegadorMedia3", "YouTubeHTTPMedia3=" +
                                    "${status ?: "indisponível"}; " +
                                    signals.description().replace('\n', ';'))
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Resultado real · Media3")
                                    .setMessage("Erro do player: ${error.errorCodeName}\n" +
                                        "HTTP recebido pelo Media3: ${status ?: "indisponível"}.\n" +
                                        "Este resultado é independente da sonda inicial.\n\n" +
                                        signals.description() + "\n\n" +
                                        "A reprodução da página continua como alternativa " +
                                        "sem transferir credenciais ao Media3.")
                                    .setPositiveButton("Retomar na página") { _, _ ->
                                        if (!isDestroyed && tabs.any { it.view === sourceView }) {
                                            sourceView.evaluateJavascript("""(function(){
                                                const video = [...document.querySelectorAll('video')]
                                                    .find(v => v.currentSrc && !v.ended);
                                                if (!video) return 'sem_video';
                                                if (!video.paused) return 'reproduzindo';
                                                video.play().catch(() => {});
                                                return 'solicitado';
                                            })()""") { result ->
                                                if (!isDestroyed) Toast.makeText(this@MainActivity,
                                                    if (result == "\"reproduzindo\"") "Vídeo já ativo na página"
                                                    else "Reprodução solicitada; use o Play da página se necessário",
                                                    Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }.setNegativeButton("Fechar", null).show()
                            } else {
                                Toast.makeText(this@MainActivity,
                                    "Erro Media3: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                            }
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
                        putString("nm_attention_page", sourceView.url ?: "")
                        youtubeId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }?.let {
                            putString("nm_youtube_video_id", it)
                        }
                    }
                    val item = MediaItem.Builder().setUri(u)
                        .apply { if (mimeType != null) setMimeType(mimeType) }
                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
                            // Metadados usados pelo cartão de mídia do Android (API 34+).
                            // Os botões Play/Pause são comandos da MediaSession, não atalhos da Activity.
                            .setTitle(sourceView.title?.take(90)?.takeIf { it.isNotBlank() }
                                ?: "Vídeo do NavegadorMaluco")
                            .setArtist("NavegadorMaluco")
                            .setIsPlayable(true)
                            .setExtras(mediaInfo).build())
                        .build()
                    // Ao escolher novamente o vídeo da página, prioriza o tempo
                    // já transferido da WebView; caso contrário recupera histórico.
                    val savedPosition = PlayerHistory(this).position(sourceView.url ?: "")
                    val resumeAt = if (position > 0L) position else savedPosition
                    controller.setMediaItem(item, resumeAt.coerceAtLeast(0L))
                    controller.prepare()
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
            styleInput(this)
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
        val hasMedia = miniController?.currentMediaItem != null
        val supported = pipAvailable()
        if (!playerDisplay.canOpenPip(hasMedia, supported, VideoPipActivity.active)) {
            val reason = when {
                VideoPipActivity.active -> "O vídeo já está em uma janela PiP"
                !supported -> "Este Android não oferece Picture-in-Picture"
                else -> "Abra um vídeo no Media3 antes de ativar PiP"
            }
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            return
        }
        // A Activity do navegador NÃO entra em PiP: só a Activity exclusiva do vídeo.
        // A sessão Media3 continua tocando enquanto a superfície migra de janela.
        closeNativePlayer()
        startActivity(Intent(this, VideoPipActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        })
    }

    override fun onUserLeaveHint() {
        // Com setAutoEnterEnabled(true), o Android realiza a transição Home → PiP.
        // Não lançar VideoPipActivity daqui: lançamento em segundo plano é restrito
        // e foi justamente o motivo da tentativa anterior não funcionar.
        super.onUserLeaveHint()
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

    /** Autoriza mídia HTML5 em segundo plano; o serviço só inicia com som detectado. */
    private fun toggleBackgroundAudio() {
        val enable = !backgroundAudioEnabled
        applyBackgroundAudioState(enable)
        backgroundAudioPrefs.edit().putBoolean("background_audio", enable).apply()
        Toast.makeText(this,
            if (enable) "Suporte a áudio em segundo plano ativado"
            else "Suporte a áudio em segundo plano desativado",
            Toast.LENGTH_LONG).show()
    }
    private fun showSavedPages(favorites: Boolean) {
        val pages = if (favorites) store.favorites() else store.history()
        if (pages.isEmpty()) { Toast.makeText(this, "Nenhum registro", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(uiBackground())
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = if (favorites) "★ Favoritos" else "◷ Histórico"
            textSize = 24f
            setTextColor(uiForeground())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(uiAction("Fechar").apply { setOnClickListener { dialog.dismiss() } })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "${pages.size} página(s) salvas"
            textSize = 13f
            setTextColor(uiMuted())
            setPadding(0, dp(4), 0, dp(12))
        })
        val search = EditText(this).apply {
            styleInput(this)
            hint = "Buscar por título ou endereço"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            background = uiShape(uiInputSurface(), 12, true)
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        fun render() {
            content.removeAllViews()
            val query = search.text.toString().trim()
            val filtered = pages.filter { query.isEmpty() ||
                it.title.contains(query, true) || it.url.contains(query, true) }
            if (filtered.isEmpty()) content.addView(TextView(this).apply {
                text = "Nenhuma página encontrada."
                textSize = 15f
                setTextColor(uiMuted())
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
            filtered.forEach { page ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = uiShape(uiSurface(), 14, true)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Abrir ${page.title.ifBlank { page.url }}"
                    setOnClickListener { dialog.dismiss(); navigate(page.url) }
                }
                row.addView(TextView(this).apply {
                    text = page.title.ifBlank { page.url }
                    textSize = 16f
                    setTextColor(uiForeground())
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(TextView(this).apply {
                    text = page.url
                    textSize = 12f
                    setTextColor(uiMuted())
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                content.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(uiAction(if (favorites) "Gerenciar favoritos" else "Limpar histórico").apply {
            setOnClickListener {
                dialog.dismiss()
                if (favorites) showRemoveFavorite(pages)
                else AlertDialog.Builder(this@MainActivity)
                    .setTitle("Apagar todo o histórico?")
                    .setPositiveButton("Apagar") { _, _ -> store.clearHistory() }
                    .setNegativeButton("Cancelar", null).show()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        render()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels, dp(720)), MATCH_PARENT)
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
        val entries = org.json.JSONArray()
        tabs.forEach { tab ->
            val url = attentionBlockedSources[tab.view] ?: tab.view.url ?: tab.url
            entries.put(org.json.JSONObject().put("url", url)
                .put("title", tab.view.title ?: tab.title)
                .put("group", tab.constellation).put("id", tab.id)
                .put("profile", tab.profile).put("live", true))
        }
        parkedTabs.forEach { tab ->
            entries.put(org.json.JSONObject().put("url", tab.url)
                .put("title", tab.title).put("group", tab.constellation)
                .put("id", tab.id).put("profile", tab.profile).put("live", false))
        }
        getSharedPreferences("tabs", MODE_PRIVATE).edit()
            .putString("urls", tabs.joinToString("\n") {
                attentionBlockedSources[it.view] ?: it.view.url ?: it.url
            })
            .putString("constellations_v1", entries.toString())
            .putString("active_id", tabs.getOrNull(activeIndex)?.id)
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
        configureSiteDisplay(webView, url)
        applyLoginCookiePolicy(webView, url)
        recordGoogleLoginAppNavigation(webView, url, "barra de endereços ou comando")
        webView.loadUrl(url)
        updateInstagramOverlay()
        urlBar.clearFocus()
    }

    private fun htmlResponse(html: String) = WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    private fun blockHtmlFor(v: AttentionVerdict): String {
        if (v.untilMs > 0L) return BlockPage.breakHtml(v, Attention.dismissWaitLeft(v.site.id))
        val html = BlockPage.html("Acesso encerrado", v.site.domain, v.reason)
        if (v.reason != "O limite diário foi atingido." || AttentionRewards.balance() < 5) return html
        val link = "<p>Créditos disponíveis: ${AttentionRewards.balance()} min</p>" +
            "<a href='extbrowser://resgatar/${v.site.id}' style='display:inline-block;padding:14px;" +
            "background:#eee;color:#111;border-radius:8px;text-decoration:none'>Resgatar 5 minutos</a>"
        return html.replace("</main>", link + "</main>")
    }
    private fun loadBlock(v: AttentionVerdict) {
        rememberAttentionSource(webView, webView.url ?: tabs.getOrNull(activeIndex)?.url)
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
        val returnUrl = attentionBlockedSources[webView] ?: "https://${site.domain}/"
        when (u.host) {
            "resgatar" -> {
                if (Attention.verdictForSite(id)?.reason == "O limite diário foi atingido." &&
                    AttentionRewards.redeem(id, 5)) webView.loadUrl(returnUrl)
                else Attention.verdictForSite(id)?.let { loadBlock(it) } ?: webView.loadUrl(returnUrl)
            }
            "dispensar" -> {
                Attention.requestDismiss(id)
                Attention.verdictForSite(id)?.let { loadBlock(it) } ?: webView.loadUrl(returnUrl)
            }
            "confirmar" -> {
                if (Attention.confirmDismiss(id)) webView.loadUrl(returnUrl)
                else Attention.verdictForSite(id)?.let { loadBlock(it) } ?: webView.loadUrl(returnUrl)
            }
        }
    }
    private fun updateStatus() {
        if (isInPictureInPictureMode || tabs.isEmpty()) {
            statusView.visibility = View.GONE
            return
        }
        val page = webView.url
        val st = Attention.status(page)
        if (st == null) {
            statusView.visibility = View.GONE
            return
        }
        // Modo Tranquilo: apenas estado estável, sem relógio progressivo.
        // O bloqueio e a contabilização continuam sob responsabilidade de Attention.
        val blocked = page?.let { Attention.checkNavigation(it) } != null
        statusView.text = if (blocked)
            "☕ ${st.site.domain} · sessão concluída. Hora de descansar."
        else "✓ ${st.site.domain} · Controle de Atenção ativo"
        statusView.setTextColor(statusDefaultColor)
        statusView.visibility = View.VISIBLE
    }
    /** Mantém as mesmas instâncias de WebView e abas durante a rotação.
     * O Android recalcula o layout, sem chamar onDestroy/onCreate.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ConfigChanges preserva a Activity: a barra precisa refazer as colunas
        // ao girar, em vez de manter os pesos de sua orientação anterior.
        arrangeBrowserToolbar(newConfig.screenWidthDp)
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
        browserIsResumed = true
        setBackgroundEndGuard(false)
        handler.removeCallbacks(backgroundMediaTicker)
        backgroundAudioEnabled = backgroundAudioPrefs.getBoolean("background_audio", false)
        // Retoma a renderização sem recriar páginas ou reiniciar players.
        tabs.forEachIndexed { index, tab ->
            if (backgroundAudioEnabled || index == activeIndex) tab.view.onResume()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        handler.removeCallbacks(audibleMediaTicker)
        if (backgroundAudioEnabled) handler.post(audibleMediaTicker)
        handler.removeCallbacks(miniRefresh)
        handler.post(miniRefresh)
    }
    override fun onPause() {
        browserIsResumed = false
        if (backgroundAudioEnabled) setBackgroundEndGuard(true)
        handler.removeCallbacks(audibleMediaTicker)
        handler.removeCallbacks(miniRefresh)
        handler.removeCallbacks(ticker)
        Attention.pause()
        tabs.forEach { saveTabNavigation(it) }
        persistTabs()
        // A contagem de navegação para fora do app. O ticker separado verifica
        // reprodução HTML5 detectável enquanto o serviço mantém as abas vivas.
        if (!backgroundAudioEnabled && !isInPictureInPictureMode && fullscreenView == null)
            tabs.forEach { it.view.onPause() }
        if (backgroundAudioEnabled) {
            handler.removeCallbacks(backgroundMediaTicker)
            handler.post(backgroundMediaTicker)
        }
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(queueSyncTicker)
        if (liveBrowser?.get() === this) liveBrowser = null
        backgroundAudioPrefs.unregisterOnSharedPreferenceChangeListener(backgroundAudioPreferenceListener)
        handler.removeCallbacks(audibleMediaTicker)
        handler.removeCallbacks(backgroundMediaTicker)
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
