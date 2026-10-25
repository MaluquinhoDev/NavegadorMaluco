package com.example.extbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Histórico local do player, sem sincronização. Guarda página e posição, não URLs de CDN. */
internal class PlayerHistory(context: Context) {
    data class Entry(val page: String, val title: String, val positionMs: Long, val updatedAt: Long)
    private val prefs = context.applicationContext.getSharedPreferences("media_history", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<Entry> = runCatching {
        val arr = JSONArray(prefs.getString("entries", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("page"), o.optString("title", "Vídeo"),
                o.optLong("position", 0L), o.optLong("updated", 0L))
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun position(page: String): Long = list().firstOrNull { it.page == page }?.positionMs ?: 0L

    @Synchronized
    fun save(page: String, title: String, positionMs: Long) {
        val uri = runCatching { Uri.parse(page) }.getOrNull() ?: return
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return
        val entry = Entry(page, title.take(90), positionMs.coerceAtLeast(0L), System.currentTimeMillis())
        val entries = (list().filterNot { it.page == page } + entry)
            .sortedByDescending { it.updatedAt }.take(50)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("page", e.page).put("title", e.title)
                .put("position", e.positionMs).put("updated", e.updatedAt))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
