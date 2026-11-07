package com.example.extbrowser.translation

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordena extração do DOM, tradução fora da thread principal e aplicação
 * seletiva dos textos no WebView que já navega no aplicativo.
 */
class TranslationManager(
    private val engine: TranslationEngine,
    private val cache: TranslationCache = TranslationCache(),
    private val maxBatchItems: Int = 12,
    private val maxBatchCharacters: Int = 2_500
) : AutoCloseable {

    data class Result(val translatedNodes: Int, val pageUrl: String)

    interface Callback {
        fun onSuccess(result: Result)
        fun onError(error: Throwable)
    }

    private data class TextNode(val id: String, val text: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val requestSequence = AtomicLong(0)
    @Volatile private var closed = false

    init {
        require(maxBatchItems > 0)
        require(maxBatchCharacters > 0)
    }

    fun translatePage(
        webView: WebView,
        sourceLanguage: String = "auto",
        targetLanguage: String = "pt",
        callback: Callback
    ) {
        check(!closed) { "O gerenciador de tradução já foi encerrado." }
        val requestId = requestSequence.incrementAndGet()

        mainHandler.post {
            val pageUrl = webView.url.orEmpty()
            if (pageUrl.isBlank()) {
                callback.onError(IllegalStateException("O WebView não possui uma página carregada."))
                return@post
            }

            webView.evaluateJavascript(
                TranslationPageScript.INSTALL + "\nJSON.stringify(window.__nmTranslation.extract());"
            ) { raw ->
                if (requestId != requestSequence.get() || closed) return@evaluateJavascript

                try {
                    val payload = JSONObject(raw ?: "{}")
                    val pageLanguage = payload.optString("language").substringBefore('-')
                    val nodes = parseNodes(payload.optJSONArray("nodes"))
                    if (nodes.isEmpty()) {
                        callback.onSuccess(Result(0, pageUrl))
                        return@evaluateJavascript
                    }

                    val source = if (sourceLanguage == "auto") {
                        pageLanguage.takeIf { it.isNotBlank() } ?: "auto"
                    } else sourceLanguage

                    executor.execute {
                        try {
                            val translated = translateNodes(nodes, source, targetLanguage)
                            mainHandler.post {
                                if (closed || requestId != requestSequence.get() || webView.url != pageUrl) {
                                    return@post
                                }

                                val array = JSONArray()
                                translated.forEach { (id, value) ->
                                    array.put(JSONObject().put("id", id).put("text", value))
                                }

                                webView.evaluateJavascript(
                                    "window.__nmTranslation.apply($array);"
                                ) {
                                    callback.onSuccess(Result(translated.size, pageUrl))
                                }
                            }
                        } catch (error: Throwable) {
                            mainHandler.post {
                                if (!closed && requestId == requestSequence.get()) callback.onError(error)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    callback.onError(error)
                }
            }
        }
    }

    fun cancelPending() {
        requestSequence.incrementAndGet()
    }

    fun restorePage(webView: WebView, callback: ((Int) -> Unit)? = null) {
        cancelPending()
        mainHandler.post {
            if (closed) return@post
            webView.evaluateJavascript(
                TranslationPageScript.INSTALL + "\nwindow.__nmTranslation.restore();"
            ) { raw ->
                callback?.invoke(raw?.trim('"')?.toIntOrNull() ?: 0)
            }
        }
    }

    private fun parseNodes(array: JSONArray?): List<TextNode> {
        if (array == null) return emptyList()
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
            val output = engine.translate(batch.map { it.text }, sourceLanguage, targetLanguage)
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
            if (current.isNotEmpty() &&
                (current.size >= maxBatchItems || characters + node.text.length > maxBatchCharacters)
            ) {
                batches.add(current)
                current = ArrayList()
                characters = 0
            }
            current.add(node)
            characters += node.text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        executor.shutdownNow()
        engine.close()
        cache.clear()
    }
}
