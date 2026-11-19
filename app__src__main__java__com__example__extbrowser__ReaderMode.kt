package com.example.extbrowser

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Extração e apresentação local de artigos no estilo Firefox Reader View.
 * Readability analisa uma cópia do DOM; o HTML é sanitizado em uma WebView separada.
 */
internal object ReaderMode {
    data class Article(
        val title: String,
        val byline: String,
        val siteName: String,
        val excerpt: String,
        val contentHtml: String,
        val textContent: String,
        val direction: String,
        val language: String,
        val publishedTime: String,
        val extractionMethod: String
    ) {
        val readingMinutes: Int
            get() = (textContent.trim().split(Regex("\\s+")).size / 220.0).toInt().coerceAtLeast(1)
    }

    fun extractionScript(context: Context): String {
        val readability = readAsset(context, "reader-mode/Readability.js")
        return """
            (function () {
              try {
                $readability
                var article = new Readability(document.cloneNode(true), {
                  charThreshold: 250,
                  maxElemsToParse: 6000,
                  nbTopCandidates: 5
                }).parse();

                if (article && article.content && article.length >= 250) {
                  return JSON.stringify({
                    title: article.title || document.title || "Artigo",
                    byline: article.byline || "",
                    siteName: article.siteName || location.hostname || "",
                    excerpt: article.excerpt || "",
                    content: article.content || "",
                    textContent: article.textContent || "",
                    direction: article.dir || "ltr",
                    language: article.lang || document.documentElement.lang || "",
                    publishedTime: article.publishedTime || "",
                    extractionMethod: "Mozilla Readability"
                  });
                }

                // Fallback 1: dados estruturados Article/NewsArticle já entregues pela página.
                var structuredNodes = [];
                function collectStructured(value, depth) {
                  if (!value || depth > 8 || structuredNodes.length > 300) return;
                  if (Array.isArray(value)) {
                    value.slice(0, 100).forEach(function (item) {
                      collectStructured(item, depth + 1);
                    });
                    return;
                  }
                  if (typeof value !== "object") return;
                  structuredNodes.push(value);
                  Object.keys(value).forEach(function (key) {
                    collectStructured(value[key], depth + 1);
                  });
                }
                Array.prototype.slice.call(
                  document.querySelectorAll('script[type="application/ld+json"]'), 0, 30
                ).forEach(function (script) {
                  try { collectStructured(JSON.parse(script.textContent || ""), 0); }
                  catch (_) {}
                });
                var structured = structuredNodes.find(function (item) {
                  return typeof item.articleBody === "string" &&
                    item.articleBody.trim().length >= 250;
                });
                if (structured) {
                  var bodyText = structured.articleBody.trim();
                  var safeBody = document.createElement("div");
                  bodyText.split(/\n{2,}/).forEach(function (paragraph) {
                    if (!paragraph.trim()) return;
                    var p = document.createElement("p");
                    p.textContent = paragraph.trim();
                    safeBody.appendChild(p);
                  });
                  var author = structured.author;
                  if (Array.isArray(author)) author = author.map(function (a) {
                    return a && a.name ? a.name : "";
                  }).filter(Boolean).join(", ");
                  else if (author && typeof author === "object") author = author.name || "";
                  return JSON.stringify({
                    title: structured.headline || structured.name || document.title || "Artigo",
                    byline: typeof author === "string" ? author : "",
                    siteName: (structured.publisher && structured.publisher.name) ||
                      location.hostname || "",
                    excerpt: structured.description || "",
                    content: safeBody.innerHTML,
                    textContent: bodyText,
                    direction: document.documentElement.dir || "ltr",
                    language: structured.inLanguage || document.documentElement.lang || "",
                    publishedTime: structured.datePublished || "",
                    extractionMethod: "JSON-LD articleBody"
                  });
                }

                // Fallback 2: contêineres editoriais comuns, inclusive texto presente no DOM
                // mas oculto visualmente por CSS. Não solicita conteúdo adicional ao servidor.
                var selectors = [
                  '[itemprop="articleBody"]',
                  'article',
                  'main article',
                  '[role="main"] article',
                  '.article-body',
                  '.article-content',
                  '.post-content',
                  '.entry-content',
                  '.story-body',
                  '.story-content',
                  '.article__body',
                  '.content__article-body',
                  '[class*="article-body"]',
                  '[class*="story-body"]'
                ];
                for (var i = 0; i < selectors.length; i++) {
                  var candidate = document.querySelector(selectors[i]);
                  if (!candidate) continue;
                  var candidateText = (candidate.innerText || candidate.textContent || "")
                    .replace(/\s+/g, " ").trim();
                  if (candidateText.length < 250) continue;
                  return JSON.stringify({
                    title: document.querySelector('meta[property="og:title"]')?.content ||
                      document.title || "Artigo",
                    byline: document.querySelector('[rel="author"]')?.textContent?.trim() || "",
                    siteName: document.querySelector('meta[property="og:site_name"]')?.content ||
                      location.hostname || "",
                    excerpt: document.querySelector('meta[name="description"]')?.content || "",
                    content: candidate.innerHTML,
                    textContent: candidateText,
                    direction: document.documentElement.dir || "ltr",
                    language: document.documentElement.lang || "",
                    publishedTime: document.querySelector('meta[property="article:published_time"]')?.content || "",
                    extractionMethod: "DOM editorial fallback"
                  });
                }

                return JSON.stringify({
                  error: "Não foi encontrado conteúdo editorial suficiente no DOM desta página."
                });
              } catch (error) {
                return JSON.stringify({
                  error: "Falha na extração local: " +
                    (error && error.name ? error.name : "erro desconhecido")
                });
              }
            })()
        """.trimIndent()
    }

