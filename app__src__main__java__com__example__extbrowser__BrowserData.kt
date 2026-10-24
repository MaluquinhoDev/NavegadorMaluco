package com.example.extbrowser

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedPage(val url: String, val title: String, val visitedAt: Long = 0L)

/** Armazenamento privado para favoritos e histórico de navegação. */
class BrowserData(context: Context) : SQLiteOpenHelper(context, "browser_data.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE favorites (url TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT NOT NULL, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_date ON history(visited_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addFavorite(url: String, title: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        writableDatabase.execSQL("INSERT OR REPLACE INTO favorites(url,title,created_at) VALUES(?,?,?)", arrayOf(url, title, System.currentTimeMillis()))
    }
    fun removeFavorite(url: String) { writableDatabase.delete("favorites", "url=?", arrayOf(url)) }
    fun isFavorite(url: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE url=? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }
    fun favorites(): List<SavedPage> = query("SELECT url,title,created_at FROM favorites ORDER BY created_at DESC LIMIT 1000")
    fun addHistory(url: String, title: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val db = writableDatabase
        db.execSQL("INSERT INTO history(url,title,visited_at) VALUES(?,?,?)", arrayOf(url, title, System.currentTimeMillis()))
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 5000)")
    }
    fun history(): List<SavedPage> = query("SELECT url,title,visited_at FROM history ORDER BY visited_at DESC LIMIT 1000")
    fun clearHistory() { writableDatabase.delete("history", null, null) }
    fun deleteHistory(url: String) { writableDatabase.delete("history", "url=?", arrayOf(url)) }
    private fun query(sql: String): List<SavedPage> = readableDatabase.rawQuery(sql, null).use { c ->
        val result = mutableListOf<SavedPage>()
        while (c.moveToNext()) result.add(SavedPage(c.getString(0), c.getString(1), c.getLong(2)))
        result
    }
}
