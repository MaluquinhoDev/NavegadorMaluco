package com.example.extbrowser

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Extractor independente, sem cookies da WebView nem alteração da sessão Media3. */
object YoutubeExtractor {
    @Volatile private var applicationContext: android.content.Context? = null
    @Volatile var poTokenStatus: String = "Ainda não testado"
        private set

    fun recordPoTokenStatus(status: String) { poTokenStatus = status }

    /** O gerador WebView só é iniciado durante a extração, nunca em serviço persistente. */
    fun configure(context: android.content.Context) {
        applicationContext = context.applicationContext
    }
    data class Source(val label: String, val url: String, val playable: Boolean)
    data class Report(val sources: List<Source>, val requests: Int,
        val httpErrors: Map<Int, Int>, val elapsedMs: Long)
    class ExtractionFailure(val requests: Int, val httpErrors: Map<Int, Int>,
        cause: Throwable) : IOException("Falha na extração: ${cause.javaClass.simpleName}", cause)
    data class AccessCheck(val status: Int?, val failure: String? = null)
    data class SourceSignals(
        val mediaHost: String,
        val poTokenParameter: Boolean,
        val sabrParameter: Boolean,
        val signatureParameter: Boolean,
        val expiry: String
    ) {
        fun description(): String = "Host: $mediaHost\n" +
            "Parâmetro PO na URL: ${if (poTokenParameter) "presente" else "ausente"}\n" +
            "Indicador SABR na URL: ${if (sabrParameter) "presente" else "ausente"}\n" +
            "Assinatura na URL: ${if (signatureParameter) "presente" else "ausente"}\n" +
            "Validade da URL: $expiry"
    }

