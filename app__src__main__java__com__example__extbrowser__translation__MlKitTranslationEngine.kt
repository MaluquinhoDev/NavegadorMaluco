package com.example.extbrowser.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.ceil
import kotlin.math.min

/**
 * Tradução local com ML Kit.
 *
 * A origem automática é determinada pelo conteúdo textual da página, não pelo
 * atributo HTML lang, que é uma declaração do site e pode estar incorreta.
 * Cada amostra é identificada separadamente para reduzir o efeito de páginas
 * que misturam idiomas em diferentes trechos.
 *
 * Os modelos de tradução são baixados sob demanda. Depois do download, a
 * inferência é executada no aparelho e o texto da página não é enviado a uma
 * API remota. Esta classe deve ser chamada fora da thread principal.
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

        val detectedSource = resolveSourceLanguage(texts, sourceLanguage)
        val normalizedTarget = normalizeLanguage(targetLanguage)

        if (detectedSource == normalizedTarget) return texts

        val current = getTranslator(detectedSource, normalizedTarget)
        // O primeiro uso baixa o modelo (aproximadamente 30 MB), somente em Wi-Fi.
        Tasks.await(current.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()))

        return texts.map { text ->
            if (text.isBlank()) text
            else Tasks.await(current.translate(text))
        }
    }

    override fun resolveSourceLanguage(texts: List<String>, requestedLanguage: String): String {
        if (!requestedLanguage.equals("auto", ignoreCase = true)) {
            return normalizeLanguage(requestedLanguage)
        }
        return detectPageLanguage(texts)
    }

    /**
     * Identifica vários trechos separadamente e escolhe o idioma predominante.
     *
     * O ML Kit considera uma única língua por chamada e trunca entradas acima
     * de 200 caracteres. Por isso cada amostra é limitada e recebe seu próprio
     * resultado. Trechos sem confiança suficiente não participam da votação.
     */
    private fun detectPageLanguage(texts: List<String>): String {
        val eligible = texts
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_SAMPLE_LENGTH }
            .toList()

        check(eligible.isNotEmpty()) {
            "Não há texto suficiente para identificar o idioma original da página."
        }

        val stride = ceil(eligible.size / MAX_SAMPLES.toDouble()).toInt().coerceAtLeast(1)
        val samples = eligible.filterIndexed { index, _ -> index % stride == 0 }
            .take(MAX_SAMPLES)

        val scores = LinkedHashMap<String, Double>()
        var acceptedWeight = 0.0
        var acceptedSamples = 0

        for (text in samples) {
            val sample = text.take(MAX_SAMPLE_CHARACTERS)
            val candidates = Tasks.await(languageIdentifier.identifyPossibleLanguages(sample))
            val best = candidates
                .asSequence()
                .filter { it.languageTag != "und" }
                .maxByOrNull { it.confidence }
                ?: continue

            if (best.confidence < MIN_SAMPLE_CONFIDENCE) continue

            val language = TranslateLanguage.fromLanguageTag(best.languageTag) ?: continue
            val weight = min(sample.length, MAX_SAMPLE_CHARACTERS).toDouble() *
                best.confidence.toDouble()

            scores[language] = (scores[language] ?: 0.0) + weight
            acceptedWeight += weight
            acceptedSamples++
        }

        check(acceptedSamples > 0 && acceptedWeight > 0.0) {
            "Não foi possível identificar com confiança o idioma original da página."
        }

        val ranking = scores.entries.sortedByDescending { it.value }
        val winner = ranking.first()
        val winnerShare = winner.value / acceptedWeight
        val runnerUpShare = ranking.getOrNull(1)?.value?.div(acceptedWeight) ?: 0.0

        check(
            winnerShare >= MIN_DOMINANT_SHARE &&
                winnerShare - runnerUpShare >= MIN_DOMINANCE_MARGIN
        ) {
            "A página parece conter vários idiomas ou não possui um idioma predominante claro."
        }

        return winner.key
    }

    private fun normalizeLanguage(languageTag: String): String {
        return TranslateLanguage.fromLanguageTag(languageTag)
            ?: throw IllegalArgumentException("Idioma não suportado pelo tradutor: $languageTag")
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

    private companion object {
        const val MAX_SAMPLES = 40
        const val MAX_SAMPLE_CHARACTERS = 200
        const val MIN_SAMPLE_LENGTH = 20
        const val MIN_SAMPLE_CONFIDENCE = 0.35f
        const val MIN_DOMINANT_SHARE = 0.55
        const val MIN_DOMINANCE_MARGIN = 0.12
    }
}
