package com.example.extbrowser.translation

/**
 * Extrai somente texto visível do documento. Nenhuma interface Kotlin é
 * exposta ao JavaScript dos sites.
 */
internal object TranslationPageScript {
    val INSTALL = """
        (function () {
          if (window.__nmTranslation) return;

          let nodeIds = new WeakMap();
          const nodes = new Map();
          const originals = new Map();
          const translated = new Map();
          let nextId = 1;

          const ignoredSelector = [
            'script', 'style', 'noscript', 'template',
            'code', 'pre', 'kbd', 'samp',
            'input', 'textarea', 'select', 'option', 'button',
            '[contenteditable]', '[translate="no"]',
            '[aria-hidden="true"]', '[data-nm-translation-ignore]',
            'svg', 'math'
          ].join(',');

          function eligible(node) {
            const parent = node.parentElement;
            if (!parent || !parent.isConnected) return false;
            if (parent.closest(ignoredSelector)) return false;
            if (!node.nodeValue || !node.nodeValue.trim()) return false;
            if (node.nodeValue.trim().length < 2) return false;

            const style = window.getComputedStyle(parent);
            if (style.display === 'none' || style.visibility === 'hidden') return false;
            if (parent.getClientRects().length === 0) return false;
            return true;
          }

          function extract() {
            const result = [];
            if (!document.body) return { language: '', nodes: result };

            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
            let node;
            let totalCharacters = 0;

            while ((node = walker.nextNode())) {
              if (!eligible(node)) continue;
              const original = nodeIds.has(node) ? (originals.get(nodeIds.get(node)) || node.nodeValue) : node.nodeValue;
              if (totalCharacters + original.length > 80_000 || result.length >= 800) break;

              let id = nodeIds.get(node);
              if (!id) {
                id = String(nextId++);
                nodeIds.set(node, id);
                nodes.set(id, node);
                originals.set(id, node.nodeValue);
              }

              result.push({ id: id, text: originals.get(id) });
              totalCharacters += original.length;
            }

            return {
              language: document.documentElement ? document.documentElement.lang || '' : '',
              nodes: result
            };
          }

          function apply(translations) {
            let applied = 0;
            for (const item of translations) {
              const node = nodes.get(String(item.id));
              if (!node || !node.isConnected || typeof item.text !== 'string') continue;
              if (node.nodeValue !== originals.get(String(item.id))) continue;
              node.nodeValue = item.text;
              translated.set(String(item.id), item.text);
              applied++;
            }
            return applied;
          }

          function restore() {
            let restored = 0;
            for (const [id, original] of originals.entries()) {
              const node = nodes.get(id);
              if (node && node.isConnected &&
                  node.nodeValue === translated.get(id)) {
                node.nodeValue = original;
                restored++;
              }
            }
            nodes.clear();
            originals.clear();
            translated.clear();
            nodeIds = new WeakMap();
            nextId = 1;
            return restored;
          }

          window.__nmTranslation = { extract: extract, apply: apply, restore: restore };
        })();
    """.trimIndent()
}
