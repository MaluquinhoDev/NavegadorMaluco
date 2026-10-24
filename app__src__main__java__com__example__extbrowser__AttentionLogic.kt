package com.example.extbrowser

import java.net.URI

/** Regras puras do controle de atenção (sem dependência do Android, para poderem ser testadas). */
object AttentionLogic {
    private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /** "https://www.YouTube.com/watch?v=1" -> "youtube.com" */
    fun normalizeDomain(input: String): String {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':')
        s = s.removePrefix("www.")
        return s.trim('.')
    }

    /** O domínio atual pertence ao configurado? (youtube.com casa com music.youtube.com) */
    fun domainMatches(current: String, configured: String): Boolean {
        val c = normalizeDomain(current)
        val d = normalizeDomain(configured)
        if (d.isEmpty()) return false
        return c == d || c.endsWith(".$d")
    }

    /** "18:30" -> 1110. Devolve null se o texto não for um horário válido. */
    fun minutesOf(value: String): Int? {
        val m = TIME.matchEntire(value.trim()) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /** Horário permitido. Vazio = sempre; início = fim = dia inteiro; aceita intervalo que cruza a meia-noite. */
    fun withinTime(start: String, end: String, nowMinutes: Int): Boolean {
        if (start.isBlank() || end.isBlank()) return true
        val s = minutesOf(start) ?: return true
        val e = minutesOf(end) ?: return true
        if (s == e) return true
        return if (s < e) nowMinutes >= s && nowMinutes < e else nowMinutes >= s || nowMinutes < e
    }

    /** Host de uma URL http/https, sem "www."; null para qualquer outra coisa. */
    fun hostOf(url: String): String? {
        return try {
            val u = URI(url.trim())
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = u.host ?: return null
            normalizeDomain(host).ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Duração da pausa obrigatória depois de uma sessão: 1/3 da sessão, no mínimo 5 e no máximo 30 minutos.
     * Sem limite de sessão (0) não há pausa.
     */
    fun breakMinutes(sessionMinutes: Int): Int =
        if (sessionMinutes <= 0) 0 else (sessionMinutes / 3).coerceIn(5, 30)

    /** Horário local "HH:mm" de um instante (epoch em ms). */
    fun clockOf(epochMs: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = epochMs
        return c.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0') + ":" +
            c.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
    }

    /** 125 -> "02:05" */
    fun formatTime(seconds: Long): String {
        val s = if (seconds < 0) 0L else seconds
        return (s / 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
    }
}
