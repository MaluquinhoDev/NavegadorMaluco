package com.example.extbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AttentionTask(val id: String, val title: String, val minutes: Int, val completedDay: String)

/** Recompensas independentes dos contadores e horários do Controle de Atenção. */
object AttentionRewards {
    private const val DAILY_EARN_CAP = 60
    private const val BANK_CAP = 180
    private var prefs: android.content.SharedPreferences? = null
    private val tasks = mutableListOf<AttentionTask>()
    private var balance = 0
    private var earnedDay = ""
    private var earnedToday = 0
    private var allocationDay = ""
    private val allocations = mutableMapOf<String, Int>()

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("attention_rewards", Context.MODE_PRIVATE)
        prefs = p
        try {
            val array = JSONArray(p.getString("tasks", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                tasks.add(AttentionTask(item.getString("id"), item.getString("title"),
                    item.getInt("minutes"), item.optString("completed", "")))
            }
            balance = p.getInt("balance", 0).coerceIn(0, BANK_CAP)
            earnedDay = p.getString("earnedDay", "") ?: ""
            earnedToday = p.getInt("earnedToday", 0)
            allocationDay = p.getString("allocationDay", "") ?: ""
            val a = JSONObject(p.getString("allocations", "{}"))
            for (id in a.keys()) allocations[id] = a.optInt(id, 0).coerceAtLeast(0)
        } catch (_: Exception) {
            tasks.clear(); balance = 0; earnedToday = 0; allocations.clear()
        }
        rollover()
    }

    private fun rollover() {
        val now = today()
        if (earnedDay != now) { earnedDay = now; earnedToday = 0 }
        if (allocationDay != now) { allocationDay = now; allocations.clear() }
    }

    @Synchronized fun tasks(): List<AttentionTask> = tasks.toList()
    @Synchronized fun balance(): Int = balance
    @Synchronized fun earnedRemaining(): Int { rollover(); return (DAILY_EARN_CAP - earnedToday).coerceAtLeast(0) }

    @Synchronized fun add(title: String, minutes: Int): Boolean {
        val clean = title.trim()
        if (clean.isEmpty() || clean.length > 100 || minutes !in 1..60) return false
        tasks.add(AttentionTask(UUID.randomUUID().toString(), clean, minutes, ""))
        save(); return true
    }

    @Synchronized fun remove(id: String) {
        tasks.removeAll { it.id == id }; save()
    }

    @Synchronized fun complete(id: String): Int {
        rollover()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0 || tasks[index].completedDay == today()) return 0
        val granted = minOf(tasks[index].minutes, earnedRemaining(), BANK_CAP - balance)
        if (granted <= 0) return 0
        tasks[index] = tasks[index].copy(completedDay = today())
        balance += granted; earnedToday += granted
        save(); return granted
    }

    /** Resgata antecipadamente para o site; não altera o tempo utilizado no histórico. */
    @Synchronized fun redeem(siteId: String, minutes: Int): Boolean {
        rollover()
        if (minutes !in 1..60 || balance < minutes || siteId.isBlank()) return false
        balance -= minutes
        allocations[siteId] = (allocations[siteId] ?: 0) + minutes
        save(); return true
    }

    /** Os minutos resgatados são uma extensão do limite diário, sem dispensar pausas/horários. */
    @Synchronized fun extraLimitMs(siteId: String): Long {
        rollover()
        return (allocations[siteId] ?: 0).toLong() * 60_000L
    }

    @Synchronized fun allocatedMinutes(siteId: String): Int { rollover(); return allocations[siteId] ?: 0 }

    private fun save() {
        val arr = JSONArray()
        for (t in tasks) arr.put(JSONObject().put("id", t.id).put("title", t.title)
            .put("minutes", t.minutes).put("completed", t.completedDay))
        val a = JSONObject()
        for ((id, min) in allocations) a.put(id, min)
        prefs?.edit()?.putString("tasks", arr.toString())?.putInt("balance", balance)
            ?.putString("earnedDay", earnedDay)?.putInt("earnedToday", earnedToday)
            ?.putString("allocationDay", allocationDay)?.putString("allocations", a.toString())?.apply()
    }
}
