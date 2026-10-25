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

/**
 * Decisão de bloquear: qual site e o motivo mostrado ao usuário.
 * untilMs > 0 indica uma pausa obrigatória que termina nesse instante (epoch em ms).
 */
class AttentionVerdict(val site: AttentionSite, val reason: String, val untilMs: Long = 0L)

class AttentionStatus(
    val site: AttentionSite,
    val usedSeconds: Long,
    val sessionSeconds: Long,
    val sessionLimitSeconds: Long,
    val breakMinutes: Int,
    val allowedNow: Boolean,
)

/** Páginas de bloqueio usadas pelo app (extensões e controle de atenção). */
object BlockPage {
    private const val LINK =
        "display:inline-block;padding:12px 20px;border-radius:8px;background:#eee;color:#111;text-decoration:none"

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

    /**
     * Página da pausa obrigatória, com contagem regressiva e o botão "Dispensar pausa".
     * dismissWaitSec == null: ainda não pediu para dispensar. Caso contrário, é quanto falta esperar.
     */
    fun breakHtml(v: AttentionVerdict, dismissWaitSec: Int?): String {
        val leftSec = (maxOf(0L, v.untilMs - Attention.wallNow()) + 999L) / 1000L
        val id = TextUtils.htmlEncode(v.site.id)
        val domain = TextUtils.htmlEncode(v.site.domain)
        val waiting = dismissWaitSec != null
        val wait = dismissWaitSec ?: 0

        val dismissArea = if (!waiting) {
            "<p id=ask><a href='extbrowser://dispensar/" + id + "' style='" + LINK + "'>Dispensar pausa</a></p>" +
                "<p id=hint style='color:#999'>Dispensar exige esperar " + Attention.DISMISS_WAIT_SECONDS + " s.</p>"
        } else {
            "<p id=w style='color:#999'>Aguarde <b id=wt>" + wait + "</b> s para confirmar.</p>" +
                "<p id=c style='display:none'><a href='extbrowser://confirmar/" + id + "' style='" + LINK +
                "'>Confirmar e continuar</a></p>"
        }

        val script =
            "var left=" + leftSec + ",wl=" + (if (waiting) wait else -1) + ";" +
                "function f(s){var m=Math.floor(s/60),x=s%60;return (m<10?'0':'')+m+':'+(x<10?'0':'')+x}" +
                "function el(i){return document.getElementById(i)}" +
                "function show(i,on){var e=el(i);if(e)e.style.display=on?'block':'none'}" +
                "function upd(){" +
                "el('t').textContent=f(left);" +
                "if(left<=0){show('end',true);show('ask',false);show('hint',false);show('w',false);show('c',false);return}" +
                "if(wl==0){show('w',false);show('c',true)}" +
                "else if(wl>0){var b=el('wt');if(b)b.textContent=wl}" +
                "}" +
                "upd();" +
                "setInterval(function(){if(left>0)left--;if(wl>0)wl--;upd()},1000);"

        return "<!doctype html><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='margin:0;min-height:100vh;display:flex;align-items:center;" +
            "justify-content:center;padding:24px;box-sizing:border-box;background:#111;" +
            "color:#eee;font-family:sans-serif;text-align:center'>" +
            "<main><div style='font-size:64px'>&#9203;</div>" +
            "<h1>Hora de uma pausa</h1>" +
            "<p style='font-size:18px'>" + domain + "</p>" +
            "<p style='font-size:18px'>" + TextUtils.htmlEncode(v.reason) + "</p>" +
            "<p style='font-size:18px'>Volta às " + AttentionLogic.clockOf(v.untilMs) +
            " (faltam <b id=t>--:--</b>)</p>" +
            dismissArea +
            "<p id=end style='display:none'>Pausa concluída.<br><br><a href='https://" + domain +
            "/' style='" + LINK + "'>Voltar ao site</a></p>" +
            "</main><script>" + script + "</script></body>"
    }
}

/**
 * Controle de atenção nativo: limite diário, limite por sessão, horário permitido e pausa
 * obrigatória entre sessões, por site. O tempo só corre enquanto o app está em primeiro plano
 * com uma página monitorada aberta.
 */
object Attention {
    const val DISMISS_WAIT_SECONDS = 30

    private const val MAX_TICK_MS = 15_000L
    private const val KEEP_DAYS = 35
    private const val SESSION_GAP_MS = 5 * 60_000L
    private const val DISMISS_WAIT_MS = DISMISS_WAIT_SECONDS * 1000L

