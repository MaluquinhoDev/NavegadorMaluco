package com.example.extbrowser.translation

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Orquestra a extração do DOM, a tradução em background e a aplicação do
 * resultado no WebView existente. Uma instância deve pertencer a uma aba.
 *
 * O motor é injetado: esta camada não escolhe servidor, não envia texto para
 * a rede e não contém dependência de GeckoView.
 */
class TranslationManager(
    private val engine: TranslationEngine,
    private val cache: TranslationCache = TranslationCache(),
    private val maxBatchItems: Int = 16,
    private val maxBatchCharacters: Int = 4_000
) : AutoCloseable {

    data class Result(
        val translatedNodes: Int,
        val pageUrl: String
    )

    interface Callback {
        fun onSuccess(result: Result)
        fun onError(error: Throwable)
    }

    private data class TextNode(val id: String, val text: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val requestSequence = AtomicLong(0)

    init {
        require(maxBatchItems > 0)
        require(maxBatchCharacters > 0)
    }

    /**
     * Traduz os textos visíveis da página atual. A detecção de idioma e a
     * inferência pertencem à implementação de TranslationEngine.
     */
    fun translatePage(
        webView: WebView,
        sourceLanguage: String = "auto",
        targetLanguage: String = "pt",
        callback: Callback
    ) {
        val requestId = requestSequence.incrementAndGet()

        mainHandler.post {
            val pageUrl = webView.url.orEmpty()
            if (pageUrl.isBlank()) {
                callback.onError(IllegalStateException("O WebView não possui uma página carregada."))
                return@post
            }

            webView.evaluateJavascript(
                TranslationPageScript.INSTALL + "\nwindow.__nmTranslation.extract();"
            ) { raw ->
                if (requestId != requestSequence.get()) return@evaluateJavascript

                try {
                    val nodes = parseNodes(raw)
                    if (nodes.isEmpty()) {
                        callback.onSuccess(Result(0, pageUrl))
                        return@evaluateJavascript
                    }

                    executor.execute {
                        try {
                            val translated = translateNodes(nodes, sourceLanguage, targetLanguage)
                            mainHandler.post {
                                if (requestId != requestSequence.get() || webView.url != pageUrl) {
                                    return@post
                                }

                                val payload = JSONArray()
                                translated.forEach { (id, text) ->
                                    payload.put(
                                        JSONObject()
                                            .put("id", id)
                                            .put("text", text)
                                    )
                                }

                                webView.evaluateJavascript(
                                    "window.__nmTranslation.apply($payload);"
                                ) {
                                    callback.onSuccess(Result(translated.size, pageUrl))
                                }
                            }
                        } catch (error: Throwable) {
                            mainHandler.post {
                                if (requestId == requestSequence.get()) callback.onError(error)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    callback.onError(error)
                }
            }
        }
    }

    /** Invalida resultados ainda em processamento, por exemplo após navegar. */
    fun cancelPending() {
        requestSequence.incrementAndGet()
    }

    /** Restaura os nós que este módulo modificou no documento atual. */
    fun restorePage(webView: WebView, callback: ((Int) -> Unit)? = null) {
        cancelPending()
        mainHandler.post {
            webView.evaluateJavascript(
                TranslationPageScript.INSTALL +
                    "\nwindow.__nmTranslation.restore();"
            ) { raw ->
                val restored = raw?.trim('"')?.toIntOrNull() ?: 0
                callback?.invoke(restored)
            }
        }
    }

    private fun parseNodes(raw: String?): List<TextNode> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()

        val array = JSONArray(raw)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val text = item.getString("text")
                if (text.isNotBlank()) add(TextNode(id, text))
            }
        }
    }

    private fun translateNodes(
        nodes: List<TextNode>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> {
        val translated = LinkedHashMap<String, String>()
        val pending = ArrayList<TextNode>()

        nodes.forEach { node ->
            val cached = cache.get(sourceLanguage, targetLanguage, node.text)
            if (cached != null) translated[node.id] = cached
            else pending.add(node)
        }

        for (batch in createBatches(pending)) {
            val input = batch.map { it.text }
            val output = engine.translate(input, sourceLanguage, targetLanguage)

            check(output.size == batch.size) {
                "O motor retornou ${output.size} traduções para ${batch.size} textos."
            }

            batch.forEachIndexed { index, node ->
                val value = output[index]
                cache.put(sourceLanguage, targetLanguage, node.text, value)
                translated[node.id] = value
            }
        }

        return translated
    }

    private fun createBatches(nodes: List<TextNode>): List<List<TextNode>> {
        val batches = ArrayList<List<TextNode>>()
        var current = ArrayList<TextNode>()
        var characters = 0

        nodes.forEach { node ->
            val length = node.text.length
            if (current.isNotEmpty() &&
                (current.size >= maxBatchItems || characters + length > maxBatchCharacters)
            ) {
                batches.add(current)
                current = ArrayList()
                characters = 0
            }

            current.add(node)
            characters += length
        }

        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    override fun close() {
        cancelPending()
        executor.shutdownNow()
        engine.close()
        cache.clear()
    }
}
