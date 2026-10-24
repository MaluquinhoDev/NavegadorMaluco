package com.example.extbrowser

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class AttentionSite(
    val id: String,
    val domain: String,
    val dailyMinutes: Int,
    val sessionMinutes: Int,
    val start: String,
    val end: String,
)

/** Decisão de bloquear: qual site e o motivo mostrado ao usuário. */
class AttentionVerdict(val site: AttentionSite, val reason: String)

class AttentionStatus(
    val site: AttentionSite,
    val usedSeconds: Long,
    val sessionSeconds: Long,
    val allowedNow: Boolean,
)

/** Página de bloqueio usada pelo app (extensões e controle de atenção). */
object BlockPage {
    fun html(heading: String, line1: String, line2: String): String =
        "<!doctype html><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;min-height:100vh;display:flex;align-items:center;" +
            "justify-content:center;padding:24px;box-sizing:border-box;background:#111;" +
            "color:#eee;font-family:sans-serif;text-align:center'>" +
            "<main><div style='font-size:64px'>&#9203;</div>" +
            "<h1>" + TextUtils.htmlEncode(heading) + "</h1>" +
            "<p style='font-size:18px'>" + TextUtils.htmlEncode(line1) + "</p>" +
            "<p style='font-size:18px'>" + TextUtils.htmlEncode(line2) + "</p>" +
            "</main></body>"
}

/**
 * Controle de atenção nativo: limite diário, limite por sessão e horário permitido por site.
 * O tempo só corre enquanto o app está em primeiro plano e com uma página monitorada aberta.
 */
object Attention {
    private const val MAX_TICK_MS = 15_000L
    private const val KEEP_DAYS = 35

    private var prefs: SharedPreferences? = null
    private var sites: List<AttentionSite> = emptyList()

    /** dia ("2026-10-23") -> id do site -> milissegundos usados */
    private val usage = HashMap<String, HashMap<String, Long>>()

