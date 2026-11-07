package com.example.extbrowser.translation

/**
 * Código executado somente no documento da página que já está aberta no
 * WebView principal. Não cria interfaces JavaScript nativas no site.
 *
 * O estado mantém referências aos nós de texto originais. Assim, restaurar a
 * página não depende de tentar traduzir a tradução anterior.
 */
internal object TranslationPageScript {
    val INSTALL = """
        (function () {
          if (window.__nmTranslation) return;

          const nodeIds = new WeakMap();
          const nodes = new Map();
          const originals = new Map();
          let nextId = 1;

          const ignoredSelector = [
            'script', 'style', 'noscript', 'template',
            'code', 'pre', 'kbd', 'samp',
            'input', 'textarea', 'select', 'option', 'button',
            '[contenteditable="true"]', '[translate="no"]',
            '[aria-hidden="true"]', '[data-nm-translation-ignore]',
            'svg', 'math'
          ].join(',');

          function isEligible(node) {
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
            if (!document.body) return result;

            const walker = document.createTreeWalker(
              document.body,
              NodeFilter.SHOW_TEXT
            );

            let node;
            while ((node = walker.nextNode())) {
              if (!isEligible(node)) continue;

              let id = nodeIds.get(node);
              if (!id) {
                id = String(nextId++);
                nodeIds.set(node, id);
                nodes.set(id, node);
                originals.set(id, node.nodeValue);
              }

              result.push({
                id: id,
                text: originals.get(id)
              });
            }
            return result;
          }

          function apply(translations) {
            let applied = 0;
            for (const item of translations) {
              const node = nodes.get(String(item.id));
              if (!node || !node.isConnected || typeof item.text !== 'string') continue;
              node.nodeValue = item.text;
              applied++;
            }
            return applied;
          }

          function restore() {
            let restored = 0;
            for (const [id, original] of originals.entries()) {
              const node = nodes.get(id);
              if (node && node.isConnected) {
                node.nodeValue = original;
                restored++;
              }
            }
            nodes.clear();
            originals.clear();
            return restored;
          }

          window.__nmTranslation = {
            extract: extract,
            apply: apply,
            restore: restore
          };
        })();
    """.trimIndent()
}
