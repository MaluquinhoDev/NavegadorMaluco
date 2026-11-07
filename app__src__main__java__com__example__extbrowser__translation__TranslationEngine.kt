package com.example.extbrowser.translation

/**
 * Contrato independente do motor de tradução.
 *
 * A implementação concreta deverá executar a inferência localmente e devolver
 * exatamente uma tradução para cada texto recebido, mantendo a ordem.
 */
interface TranslationEngine : AutoCloseable {
    /**
     * Traduz um lote. "auto" pode ser usado como idioma de origem quando o
     * motor oferecer detecção automática.
     *
     * Esta função é chamada fora da thread principal.
     */
    fun resolveSourceLanguage(texts: List<String>, requestedLanguage: String): String =
        requestedLanguage

    fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String>

    override fun close() = Unit
}
