package com.example.extbrowser

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL

/** Extractor independente, sem cookies da WebView nem alteração da sessão Media3. */
object YoutubeExtractor {
    data class Source(val label: String, val url: String, val playable: Boolean)

    private class NetworkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = (URL(request.url()).openConnection() as HttpURLConnection)
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
                val bytes = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.use { it.readBytes() } ?: byteArrayOf()
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
}
