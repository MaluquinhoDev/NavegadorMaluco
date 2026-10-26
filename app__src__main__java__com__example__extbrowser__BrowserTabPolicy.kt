package com.example.extbrowser

/**
 * Regras puras de seleção de abas, sem referências a Activity ou WebView.
 * As operações de criar/destruir WebViews permanecem na MainActivity.
 */
internal object BrowserTabPolicy {
    const val MAX_TABS = 30
    const val MAX_RESIDENT_TABS = 6

    fun canCreate(count: Int): Boolean = count < MAX_TABS

    fun restoredIndex(savedIndex: Int, count: Int): Int =
        if (count <= 0) 0 else savedIndex.coerceIn(0, count - 1)

    /** Retorna o índice ativo depois de remover a aba em removedIndex. */
    fun indexAfterClose(activeIndex: Int, removedIndex: Int, remainingCount: Int): Int {
        if (remainingCount <= 0) return 0
        val desired = when {
            removedIndex < activeIndex -> activeIndex - 1
            else -> activeIndex
        }
        return desired.coerceIn(0, remainingCount - 1)
    }

    fun tabLabel(position: Int, title: String, selected: Boolean): String =
        "${position + 1}. ${title.take(50)}${if (selected) " ✓" else ""}"
}