    /** Relógio de parede (usado nas pausas); substituível nos testes. */
    internal var wallNow: () -> Long = { System.currentTimeMillis() }

    private var prefs: SharedPreferences? = null
    private var sites: List<AttentionSite> = emptyList()

    /** dia ("2026-10-23") -> id do site -> milissegundos usados */
    private val usage = HashMap<String, HashMap<String, Long>>()

    /** id do site -> fim da pausa obrigatória (epoch em ms) */
    private val breaks = HashMap<String, Long>()

    /** id do site -> instante (elapsedRealtime) em que o usuário pediu para dispensar a pausa */
    private val dismissAsked = HashMap<String, Long>()

    private var currentId: String? = null
    private var sessionMs = 0L
    private var lastTickMs = 0L

    /** Última sessão encerrada, para continuar se o usuário voltar em até 5 minutos. */
    private class SavedSession(val id: String, val ms: Long, val endedAt: Long)

    private var saved: SavedSession? = null

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
            val b = JSONObject(p.getString("breaks", "{}") ?: "{}")
            for (id in b.keys()) breaks[id] = b.getLong(id)
            val s = p.getString("session", null)
            if (s != null) {
                val o = JSONObject(s)
                saved = SavedSession(o.getString("id"), o.getLong("ms"), o.getLong("at"))
            }
        } catch (e: Exception) {
            sites = emptyList()
            usage.clear()
            breaks.clear()
            saved = null
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
        forgetSession(id)
        sites = sites.filter { it.id != id }
        for (day in usage.values) day.remove(id)
        breaks.remove(id)
        dismissAsked.remove(id)
        saveSites()
        saveUsage()
        saveBreaks()
    }

    /**
     * Contabiliza somente reprodução efetiva do Media3, inclusive em PiP/background.
     * O relógio e a cota diária são compartilhados com a navegação.
     * A sessão multimídia é mantida separada para não reativar a WebView em segundo plano.
     */
    private var mediaSiteId: String? = null
    private var mediaSessionMs = 0L

    @Synchronized
    fun mediaPlaybackTick(pageUrl: String?, elapsedMs: Long): AttentionVerdict? {
        val host = pageUrl?.let { AttentionLogic.hostOf(it) } ?: run {
            mediaSiteId = null
            mediaSessionMs = 0L
            return null
        }
        val site = findSite(host) ?: run {
            mediaSiteId = null
            mediaSessionMs = 0L
            return null
        }
        if (mediaSiteId != site.id) {
            mediaSiteId = site.id
            mediaSessionMs = 0L
        }
        navigationBlock(site)?.let { return it }
        val delta = elapsedMs.coerceIn(0L, MAX_TICK_MS)
        if (delta > 0L) {
            addUsage(site.id, delta)
            mediaSessionMs += delta
        }
        if (site.sessionMinutes > 0 && mediaSessionMs >= site.sessionMinutes * 60_000L) {
            val until = wallNow() + AttentionLogic.breakMinutes(site.sessionMinutes) * 60_000L
            breaks[site.id] = until
            saveBreaks()
            return AttentionVerdict(site, "A duração máxima desta sessão foi atingida.", until)
        }
        return navigationBlock(site)
    }

    /**
     * Contagem para vídeo HTML5 em aba não selecionada. A fonte é o domínio da
     * aba, nunca o endereço CDN. Usa o mesmo limite diário e de sessão.
     * O chamador só informa tempo quando detectou reprodução efetiva.
     */
    private val backgroundSessions = HashMap<String, Long>()

    @Synchronized
    fun backgroundTabPlaybackTick(pageUrl: String?, elapsedMs: Long): AttentionVerdict? {
        val host = pageUrl?.let { AttentionLogic.hostOf(it) } ?: return null
        val site = findSite(host) ?: return null
        navigationBlock(site)?.let { return it }
        val delta = elapsedMs.coerceIn(0L, MAX_TICK_MS)
        if (delta > 0L) {
            addUsage(site.id, delta)
            val session = (backgroundSessions[site.id] ?: 0L) + delta
            backgroundSessions[site.id] = session
            if (site.sessionMinutes > 0 && session >= site.sessionMinutes * 60_000L) {
                val until = wallNow() + AttentionLogic.breakMinutes(site.sessionMinutes) * 60_000L
                breaks[site.id] = until
                saveBreaks()
                backgroundSessions.remove(site.id)
                return AttentionVerdict(site, "A duração máxima desta sessão foi atingida.", until)
            }
        }
        return navigationBlock(site)
    }

    /** Tempo disponível da sessão de mídia do site, com limites diário e por sessão. */
    @Synchronized
    fun mediaRemainingSeconds(pageUrl: String?): Long? {
        val host = pageUrl?.let { AttentionLogic.hostOf(it) } ?: return null
        val site = findSite(host) ?: return null
        if (navigationBlock(site) != null) return 0L
        val daily = if (site.dailyMinutes > 0)
            (site.dailyMinutes * 60_000L - usedMs(site.id)).coerceAtLeast(0L)
        else Long.MAX_VALUE
        val session = if (site.sessionMinutes > 0)
            (site.sessionMinutes * 60_000L -
                (if (mediaSiteId == site.id) mediaSessionMs else 0L)).coerceAtLeast(0L)
        else Long.MAX_VALUE
        val left = minOf(daily, session)
        return if (left == Long.MAX_VALUE) null else (left + 999L) / 1000L
    }

    @Synchronized
    fun pauseRemainingSeconds(pageUrl: String?): Long {
        val host = pageUrl?.let { AttentionLogic.hostOf(it) } ?: return 0L
        val site = findSite(host) ?: return 0L
        return (breakLeftMs(site) + 999L) / 1000L
    }

    @Synchronized
    fun mediaPlaybackReset() {
        mediaSiteId = null
        mediaSessionMs = 0L
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

    /**
     * Quanto falta da pausa obrigatória. Se o relógio do aparelho foi atrasado, a pausa nunca
     * passa da duração prevista para o site.
     */
    private fun breakLeftMs(site: AttentionSite): Long {
        val until = breaks[site.id] ?: return 0L
        val remaining = until - wallNow()
        if (remaining <= 0L) return 0L
        val cap = AttentionLogic.breakMinutes(site.sessionMinutes) * 60_000L
        return if (cap <= 0L) 0L else minOf(remaining, cap)
    }

    /** O site pode ser aberto agora? Devolve o bloqueio (horário, limite diário ou pausa) ou null. */
    private fun navigationBlock(site: AttentionSite): AttentionVerdict? {
        if (!AttentionLogic.withinTime(site.start, site.end, nowMinutes())) {
            return AttentionVerdict(site, "Este site está fora do horário permitido.")
        }
        if (site.dailyMinutes > 0 && usedMs(site.id) >= site.dailyMinutes * 60_000L) {
            return AttentionVerdict(site, "O limite diário foi atingido.")
        }
        val left = breakLeftMs(site)
        if (left > 0L) {
            return AttentionVerdict(site, "Pausa entre sessões em andamento.", wallNow() + left)
        }
        return null
    }

    /** Usado antes de carregar uma página: ela pode abrir? (não altera nenhum contador) */
    @Synchronized
    fun checkNavigation(url: String): AttentionVerdict? {
        val host = AttentionLogic.hostOf(url) ?: return null
        val site = findSite(host) ?: return null
        return navigationBlock(site)
    }

    /** Bloqueio atual de um site pelo id (para redesenhar a página de pausa). */
    @Synchronized
    fun verdictForSite(id: String): AttentionVerdict? {
        val site = sites.firstOrNull { it.id == id } ?: return null
        return navigationBlock(site)
    }

    @Synchronized
    fun status(url: String?): AttentionStatus? {
        val host = url?.let { AttentionLogic.hostOf(it) } ?: return null
        val site = findSite(host) ?: return null
        val session = if (currentId == site.id) sessionMs / 1000 else 0L
        val allowed = AttentionLogic.withinTime(site.start, site.end, nowMinutes())
        return AttentionStatus(
            site, usedMs(site.id) / 1000, session, site.sessionMinutes * 60L,
            AttentionLogic.breakMinutes(site.sessionMinutes), allowed,
        )
    }

    @Synchronized
    fun usedTodaySeconds(id: String): Long = usedMs(id) / 1000

    // ---------- Dispensar a pausa (com espera) ----------

    /** O usuário tocou em "Dispensar pausa": começa a espera de 30 s. */
    @Synchronized
    fun requestDismiss(id: String) {
        val site = sites.firstOrNull { it.id == id } ?: return
        if (breakLeftMs(site) > 0L) dismissAsked[id] = SystemClock.elapsedRealtime()
    }

    /** Segundos que ainda faltam para poder confirmar; null se não pediu para dispensar. */
    @Synchronized
    fun dismissWaitLeft(id: String): Int? {
        val at = dismissAsked[id] ?: return null
        val left = DISMISS_WAIT_MS - (SystemClock.elapsedRealtime() - at)
        return if (left <= 0L) 0 else ((left + 999L) / 1000L).toInt()
    }

    /** Confirma a dispensa; só vale se os 30 s de espera já passaram. */
    @Synchronized
    fun confirmDismiss(id: String): Boolean {
        val at = dismissAsked[id] ?: return false
        if (SystemClock.elapsedRealtime() - at < DISMISS_WAIT_MS) return false
        dismissAsked.remove(id)
        breaks.remove(id)
        saveBreaks()
        forgetSession(id)
        Log.i("Attention", "pausa dispensada: " + id)
        return true
    }

    // ---------- Sessão ----------

    /**
     * Informa a página atual. Inicia ou encerra a sessão conforme o site; uma sessão interrompida
     * por até 5 minutos continua de onde parou.
     * Devolve um bloqueio se a página atual não deveria estar aberta (horário, limite diário ou pausa).
     */
    @Synchronized
    fun onUrl(url: String?): AttentionVerdict? {
        val host = url?.let { AttentionLogic.hostOf(it) }
        val site = host?.let { findSite(it) }
        if (site == null) {
            endSession()
            return null
        }
        val block = navigationBlock(site)
        if (block != null) {
            endSession()
            forgetSession(site.id)
            Log.i("Attention", "página bloqueada: " + site.domain + " - " + block.reason)
            return block
        }
        if (currentId != site.id) {
            endSession()
            val s = saved
            val gap = wallNow() - (s?.endedAt ?: 0L)
            sessionMs = if (s != null && s.id == site.id && gap >= 0L && gap <= SESSION_GAP_MS) s.ms else 0L
            currentId = site.id
            lastTickMs = SystemClock.elapsedRealtime()
            Log.i("Attention", "sessão iniciada: " + site.domain + " (já em " + sessionMs / 1000 + " s)")
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

        if (!AttentionLogic.withinTime(site.start, site.end, nowMinutes())) {
            return stopWith(site, "Este site está fora do horário permitido.")
        }
        if (site.dailyMinutes > 0 && usedMs(id) >= site.dailyMinutes * 60_000L) {
            return stopWith(site, "O limite diário foi atingido.")
        }
        if (site.sessionMinutes > 0 && sessionMs >= site.sessionMinutes * 60_000L) {
            val pauseMs = AttentionLogic.breakMinutes(site.sessionMinutes) * 60_000L
            val until = wallNow() + pauseMs
            breaks[id] = until
            saveBreaks()
            val v = stopWith(site, "A duração máxima desta sessão foi atingida.", until)
            Log.i("Attention", "pausa de " + pauseMs / 60_000L + " min até " + AttentionLogic.clockOf(until))
            return v
        }
        return null
    }

    private fun stopWith(site: AttentionSite, reason: String, untilMs: Long = 0L): AttentionVerdict {
        endSession()
        forgetSession(site.id)
        Log.i("Attention", "sessão encerrada por limite: " + site.domain + " - " + reason)
        return AttentionVerdict(site, reason, untilMs)
    }

    /** O app saiu da tela: conta o último trecho e guarda a sessão (continua se voltar logo). */
    @Synchronized
    fun pause() {
        tick()
        endSession()
    }

    /** Encerra o acompanhamento e guarda a sessão para uma possível volta rápida. */
    private fun endSession() {
        val id = currentId
        if (id != null && sessionMs > 0L) {
            saved = SavedSession(id, sessionMs, wallNow())
            saveSession()
        }
        currentId = null
        sessionMs = 0L
    }

    /** Esquece a sessão guardada do site (limite atingido, pausa dispensada ou site removido). */
    private fun forgetSession(id: String) {
        if (saved?.id == id) {
            saved = null
            saveSession()
        }
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

    private fun saveBreaks() {
        val o = JSONObject()
        for ((id, until) in breaks) o.put(id, until)
        prefs?.edit()?.putString("breaks", o.toString())?.apply()
    }

    private fun saveSession() {
        val s = saved
        val text = if (s == null) null else JSONObject().put("id", s.id).put("ms", s.ms).put("at", s.endedAt).toString()
        prefs?.edit()?.putString("session", text)?.apply()
    }
}
