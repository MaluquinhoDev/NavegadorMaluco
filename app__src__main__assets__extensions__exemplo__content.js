// Roda no mesmo contexto JS da página. `browser` é a API injetada pelo app.
(function () {
  browser.storage.local.get({ visitas: 0 }).then(function (d) {
    var n = d.visitas + 1;
    browser.storage.local.set({ visitas: n });
    var el = document.createElement('div');
    el.className = 'ext-exemplo-badge';
    el.textContent = 'Extensão ativa · carregamentos: ' + n;
    document.body.appendChild(el);
  }).catch(function (e) { console.error('exemplo:', e); });
})();