    /** Somente presença de parâmetros e validade; nunca retorna seus valores. */
    fun inspectSourceSignals(url: String): SourceSignals {
        val uri = android.net.Uri.parse(url)
        val names = runCatching { uri.queryParameterNames.map { it.lowercase() }.toSet() }
            .getOrDefault(emptySet())
        val expirySeconds = runCatching { uri.getQueryParameter("expire")?.toLongOrNull() }
            .getOrNull()
        val expiry = if (expirySeconds == null) "não informada" else {
            val left = expirySeconds - System.currentTimeMillis() / 1000L
            when {
                left <= 0 -> "expirada"
                left < 60 -> "menos de 1 minuto"
                else -> "aproximadamente ${left / 60} minuto(s)"
            }
        }
        val host = uri.host.orEmpty().lowercase()
        return SourceSignals(
            mediaHost = if (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
                "GoogleVideo" else if (host == "youtube.com" || host.endsWith(".youtube.com"))
                "YouTube" else "outro",
            poTokenParameter = "pot" in names,
            sabrParameter = "sabr" in names,
            signatureParameter = names.any { it == "sig" || it == "lsig" || it == "signature" },
            expiry = expiry
        )
    }

    private class NetworkTrace {
        var requests = 0
        val errors = sortedMapOf<Int, Int>()
    }
    private val trace = ThreadLocal<NetworkTrace>()

    // Metadados do extrator não devem consumir memória sem limites.
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        stream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_RESPONSE_BYTES)
                    throw IOException("Resposta do extrator acima do limite de segurança")
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private class NetworkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection)
            trace.get()?.let { it.requests++ }
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 16000
                connection.instanceFollowRedirects = true
                connection.requestMethod = request.httpMethod()
                for ((name, values) in request.headers().orEmpty()) {
                    if (!name.equals("cookie", true) && !name.equals("authorization", true)) {
                        values.forEach { connection.addRequestProperty(name, it) }
                    }
                }
                val payload = request.dataToSend()
                if (payload != null && payload.isNotEmpty()) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(payload) }
                }
                val status = connection.responseCode
                trace.get()?.let { t ->
                    if (status >= 400) t.errors[status] = (t.errors[status] ?: 0) + 1
                }
                val bytes = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.let(::readLimited) ?: byteArrayOf()
                val headers = connection.headerFields.filterKeys { it != null }
                    .mapKeys { it.key!! }
                return Response(status, connection.responseMessage ?: "", headers,
                    String(bytes, Charsets.UTF_8), connection.url.toString())
            } finally { connection.disconnect() }
        }
    }

    @Synchronized
    private fun initialize() {
        if (NewPipe.getDownloader() == null) NewPipe.init(NetworkDownloader())
        applicationContext?.let { YoutubeStreamExtractor.setPoTokenProvider(
            YoutubePoTokenProvider(it)
        ) }
    }

    /** Executar exclusivamente em thread de I/O. URLs podem expirar. */
    fun extract(url: String): List<Source> {
        initialize()
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val result = mutableListOf<Source>()
        extractor.hlsUrl?.takeIf { it.startsWith("https://") }?.let {
            result += Source("HLS · playlist", it, true)
        }
        extractor.dashMpdUrl?.takeIf { it.startsWith("https://") }?.let {
            result += Source("DASH · manifesto", it, true)
        }
        for (stream in extractor.videoStreams) {
            val u = stream.content
            if (u.startsWith("https://"))
                result += Source("Vídeo + áudio · ${stream.resolution}", u, true)
        }
        for (stream in extractor.videoOnlyStreams) {
            val u = stream.content
            if (u.startsWith("https://"))
                result += Source("Vídeo sem áudio · ${stream.resolution}", u, false)
        }
        for (stream in extractor.audioStreams) {
            val u = stream.content
            if (u.startsWith("https://"))
                result += Source("Áudio · ${stream.averageBitrate} kbps", u, true)
        }
        return result.distinctBy { it.url }
    }

    /** Métricas sem URLs assinadas, cookies ou corpo das respostas. */
    fun extractWithReport(url: String): Report {
        recordPoTokenStatus("Não solicitado nesta tentativa")
        val started = android.os.SystemClock.elapsedRealtime()
        val network = NetworkTrace()
        trace.set(network)
        try {
            val sources = extract(url)
            return Report(sources, network.requests, network.errors.toMap(),
                android.os.SystemClock.elapsedRealtime() - started)
        } catch (error: Exception) {
            throw ExtractionFailure(network.requests, network.errors.toMap(), error)
        } finally {
            trace.remove()
        }
    }

    /** Sonda o primeiro byte; sucesso inicial não garante a reprodução integral. */
    fun checkAccess(url: String, userAgent: String, referer: String): AccessCheck {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("User-Agent", userAgent)
            if (referer.startsWith("https://")) conn.setRequestProperty("Referer", referer)
            val status = conn.responseCode
            if (status in 200..299) conn.inputStream.use { it.read() }
            AccessCheck(status)
        } catch (error: IOException) {
            AccessCheck(null, error.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * PO Token WEB anônimo para o NewPipeExtractor. Executa BotGuard em WebView efêmera,
 * isolada da sessão do navegador: não lê nem encaminha cookies da conta do usuário.
 * Adaptado da especificação pública do protocolo usada pelo projeto NewPipe.
 */
private class YoutubePoTokenProvider(private val context: android.content.Context) :
    org.schabi.newpipe.extractor.services.youtube.PoTokenProvider {

    override fun getWebClientPoToken(videoId: String):
        org.schabi.newpipe.extractor.services.youtube.PoTokenResult? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        YoutubeExtractor.recordPoTokenStatus("Gerando PO Token WEB…")
        return synchronized(this) {
            val tokenResult = runCatching {
                val info = org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo.ofWebClient()
                info.clientInfo.clientVersion =
                    org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion()
                val visitor = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisitorDataFromInnertube(
                    info, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                    org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYouTubeHeaders(),
                    org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null, false
                )
                val generator = BotguardSession(context, visitor, videoId)
                generator.run()
            }.onFailure {
                android.util.Log.w("YoutubePoToken", "Token WEB indisponível: ${it.javaClass.simpleName}")
            }.getOrNull()
            YoutubeExtractor.recordPoTokenStatus(if (tokenResult != null)
                "PO Token WEB gerado em perfil WebView isolado"
            else if (YoutubeExtractor.poTokenStatus.startsWith("BotGuard isolado indisponível"))
                YoutubeExtractor.poTokenStatus
            else "Falha na geração do PO Token WEB")
            tokenResult
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String):
        org.schabi.newpipe.extractor.services.youtube.PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String):
        org.schabi.newpipe.extractor.services.youtube.PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String):
        org.schabi.newpipe.extractor.services.youtube.PoTokenResult? = null

    private class BotguardSession(
        private val context: android.content.Context,
        private val visitor: String,
        private val videoId: String
    ) {
        private val done = java.util.concurrent.CountDownLatch(1)
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private var webView: android.webkit.WebView? = null
        @Volatile private var result:
            org.schabi.newpipe.extractor.services.youtube.PoTokenResult? = null
        private var stage = 0
        private var streamingToken: String? = null
        // Estado atômico restringe chamadas inesperadas/repetidas pela ponte JavaScript.
        // 0=início, 1=Create, 2=GenerateIT, 3=primeiro mint, 4=segundo mint, 5=fim.
        private val bridgePhase = java.util.concurrent.atomic.AtomicInteger(0)

        fun run(): org.schabi.newpipe.extractor.services.youtube.PoTokenResult? {
            main.post {
                try {
                    // Uma WebView nova compartilha o perfil padrão com as abas do navegador.
                    // Exigir MULTI_PROFILE protege os cookies e armazenamento da navegação.
                    // Falhar fechado preserva a sessão do usuário em WebViews antigas.
                    if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                            androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                        YoutubeExtractor.recordPoTokenStatus(
                            "BotGuard isolado indisponível: WebView sem suporte a perfis")
                        finish()
                        return@post
                    }
                    val view = android.webkit.WebView(context)
                    webView = view
                    // setProfile DEVE ser a primeira operação com a instância.
                    androidx.webkit.WebViewCompat.setProfile(view, "nm_botguard_isolated")
                    view.settings.javaScriptEnabled = true
                    view.settings.blockNetworkLoads = true
                    view.settings.allowFileAccess = false
                    view.settings.allowContentAccess = false
                    view.settings.domStorageEnabled = false
                    view.settings.javaScriptCanOpenWindowsAutomatically = false
                    view.settings.userAgentString = BOTGUARD_UA
                    view.addJavascriptInterface(Callbacks(), "PoBridge")
                    view.loadDataWithBaseURL(
                        "https://www.youtube.com", BOTGUARD_HTML,
                        "text/html", "UTF-8", null
                    )
                } catch (e: Exception) {
                    YoutubeExtractor.recordPoTokenStatus(
                        "BotGuard isolado indisponível: ${e.javaClass.simpleName}")
                    finish()
                }
            }
            try {
                done.await(32, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                main.post {
                    webView?.let {
                        it.stopLoading()
                        it.removeJavascriptInterface("PoBridge")
                        it.loadUrl("about:blank")
                        it.destroy()
                    }
                    webView = null
                }
            }
            return result
        }

        private fun finish() { done.countDown() }

        private fun postChallenge(endpoint: String, data: String, action: (String) -> Unit) {
            if ((endpoint != "Create" && endpoint != "GenerateIT") ||
                data.length > 1024 * 1024) { finish(); return }
            Thread {
                try {
                    val conn = (java.net.URL("https://www.youtube.com/api/jnn/v1/$endpoint")
                        .openConnection() as HttpURLConnection)
                    try {
                        conn.connectTimeout = 9000
                        conn.readTimeout = 9000
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("User-Agent", BOTGUARD_UA)
                        conn.setRequestProperty("Accept", "application/json")
                        conn.setRequestProperty("Content-Type", "application/json+protobuf")
                        conn.setRequestProperty("x-goog-api-key", BOTGUARD_API_KEY)
                        conn.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                        if (conn.responseCode != 200) throw IOException("BotGuard HTTP ${conn.responseCode}")
                        // Não permitir respostas ilimitadas vindas do serviço remoto.
                        val response = conn.inputStream.bufferedReader().use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                if (out.length + count > 8 * 1024 * 1024)
                                    throw IOException("BotGuard response too large")
                                out.append(buffer, 0, count)
                            }
                            out.toString()
                        }
                        main.post { action(response) }
                    } finally { conn.disconnect() }
                } catch (_: Exception) { finish() }
            }.start()
        }

        private fun js(source: String) {
            webView?.evaluateJavascript(source, null)
        }

        inner class Callbacks {
            @android.webkit.JavascriptInterface fun loaded() {
                if (!bridgePhase.compareAndSet(0, 1)) return
                postChallenge("Create", "[\"$BOTGUARD_REQUEST_KEY\"]") { response ->
                    try {
                        val root = org.json.JSONArray(response)
                        val challenge = if (root.length() > 1 && root.opt(1) is String) {
                            val encoded = root.getString(1).replace('-', '+').replace('_', '/')
                                .replace('.', '=')
                            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                            org.json.JSONArray(String(bytes.map { (it + 97).toByte() }.toByteArray()))
                        } else root.getJSONArray(0)
                        val jsInfo = org.json.JSONObject().apply {
                            put("program", challenge.getString(4))
                            put("globalName", challenge.getString(5))
                            put("interpreterJavascript", org.json.JSONObject().apply {
                                put("privateDoNotAccessOrElseSafeScriptWrappedValue",
                                    challenge.optJSONArray(1)?.let { list ->
                                        (0 until list.length()).mapNotNull { list.optString(it).takeIf(String::isNotEmpty) }
                                            .firstOrNull()
                                    } ?: "")
                            })
                        }
                        js("""try { runBotGuard($jsInfo).then(
                            function(r) { window.poSignal=r.webPoSignalOutput; PoBridge.botguard(r.botguardResponse); },
                            function(e) { PoBridge.failed(); }
                        ); } catch(e) { PoBridge.failed(); }""")
                    } catch (_: Exception) { finish() }
                }
            }

            @android.webkit.JavascriptInterface fun botguard(snapshot: String) {
                if (!bridgePhase.compareAndSet(1, 2)) return
                if (snapshot.length > 1024 * 1024) { finish(); return }
                val body = org.json.JSONArray().put(BOTGUARD_REQUEST_KEY).put(snapshot).toString()
                postChallenge("GenerateIT", body) { response ->
                    try {
                        val json = org.json.JSONArray(response)
                        val encoded = json.getString(0).replace('-', '+').replace('_', '/')
                            .replace('.', '=')
                        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                        if (bytes.size > 1024 * 1024) throw IOException("BotGuard token too large")
                        js("window.integrityToken=new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 255).toString() }}]);")
                        bridgePhase.set(3)
                        mint(visitor)
                    } catch (_: Exception) { finish() }
                }
            }

            @android.webkit.JavascriptInterface fun minted(encoded: String) {
                try {
                    if (encoded.length > 128 * 1024) { finish(); return }
                    val expected = if (stage == 0) 3 else 4
                    if (!bridgePhase.compareAndSet(expected, expected + 1)) return
                    val bytes = encoded.split(',').map { it.toInt().toByte() }.toByteArray()
                    val token = android.util.Base64.encodeToString(bytes,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    main.post {
                        if (stage == 0) {
                            streamingToken = token
                            stage = 1
                            mint(videoId)
                        } else {
                            val streaming = streamingToken
                            if (streaming != null)
                                result = org.schabi.newpipe.extractor.services.youtube.PoTokenResult(
                                    visitor, token, streaming
                                )
                            finish()
                        }
                    }
                } catch (_: Exception) { finish() }
            }

            @android.webkit.JavascriptInterface fun failed() { finish() }
        }

        private fun mint(identifier: String) {
            val data = identifier.toByteArray(Charsets.UTF_8)
                .joinToString(",") { (it.toInt() and 255).toString() }
            js("""try { PoBridge.minted(Array.from(obtainPoToken(window.poSignal,
                window.integrityToken,new Uint8Array([$data]))).join(',')); }
                catch(e) { PoBridge.failed(); }""")
        }

        companion object {
            private const val BOTGUARD_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
            private const val BOTGUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
            private const val BOTGUARD_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            private val BOTGUARD_HTML = """<!doctype html><html><head><script>
                function runBotGuard(c) {
                  let source=c.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
                  if(!source) throw Error('BotGuard VM missing');
                  new Function(source)();
                  let vm=window[c.globalName], output=[];
                  if(!vm || !vm.a) throw Error('BotGuard VM unavailable');
                  return new Promise((resolve,reject)=>{
                    let functions=null;
                    let snapshot=vm.a(c.program,(a,b,p,cam)=>{functions=a;},true,
                      document.body,()=>{},[[],[]])[0];
                    let tries=0;
                    let timer=setInterval(()=>{
                      if(functions){
                        clearInterval(timer);
                        functions(v=>resolve({webPoSignalOutput:output,botguardResponse:v}),
                          [undefined,undefined,output,undefined]);
                      } else if(++tries>10000){clearInterval(timer);reject(Error('BotGuard timeout'));}
                    },1);
                  });
                }
                function obtainPoToken(signal,integrity,id) {
                  let callback=signal[0](integrity),value=callback(id);
                  if(!(value instanceof Uint8Array)) throw Error('Invalid PO Token');
                  return value;
                }
                PoBridge.loaded();
                </script></head><body></body></html>"""
        }
    }
}
