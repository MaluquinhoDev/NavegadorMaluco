package com.example.extbrowser.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Tradução local com ML Kit.
 *
 * Os modelos são baixados sob demanda. Depois do download, a inferência é
 * executada no aparelho e o texto da página não é enviado para um API remoto.
 *
 * Esta classe deve ser chamada fora da thread principal.
 */
class MlKitTranslationEngine : TranslationEngine {
    private val languageIdentifier = LanguageIdentification.getClient()
    private var activeSource: String? = null
    private var activeTarget: String? = null
    private var translator: Translator? = null

    override fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()

        val detectedSource = if (sourceLanguage == "auto") {
            detectLanguage(texts)
        } else {
            normalizeLanguage(sourceLanguage)
        }
        val normalizedTarget = normalizeLanguage(targetLanguage)

        if (detectedSource == normalizedTarget) return texts

        val current = getTranslator(detectedSource, normalizedTarget)
        // O primeiro uso pode baixar o modelo (aproximadamente 30 MB).
        Tasks.await(current.downloadModelIfNeeded(DownloadConditions.Builder().build()))

        return texts.map { text ->
            if (text.isBlank()) text
            else Tasks.await(current.translate(text))
        }
    }

    private fun detectLanguage(texts: List<String>): String {
        val sample = texts.asSequence()
            .filter { it.isNotBlank() }
            .take(30)
            .joinToString(" ")
            .take(4_000)

        check(sample.isNotBlank()) { "Não há texto suficiente para identificar o idioma." }

        val detected = Tasks.await(languageIdentifier.identifyLanguage(sample))
        check(detected != "und") {
            "Não foi possível identificar o idioma da página automaticamente."
        }
        return normalizeLanguage(detected)
    }

    private fun normalizeLanguage(languageTag: String): String {
        val language = TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalArgumentException("Idioma não suportado pelo tradutor: $languageTag")
        return language
    }

    private fun getTranslator(source: String, target: String): Translator {
        if (source == activeSource && target == activeTarget && translator != null) {
            return translator!!
        }

        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also {
            translator = it
            activeSource = source
            activeTarget = target
        }
    }

    override fun close() {
        translator?.close()
        translator = null
        activeSource = null
        activeTarget = null
        languageIdentifier.close()
    }
}
