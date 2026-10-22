package com.example.extbrowser

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Recebe as chamadas do JS, valida a origem e executa a operação. */
class BridgeRouter(private val context: Context, extensions: List<Extension>) {
    private val exts = extensions.associateBy { it.id }

    fun handle(raw: String, origin: Uri): String {
        val resp = JSONObject()
        try {
            val req = JSONObject(raw)
            resp.put("id", req.optInt("id"))
            val ext = exts[req.optString("ext")] ?: error("extensão desconhecida")
            if (!ext.allowsOrigin(origin.scheme, origin.host)) error("origem não permitida para esta extensão")
            val payload = req.opt(
                "payload"
            )
            val result: Any? = when (val op = req.optString("op")) {
                "storage.get" -> storageGet(ext.id, payload)
                "storage.set" -> storageSet(ext.id, payload as JSONObject)
                "storage.remove" -> storageRemove(ext.id, payload)
                "storage.clear" -> { save(ext.id, JSONObject()); null }
                "runtime.sendMessage" -> {
                    Log.i("ExtBridge", "[${ext.id}] mensagem: $payload")
                    JSONObject().put("ok", true).put("echo", payload)
                }
                else -> error("operação desconhecida: $op")
            }
            resp.put("ok", true).put("result", result ?: JSONObject.NULL)
        } catch (e: Exception) {
            resp.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        return resp.toString()
    }

    private fun prefs(id: String) = context.getSharedPreferences("ext_$id", Context.MODE_PRIVATE)
    private fun load(id: String) = JSONObject(prefs(id).getString("data", "{}") ?: "{}")
    private fun save(id: String, o: JSONObject) = prefs(id).edit().putString("data", o.toString()).apply()

    private fun storageGet(id: String, keys: Any?): JSONObject {
        val all = load(id)
        val out = JSONObject()
        when (keys) {
            null, JSONObject.NULL -> return all
            is String -> if (all.has(keys)) out.put(keys, all.get(keys))
            is JSONArray -> for (i in 0 until keys.length()) {
                val k = keys.getString(i)
                if (all.has(k)) out.put(k, all.get(k))
            }
            is JSONObject -> keys.keys().forEach { k -> out.put(k, if (all.has(k)) all.get(k) else keys.get(k)) }
        }
        return out
    }

    private fun storageSet(id: String, values: JSONObject): Any? {
        val all = load(id)
        values.keys().forEach { all.put(it, values.get(it)) }
        save(id, all)
        return null
    }

    private fun storageRemove(id: String, keys: Any?): Any? {
        val all = load(id)
        when (keys) {
            is String -> all.remove(keys)
            is JSONArray -> for (i in 0 until keys.length()) all.remove(keys.getString(i))
        }
        save(id, all)
        return null
    }
}