    private var currentId: String? = null
    private var sessionMs = 0L
    private var lastTickMs = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("attention", Context.MODE_PRIVATE)
        prefs = p
        try {
            val arr = JSONArray(p.getString("sites", "[]") ?: "[]")
            sites = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AttentionSite(
                    id = o.getString("id"),
                    domain = o.getString("domain"),
                    dailyMinutes = o.optInt("daily", 0),
                    sessionMinutes = o.optInt("session", 0),
                    start = o.optString("start", ""),
                    end = o.optString("end", ""),
                )
            }
            val u = JSONObject(p.getString("usage", "{}") ?: "{}")
            for (day in u.keys()) {
                val m = HashMap<String, Long>()
                val d = u.getJSONObject(day)
                for (id in d.keys()) m[id] = d.getLong(id)
                usage[day] = m
            }
        } catch (e: Exception) {
            sites = emptyList()
            usage.clear()
        }
    }

    // ---------- Configuração ----------

    @Synchronized
    fun sites(): List<AttentionSite> = sites.toList()

    /** Devolve uma mensagem de erro, ou null se o site foi adicionado. */
    @Synchronized
    fun addSite(rawDomain: String, daily: Int, session: Int, start: String, end: String): String? {
        val domain = AttentionLogic.normalizeDomain(rawDomain)
        if (domain.isEmpty() || !domain.contains('.')) return "Informe um domínio válido, como youtube.com."
        if (daily < 0 || session < 0) return "Os limites não podem ser negativos."
        val s = start.trim()
        val e = end.trim()
        if (s.isEmpty() != e.isEmpty()) return "Preencha início e fim, ou deixe os dois vazios."
        if (s.isNotEmpty() && (AttentionLogic.minutesOf(s) == null || AttentionLogic.minutesOf(e) == null)) {
            return "Use o formato HH:mm nos horários (por exemplo 18:00)."
        }
        if (sites.any { it.domain == domain }) return "Este site já está configurado."
        sites = sites + AttentionSite(UUID.randomUUID().toString(), domain, daily, session, s, e)
        saveSites()
        return null
    }

    @Synchronized
    fun removeSite(id: String) {
        if (currentId == id) endSession()
        sites = sites.filter { it.id != id }
        for (day in usage.values) day.remove(id)
        saveSites()
        saveUsage()
    }

    // ---------- Consulta ----------

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR).toString() + "-" +
            (c.get(Calendar.MONTH) + 1).toString().padStart(2, '0') + "-" +
            c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
    }

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun findSite(host: String): AttentionSite? =
        sites.firstOrNull { AttentionLogic.domainMatches(host, it.domain) }

    private fun usedMs(id: String): Long = usage[todayKey()]?.get(id) ?: 0L

    private fun navigationReason(site: AttentionSite): String? {
        if (!AttentionLogic.withinTime(site.start, site.end, nowMinutes())) {
            return "Este site está fora do horário permitido."
        }
        if (site.dailyMinutes > 0 && usedMs(site.id) >= site.dailyMinutes * 60_000L) {
            return "O limite diário foi atingido."
        }
        return null
    }

    /** Usado antes de carregar uma página: ela pode abrir? (não altera nenhum contador) */
    @Synchronized
    fun checkNavigation(url: String): AttentionVerdict? {
        val host = AttentionLogic.hostOf(url) ?: return null
        val site = findSite(host) ?: return null
        val reason = navigationReason(site) ?: return null
        return AttentionVerdict(site, reason)
    }

    @Synchronized
    fun status(url: String?): AttentionStatus? {
        val host = url?.let { AttentionLogic.hostOf(it) } ?: return null
        val site = findSite(host) ?: return null
        val session = if (currentId == site.id) sessionMs / 1000 else 0L
        val allowed = AttentionLogic.withinTime(site.start, site.end, nowMinutes())
        return AttentionStatus(site, usedMs(site.id) / 1000, session, allowed)
    }

    @Synchronized
    fun usedTodaySeconds(id: String): Long = usedMs(id) / 1000

    // ---------- Sessão ----------

    /**
     * Informa a página atual. Inicia ou encerra a sessão conforme o site.
     * Devolve um AttentionVerdict se a página atual não deveria estar aberta (horário ou limite diário).
     */
    @Synchronized
    fun onUrl(url: String?): AttentionVerdict? {
        val host = url?.let { AttentionLogic.hostOf(it) }
        val site = host?.let { findSite(it) }
        if (site == null) {
            endSession()
            return null
        }
        val reason = navigationReason(site)
        if (reason != null) {
            endSession()
            Log.i("Attention", "página bloqueada: " + site.domain + " - " + reason)
            return AttentionVerdict(site, reason)
        }
        if (currentId != site.id) {
            endSession()
            currentId = site.id
            sessionMs = 0L
            lastTickMs = SystemClock.elapsedRealtime()
            Log.i("Attention", "sessão iniciada: " + site.domain)
        }
        return null
    }

    /** Conta o tempo decorrido (no máximo 15 s por chamada) e verifica os limites da sessão ativa. */
    @Synchronized
    fun tick(): AttentionVerdict? {
        val id = currentId ?: return null
        val site = sites.firstOrNull { it.id == id }
        if (site == null) {
            endSession()
            return null
        }
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTickMs).coerceIn(0L, MAX_TICK_MS)
        lastTickMs = now
        addUsage(id, delta)
        sessionMs += delta

        val reason = when {
            !AttentionLogic.withinTime(site.start, site.end, nowMinutes()) ->
                "Este site está fora do horário permitido."
            site.dailyMinutes > 0 && usedMs(id) >= site.dailyMinutes * 60_000L ->
                "O limite diário foi atingido."
            site.sessionMinutes > 0 && sessionMs >= site.sessionMinutes * 60_000L ->
                "A duração máxima desta sessão foi atingida."
            else -> null
        }
        if (reason != null) {
            endSession()
            Log.i("Attention", "sessão encerrada por limite: " + site.domain + " - " + reason)
            return AttentionVerdict(site, reason)
        }
        return null
    }

    /** O app saiu da tela: conta o último trecho e encerra a sessão. */
    @Synchronized
    fun pause() {
        tick()
        endSession()
    }

    private fun endSession() {
        currentId = null
        sessionMs = 0L
    }

    // ---------- Persistência ----------

    private fun addUsage(id: String, ms: Long) {
        if (ms <= 0L) return
        val day = usage.getOrPut(todayKey()) { HashMap() }
        day[id] = (day[id] ?: 0L) + ms
        if (usage.size > KEEP_DAYS) {
            usage.keys.sorted().take(usage.size - KEEP_DAYS).forEach { usage.remove(it) }
        }
        saveUsage()
    }

    private fun saveSites() {
        val arr = JSONArray()
        for (s in sites) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("domain", s.domain)
                    .put("daily", s.dailyMinutes)
                    .put("session", s.sessionMinutes)
                    .put("start", s.start)
                    .put("end", s.end),
            )
        }
        prefs?.edit()?.putString("sites", arr.toString())?.apply()
    }

    private fun saveUsage() {
        val o = JSONObject()
        for ((day, m) in usage) {
            val d = JSONObject()
            for ((id, ms) in m) d.put(id, ms)
            o.put(day, d)
        }
        prefs?.edit()?.putString("usage", o.toString())?.apply()
    }
}
