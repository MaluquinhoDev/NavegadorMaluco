package com.example.extbrowser

/** Suporte a match patterns no estilo WebExtensions: <all_urls>, *://*.exemplo.com/*, https://a.com/x/* */
object MatchPattern {
    private val PATTERN = Regex("^(\\*|https?)://(\\*|\\*\\.[^/*]+|[^/*]+)(/.*)$")

    data class Parsed(val scheme: String, val host: String, val path: String)

    fun parse(p: String): Parsed? {
        if (p == "<all_urls>") return Parsed("*", "*", "/*")
        val m = PATTERN.matchEntire(p) ?: return null
        return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in "\\.+?^$|(){}[]") sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** Regex (sintaxe compatível com JS e Kotlin) que casa com a URL completa. */
    fun toRegex(p: String): String? {
        val x = parse(p) ?: return null
        val scheme = if (x.scheme == "*") "https?" else x.scheme
        val host = when {
            x.host == "*" -> "[^/]+?"
            x.host.startsWith("*.") -> "([^/]+\\.)?" + esc(x.host.substring(2))
            else -> esc(x.host)
        }
        val path = esc(x.path).replace("*", ".*")
        return "^" + scheme + "://" + host + "(:\\d+)?" + path + "$"
    }

    /** Verificação em nível de origem (usada pela ponte Kotlin). */
    fun matchesOrigin(p: String, scheme: String?, host: String?): Boolean {
        val x = parse(p) ?: return false
        if (scheme == null || host == null) return false
        val schemeOk = if (x.scheme == "*") scheme == "http" || scheme == "https" else scheme == x.scheme
        val hostOk = when {
            x.host == "*" -> true
            x.host.startsWith("*.") -> {
                val base = x.host.substring(2)
                host == base || host.endsWith(".$base")
            }
            else -> host == x.host
        }
        return schemeOk && hostOk
    }

    /** Regras de origem aceitas por addWebMessageListener / addDocumentStartJavaScript. */
    fun originRules(p: String): List<String> {
        val x = parse(p) ?: return emptyList()
        if (x.host == "*") return listOf("*")
        val schemes = if (x.scheme == "*") listOf("http", "https") else listOf(x.scheme)
        val hosts = if (x.host.startsWith("*.")) listOf(x.host.substring(2), x.host) else listOf(x.host)
        return schemes.flatMap { s -> hosts.map { h -> "$s://$h" } }
    }
}
