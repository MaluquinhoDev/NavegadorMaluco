package com.example.extbrowser

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ContentScript(
    val matches: List<String>,
    val regexes: List<String>,
    val js: String,
    val css: String,
    val runAt: String,
)

data class Extension(
    val id: String,
    val name: String,
    val dir: File,
    val scripts: List<ContentScript>,
    val blockRegexes: List<String> = emptyList(),
) {
    fun allowsOrigin(scheme: String?, host: String?) =
        scripts.any { s -> s.matches.any { MatchPattern.matchesOrigin(it, scheme, host) } }
}

class ExtensionManager(private val context: Context) {
    private val tag = "ExtManager"
    val root = File(context.filesDir, "extensions")

    /** Copia para filesDir/extensions cada extensão de assets/extensions que ainda não existe lá. */
    fun seedFromAssets() {
        root.mkdirs()
        for (name in context.assets.list("extensions") ?: emptyArray()) {
            val dest = File(root, name)
            if (!dest.exists()) copyAsset("extensions/$name", dest)
        }
    }

    private fun copyAsset(path: String, dest: File) {
        val children = context.assets.list(path)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAsset("$path/$it", File(dest, it)) }
        }
    }

    fun load(): List<Extension> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                try {
                    parse(dir)
                } catch (e: Exception) {
                    Log.e(tag, "Falha ao carregar ${dir.name}", e)
                    null
                }
            }

    private fun readInside(dir: File, rel: String): String {
        val f = File(dir, rel).canonicalFile
        require(f.path.startsWith(dir.canonicalPath + File.separator)) { "Caminho fora da extensão: $rel" }
        return f.readText()
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    private fun parse(dir: File): Extension {
        val m = JSONObject(File(dir, "manifest.json").readText())
        val arr = m.optJSONArray("content_scripts")
        val scripts = if (arr == null) emptyList() else List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val matches = o.optJSONArray("matches").strings()
            val regexes = matches.map { MatchPattern.toRegex(it) ?: error("Match pattern inválido: $it") }
            ContentScript(
                matches = matches,
                regexes = regexes,
                js = o.optJSONArray("js").strings().joinToString("\n;\n") { readInside(dir, it) },
                css = o.optJSONArray("css").strings().joinToString("\n") { readInside(dir, it) },
                runAt = o.optString("run_at", "document_idle"),
            )
        }
        val blocks = m.optJSONArray("block_requests").strings()
            .map { MatchPattern.toRegex(it) ?: error("Match pattern inválido: $it") }
        return Extension(dir.name, m.optString("name", dir.name), dir, scripts, blocks)
    }

    /** Origens onde a ponte e o bootstrap serão expostos. */
    fun allowedOrigins(exts: List<Extension>): Set<String> {
        val rules = exts.flatMap { e -> e.scripts.flatMap { s -> s.matches.flatMap { MatchPattern.originRules(it) } } }.toSet()
        return if ("*" in rules) setOf("*") else rules
    }

    /** Gera o script único injetado em document-start. */
    fun buildBootstrap(exts: List<Extension>): String {
        val sb = StringBuilder()
        for (e in exts) for (s in e.scripts) {
            sb.append("registry.push({id:").append(JSONObject.quote(e.id))
                .append(",patterns:").append(JSONArray(s.regexes))
                .append(",runAt:").append(JSONObject.quote(s.runAt))
                .append(",css:").append(JSONObject.quote(s.css))
                .append(",run:function(browser){\n").append(s.js).append("\n}});\n")
        }
        return BOOTSTRAP.replace("/*__REGISTRY__*/", sb.toString())
    }

    private companion object {
        val BOOTSTRAP = """
(function () {
  if (window.top !== window) return;
  var BRIDGE = '__extBridge';
  var pending = {}, seq = 0, hooked = false;

  function hook() {
    var b = window[BRIDGE];
    if (!b) return null;
    if (!hooked) {
      hooked = true;
      b.onmessage = function (ev) {
        var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
        var p = pending[m.id]; if (!p) return;
        delete pending[m.id];
        if (m.ok) p.res(m.result); else p.rej(new Error(m.error || 'erro'));
      };
    }
    return b;
  }

  function call(ext, op, payload) {
    return new Promise(function (res, rej) {
      var b = hook();
      if (!b) { rej(new Error('ponte indisponivel')); return; }
      var id = ++seq;
      pending[id] = { res: res, rej: rej };
      b.postMessage(JSON.stringify({ id: id, ext: ext, op: op, payload: payload === undefined ? null : payload }));
    });
  }

  function makeApi(ext) {
    return {
      runtime: { sendMessage: function (m) { return call(ext, 'runtime.sendMessage', m); } },
      storage: { local: {
        get: function (k) { return call(ext, 'storage.get', k === undefined ? null : k); },
        set: function (o) { return call(ext, 'storage.set', o); },
        remove: function (k) { return call(ext, 'storage.remove', k); },
        clear: function () { return call(ext, 'storage.clear', null); }
      } }
    };
  }

  function whenRoot(fn) {
    if (document.documentElement) { fn(); return; }
    var mo = new MutationObserver(function () {
      if (document.documentElement) { mo.disconnect(); fn(); }
    });
    mo.observe(document, { childList: true });
  }

  function whenReady(fn) {
    if (document.readyState !== 'loading') { fn(); return; }
    document.addEventListener('DOMContentLoaded', fn, { once: true });
  }

  var registry = [];
  /*__REGISTRY__*/

  var href = location.href;
  registry.forEach(function (e) {
    var ok = e.patterns.some(function (p) { return new RegExp(p).test(href); });
    if (!ok) return;
    if (e.css) whenRoot(function () {
      var st = document.createElement('style');
      st.textContent = e.css;
      document.documentElement.appendChild(st);
    });
    var go = function () {
      try { e.run(makeApi(e.id)); } catch (err) { console.error('[ext:' + e.id + ']', err); }
    };
    if (e.runAt === 'document_start') go(); else whenReady(go);
  });
})();
""".trimIndent()
    }
}
