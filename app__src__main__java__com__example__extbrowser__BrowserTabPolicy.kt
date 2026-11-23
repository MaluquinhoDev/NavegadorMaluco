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
    data class TabCandidate(
        val id: String,
        val constellation: String,
        val lastUsed: Long
    )

    /**
     * Fecha-se uma aba de fundo sem interromper a atual. Ao fechar a ativa,
     * prioriza outra aba do mesmo grupo, depois a vizinha e por fim a mais recente.
     */
    fun nextTabIdAfterClose(
        activeTabId: String?,
        closingTabId: String,
        closingIndex: Int,
        tabs: List<TabCandidate>
    ): String? {
        val remaining = tabs.filterNot { it.id == closingTabId }
        if (remaining.isEmpty()) return null

        if (activeTabId != closingTabId) {
            remaining.firstOrNull { it.id == activeTabId }?.let { return it.id }
        }

        val closing = tabs.firstOrNull { it.id == closingTabId }
        val sameGroup = closing?.let { current ->
            remaining.filter { it.constellation == current.constellation }
                .maxByOrNull { it.lastUsed }
        }
        if (sameGroup != null) return sameGroup.id

        tabs.getOrNull(closingIndex + 1)?.takeIf { it.id != closingTabId }?.let { return it.id }
        tabs.getOrNull(closingIndex - 1)?.takeIf { it.id != closingTabId }?.let { return it.id }
        return remaining.maxByOrNull { it.lastUsed }?.id
    }

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
