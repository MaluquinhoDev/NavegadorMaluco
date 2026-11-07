package com.example.extbrowser.translation

import java.util.LinkedHashMap

/**
 * Cache LRU em memória. O limite é por quantidade de entradas para manter
 * previsível o consumo de memória em aparelhos modestos.
 */
class TranslationCache(
    private val maxEntries: Int = 1_000
) {
    init {
        require(maxEntries > 0) { "maxEntries precisa ser maior que zero" }
    }

    private data class Key(
        val sourceLanguage: String,
        val targetLanguage: String,
        val originalText: String
    )

    private val entries = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(sourceLanguage: String, targetLanguage: String, originalText: String): String? {
        return entries[Key(sourceLanguage, targetLanguage, originalText)]
    }

    @Synchronized
    fun put(
        sourceLanguage: String,
        targetLanguage: String,
        originalText: String,
        translatedText: String
    ) {
        entries[Key(sourceLanguage, targetLanguage, originalText)] = translatedText
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