    /** Executado em uma WebView limpa, sem o JavaScript do site original. */
    fun sanitizationScript(context: Context, untrustedHtml: String): String {
        val purifier = readAsset(context, "reader-mode/DOMPurify.min.js")
        val quotedHtml = JSONObject.quote(untrustedHtml)
        return """
            (function () {
              $purifier
              var clean = DOMPurify.sanitize($quotedHtml, {
                USE_PROFILES: { html: true },
                FORBID_TAGS: [
                  "style", "iframe", "form", "button", "input", "select",
                  "textarea", "video", "audio", "source", "object", "embed",
                  "svg", "math", "canvas", "template"
                ],
                FORBID_ATTR: [
                  "style", "srcdoc", "srcset", "target", "onerror", "onclick", "onload"
                ],
                ALLOW_DATA_ATTR: false
              });
              return JSON.stringify(clean);
            })()
        """.trimIndent()
    }

    fun decodeArticle(webViewResult: String?): Article? {
        val encoded = webViewResult ?: return null
        val jsonText = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            ?: return null
        val json = JSONObject(jsonText)
        val error = json.optString("error")
        if (error.isNotBlank()) throw IllegalArgumentException(error)
        val content = json.optString("content")
        val text = json.optString("textContent")
        if (content.isBlank() || text.length < 250) return null
        return Article(
            title = json.optString("title", "Artigo"),
            byline = json.optString("byline"),
            siteName = json.optString("siteName"),
            excerpt = json.optString("excerpt"),
            contentHtml = content,
            textContent = text,
            direction = json.optString("direction", "ltr"),
            language = json.optString("language"),
            publishedTime = json.optString("publishedTime"),
            extractionMethod = json.optString("extractionMethod", "Mozilla Readability")
        )
    }

    fun decodeSanitizedHtml(webViewResult: String?): String? {
        val encoded = webViewResult ?: return null
        return runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
    }

