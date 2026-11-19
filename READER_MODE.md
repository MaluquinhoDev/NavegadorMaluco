# Modo de leitura

O Navegador Maluco inclui um modo de leitura local inspirado no Reader View do Firefox.

## Fluxo

1. O usuário abre **Menu → Página → Modo de leitura**.
2. A WebView da aba executa o Mozilla Readability sobre uma cópia do DOM. A página original não é modificada nem recarregada.
3. O resultado estruturado (título, autoria, site, resumo, idioma, direção e conteúdo) retorna ao aplicativo.
4. O HTML extraído é enviado a uma segunda WebView, vazia e sem scripts do site de origem. Nessa superfície, DOMPurify remove conteúdo e atributos perigosos.
5. O aplicativo apresenta o artigo em uma janela de leitura independente. A WebView original continua carregada por trás dela.
6. Ao fechar, o usuário retorna à página no mesmo ponto e com a mesma sessão. Links HTTP/HTTPS do artigo são abertos na aba original.

## Controles

- Tamanho da fonte: 14–28 px, persistido localmente.
- Temas: claro, sépia e escuro, persistidos localmente.
- Imagens, títulos, parágrafos, listas, citações, tabelas e blocos de código são preservados quando extraídos pelo parser.
- Tempo de leitura estimado a 220 palavras por minuto.
- Não há tradução automática, síntese, leitura em voz alta ou salvamento offline neste recurso.

## Segurança e privacidade

- Readability opera sobre `document.cloneNode(true)`, evitando mutação do documento da página.
- O conteúdo extraído é tratado como não confiável.
- DOMPurify é executado em uma WebView separada, inicialmente vazia; a página de origem não controla o sanitizador.
- A visualização final recebe uma política CSP restritiva: sem scripts, formulários, frames, mídia executável ou conexões de rede. Imagens HTTPS e data URLs permanecem permitidas.
- A WebView de leitura não recebe bridge JavaScript, não acessa arquivos e não usa armazenamento DOM.
- O processamento do artigo ocorre no dispositivo. Não há envio do conteúdo a servidores de leitura.

## Limitações conhecidas

- O modo depende de o site expor conteúdo principal no DOM atual. Páginas com login, paywall, conteúdo carregado somente após interação, leitores de PDF, aplicações complexas ou conteúdo muito curto podem não produzir um artigo.
- A extração pode omitir partes de páginas mal estruturadas; isso é uma limitação do algoritmo de leitura, não uma garantia de equivalência visual com a página original.
- Vídeos incorporados, players, formulários e elementos interativos não são reproduzidos no modo de leitura.
- As imagens são carregadas dos endereços HTTPS originais; não são baixadas nem armazenadas offline.
- O suporte foi integrado à WebView principal do navegador. A Activity GeckoView usada para sessões específicas não foi alterada.

## Dependências de terceiros

- [Mozilla Readability 0.6.0](https://github.com/mozilla/readability) — Apache-2.0. Código local em `app/src/main/assets/reader-mode/Readability.js`.
- [DOMPurify 3.4.18](https://github.com/cure53/DOMPurify) — Apache-2.0 / MPL-2.0. Código local em `app/src/main/assets/reader-mode/DOMPurify.min.js`.

As cópias das licenças acompanham os arquivos de terceiros na pasta `reader-mode`. As dependências são empacotadas no APK; o recurso não depende de CDN nem de conexão com serviços externos para extrair e sanitizar o texto.
