package com.example.extbrowser

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Extractor independente, sem cookies da WebView nem alteração da sessão Media3. */
object YoutubeExtractor {
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
