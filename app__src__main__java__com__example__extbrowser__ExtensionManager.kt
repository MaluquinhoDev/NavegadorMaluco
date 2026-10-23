package com.example.extbrowser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

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

/** Linha da tela de extensões. Se error != null, o manifest não pôde ser lido. */
data class ExtEntry(val id: String, val name: String, val enabled: Boolean, val error: String?)

class ExtensionManager(private val context: Context) {
    private val tag = "ExtManager"
    val root = File(context.filesDir, "extensions")

    private val prefs get() = context.getSharedPreferences("ext_manager", Context.MODE_PRIVATE)

    private fun disabledIds(): Set<String> =
        (prefs.getStringSet("disabled", emptySet()) ?: emptySet()).toSet()

    fun setEnabled(id: String, on: Boolean) {
        val s = disabledIds().toMutableSet()
        if (on) s.remove(id) else s.add(id)
        prefs.edit().putStringSet("disabled", s).apply()
    }

    /** Copia cada extensão de assets/extensions uma única vez (se você remover, ela não volta). */
    fun seedFromAssets() {
        root.mkdirs()
        val seeded = (prefs.getStringSet("seeded", emptySet()) ?: emptySet()).toMutableSet()
        for (name in context.assets.list("extensions") ?: emptyArray()) {
            if (name in seeded) continue
            val dest = File(root, name)
            if (!dest.exists()) copyAsset("extensions/$name", dest)
            seeded.add(name)
        }
        prefs.edit().putStringSet("seeded", seeded).apply()
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

    private fun dirs(): List<File> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }

    /** Todas as extensões instaladas, para a tela de gerenciamento. */
    fun entries(): List<ExtEntry> {
        val off = disabledIds()
        return dirs().map { d ->
            try {
                ExtEntry(d.name, parse(d).name, d.name !in off, null)
            } catch (e: Exception) {
                ExtEntry(d.name, d.name, d.name !in off, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Somente as extensões ligadas e com manifest válido. */
    fun loadEnabled(): List<Extension> {
        val off = disabledIds()
        return dirs().filter { it.name !in off }.mapNotNull { dir ->
            try {
                parse(dir)
            } catch (e: Exception) {
                Log.e(tag, "Falha ao carregar ${dir.name}", e)
                null
            }
        }
    }

    fun remove(id: String) {
        val d = File(root, id).canonicalFile
        require(d.parentFile == root.canonicalFile) { "id inválido" }
        d.deleteRecursively()
        context.deleteSharedPreferences("ext_$id")
        setEnabled(id, true)
    }

    // ---------- Importação ----------

    /** Importa um .zip escolhido no seletor de arquivos. Devolve o id da extensão. */
    fun importFromZip(uri: Uri): String {
        val stage = newStage()
        try {
            val out = File(stage, "x").also { it.mkdirs() }
            extractZip(uri, out)
            val name = Regex("(?i)\\.zip$").replace(displayName(uri) ?: "ext", "")
            return install(findManifestRoot(out), name)
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Importa uma pasta escolhida no seletor de pastas. Devolve o id da extensão. */
    fun importFromTree(tree: Uri): String {
        val doc = DocumentFile.fromTreeUri(context, tree) ?: error("pasta inválida")
        val stage = newStage()
        try {
            val out = File(stage, "x").also { it.mkdirs() }
            copyTree(doc, out, Budget(), 0)
            return install(findManifestRoot(out), doc.name ?: "ext")
        } finally {
            stage.deleteRecursively()
        }
    }

    private class Budget {
        var bytes = 0L
        var files = 0
    }

    private fun newStage(): File =
        File(context.filesDir, "staging").also { it.deleteRecursively(); it.mkdirs() }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun sanitize(s: String): String {
        val t = s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(64)
        return t.ifBlank { "ext" }
    }

    private fun findManifestRoot(dir: File): File {
        if (File(dir, "manifest.json").isFile) return dir
        val subs = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }
        if (subs.size == 1 && File(subs[0], "manifest.json").isFile) return subs[0]
        error("manifest.json não encontrado na raiz da extensão")
    }

    /** Valida o manifest (e os arquivos que ele cita) antes de instalar; substitui versão anterior. */
    private fun install(src: File, suggestedName: String): String {
        parse(src)
        val id = sanitize(suggestedName)
        val dest = File(root, id)
        root.mkdirs()
        if (dest.exists()) dest.deleteRecursively()
        if (!src.renameTo(dest)) src.copyRecursively(dest, overwrite = true)
        return id
    }

    private fun extractZip(uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("não foi possível abrir o arquivo")
        val budget = Budget()
        ZipInputStream(input).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name.startsWith("__MACOSX/")) continue
                val target = File(out, e.name).canonicalFile
                require(target.path.startsWith(out.canonicalPath + File.separator)) { "Caminho inválido no zip: ${e.name}" }
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    require(++budget.files <= MAX_FILES) { "arquivos demais na extensão" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { o -> copyLimited(zin, o, budget) }
                }
            }
        }
    }

    private fun copyTree(src: DocumentFile, dest: File, b: Budget, depth: Int) {
        require(depth <= MAX_DEPTH) { "pastas aninhadas demais" }
        for (child in src.listFiles()) {
            val name = child.name ?: continue
            val target = File(dest, name).canonicalFile
            require(target.path.startsWith(dest.canonicalPath + File.separator)) { "Nome inválido: $name" }
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target, b, depth + 1)
            } else {
                require(++b.files <= MAX_FILES) { "arquivos demais na extensão" }
                context.contentResolver.openInputStream(child.uri)?.use { i ->
                    target.outputStream().use { o -> copyLimited(i, o, b) }
                }
            }
        }
    }

    private fun copyLimited(i: InputStream, o: OutputStream, b: Budget) {
        val buf = ByteArray(8192)
        while (true) {
            val n = i.read(buf)
            if (n <= 0) break
            b.bytes += n
            require(b.bytes <= MAX_BYTES) { "extensão grande demais" }
            o.write(buf, 0, n)
        }
    }

    // ---------- Manifest ----------

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
        const val MAX_BYTES = 50L * 1024 * 1024
        const val MAX_FILES = 1000
        const val MAX_DEPTH = 10

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