    fun buildHtml(article: Article, cleanContent: String, theme: String, fontSize: Int): String {
        val palette = when (theme) {
            "dark" -> Palette("#17191d", "#e6e2d9", "#b6b0a5", "#8ab4f8", "#292d34", "#454b55")
            "sepia" -> Palette("#f3ead8", "#433629", "#766650", "#805b32", "#e9ddc7", "#d6c6aa")
            else -> Palette("#ffffff", "#24262b", "#707782", "#1769aa", "#f2f4f7", "#dce1e8")
        }
        val metaParts = listOf(article.byline, article.siteName)
            .filter { it.isNotBlank() }.distinct()
        val metadata = metaParts.joinToString(" · ")
        val excerpt = article.excerpt.takeIf { it.isNotBlank() }
            ?.let { "<p class=\"reader-excerpt\">${escapeHtml(it)}</p>" }.orEmpty()
        val published = article.publishedTime.takeIf { it.isNotBlank() }
            ?.let { "<span>${escapeHtml(it)}</span>" }.orEmpty()
        val lang = escapeHtml(article.language.ifBlank { "pt-BR" })
        val dir = if (article.direction == "rtl") "rtl" else "ltr"
        return """<!doctype html>
<html lang="$lang" dir="$dir" data-theme="$theme">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=3">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; font-src https: data:; connect-src 'none'; media-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; script-src 'none'">
<title>${escapeHtml(article.title)}</title>
<style>
:root { color-scheme: ${if (theme == "dark") "dark" else "light"}; --reader-bg:${palette.background}; --reader-text:${palette.text}; --reader-muted:${palette.muted}; --reader-link:${palette.link}; --reader-panel:${palette.panel}; --reader-border:${palette.border}; --reader-font-size:${fontSize}px; }
* { box-sizing:border-box; }
html { background:var(--reader-bg); scroll-behavior:smooth; }
body { margin:0; background:var(--reader-bg); color:var(--reader-text); font-family: Georgia, "Noto Serif", "Times New Roman", serif; font-size:var(--reader-font-size); line-height:1.78; overflow-wrap:anywhere; }
.reader-shell { width:min(100%, 780px); margin:0 auto; padding:28px 22px 72px; }
.reader-header { padding-bottom:22px; margin-bottom:28px; border-bottom:1px solid var(--reader-border); }
.reader-site { margin:0 0 12px; color:var(--reader-muted); font:600 12px/1.4 system-ui,sans-serif; letter-spacing:.08em; text-transform:uppercase; }
h1 { margin:0 0 14px; font:700 clamp(1.7em, 6vw, 2.35em)/1.18 system-ui,sans-serif; letter-spacing:-.035em; }
.reader-meta { color:var(--reader-muted); font:13px/1.5 system-ui,sans-serif; }
.reader-excerpt { margin:18px 0 0; color:var(--reader-muted); font:italic 1.02em/1.6 Georgia,serif; }
article { min-width:0; }
article p { margin:0 0 1.25em; }
article h2, article h3, article h4 { margin:1.8em 0 .65em; font-family:system-ui,sans-serif; line-height:1.3; }
article h2 { font-size:1.45em; } article h3 { font-size:1.2em; }
article img { display:block; max-width:100%; height:auto; margin:1.5em auto; border-radius:8px; }
article figure { margin:1.5em 0; }
article figcaption { color:var(--reader-muted); text-align:center; font:12px/1.5 system-ui,sans-serif; }
article a { color:var(--reader-link); text-decoration:underline; text-underline-offset:2px; }
article blockquote { margin:1.5em 0; padding:.25em 0 .25em 1em; border-left:3px solid var(--reader-link); color:var(--reader-muted); }
article ul, article ol { padding-left:1.5em; margin:0 0 1.25em; }
article li { margin:.35em 0; }
article pre { overflow-x:auto; padding:14px; border-radius:10px; background:var(--reader-panel); font-size:.82em; line-height:1.55; }
article code { font-family:ui-monospace,monospace; font-size:.88em; }
article table { display:block; max-width:100%; overflow-x:auto; border-collapse:collapse; font-size:.85em; }
article th, article td { border:1px solid var(--reader-border); padding:8px 10px; text-align:start; }
article hr { border:0; border-top:1px solid var(--reader-border); margin:2em 0; }
.reader-footer { margin-top:44px; padding-top:18px; border-top:1px solid var(--reader-border); color:var(--reader-muted); font:12px system-ui,sans-serif; text-align:center; }
</style>
</head>
<body>
<main class="reader-shell">
<header class="reader-header">
<p class="reader-site">${escapeHtml(article.siteName.ifBlank { "Modo de leitura" })}</p>
<h1>${escapeHtml(article.title)}</h1>
<div class="reader-meta">${escapeHtml(metadata)}${if (metadata.isNotBlank()) " · " else ""}${article.readingMinutes} min de leitura $published</div>
$excerpt
</header>
<article>$cleanContent</article>
<footer class="reader-footer">Fim do artigo · Extração: ${escapeHtml(article.extractionMethod)} · Navegador Maluco</footer>
</main>
</body>
</html>"""
    }

    private data class Palette(
        val background: String, val text: String, val muted: String,
        val link: String, val panel: String, val border: String
    )

    private fun readAsset(context: Context, path: String): String =
        context.applicationContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
