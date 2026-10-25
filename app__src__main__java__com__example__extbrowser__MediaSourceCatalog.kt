package com.example.extbrowser

/** Apresentação pura dos resultados do extrator; nunca inclui URLs assinadas em textos/logs. */
internal object MediaSourceCatalog {
    fun ordered(sources: List<YoutubeExtractor.Source>): List<YoutubeExtractor.Source> =
        sources.distinctBy { it.url }.sortedWith(
            compareBy<YoutubeExtractor.Source> { priority(it.label) }
                .thenBy { it.label.lowercase() }
        )

    private fun priority(label: String): Int = when {
        label.startsWith("HLS") -> 0
        label.startsWith("DASH") -> 1
        label.startsWith("Vídeo + áudio") -> 2
        label.startsWith("Áudio") -> 3
        else -> 4
    }

    fun summary(sources: List<YoutubeExtractor.Source>): String {
        val hls = sources.count { it.label.startsWith("HLS") }
        val dash = sources.count { it.label.startsWith("DASH") }
        val combined = sources.count { it.label.startsWith("Vídeo + áudio") }
        val videoOnly = sources.count { it.label.startsWith("Vídeo sem áudio") }
        val audioOnly = sources.count { it.label.startsWith("Áudio") }
        return "HLS: $hls · DASH: $dash · Vídeo com áudio: $combined · " +
            "Vídeo separado: $videoOnly · Áudio: $audioOnly"
    }
}
