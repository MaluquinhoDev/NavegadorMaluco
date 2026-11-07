# Controle de velocidade de vídeo

## Escopo

O controle de velocidade mantém o Android WebView como mecanismo principal de navegação. Não transfere automaticamente vídeos das páginas para o ExoPlayer.

A preferência é compartilhada entre:
- Vídeos HTML5 reproduzidos dentro das páginas no WebView.
- Mídias reproduzidas pela sessão AndroidX Media3/ExoPlayer.

## Interface

A opção **Velocidade do vídeo** está no menu principal, na seção Reprodução. O player Media3 também exibe um atalho de velocidade nos controles.

Velocidades disponíveis: 0,5×, 0,75×, 1,0×, 1,25×, 1,5×, 1,75×, 2,0× e 2,5×.

A escolha é salva em SharedPreferences (`video_playback/playback_speed`) e permanece após fechar o aplicativo.

## WebView

Quando suportado, o controlador é instalado com `WebViewCompat.addDocumentStartJavaScript`, antes da execução normal da página. O fallback instala o script em `onPageFinished`.

O script:
- Usa `HTMLMediaElement.playbackRate` e `defaultPlaybackRate`.
- Prioriza o vídeo visível que está reproduzindo; na ausência dele, escolhe o maior vídeo visível.
- Reaplica a velocidade em eventos `play` e `loadedmetadata`.
- Observa a inserção dinâmica de elementos de vídeo.
- Não substitui o WebView nem extrai a mídia da página.

A aplicação via `evaluateJavascript` alcança o documento principal. Conteúdo em iframes de outra origem pode não receber a atualização dinâmica; a injeção de início de documento depende do suporte e das regras de origem do WebView.

## Media3

A sessão Media3 aplica a preferência ao criar o ExoPlayer. Mudanças feitas pelo menu atualizam a sessão ativa por `Player.setPlaybackSpeed`. Como Mini-player, player em tela cheia e PiP compartilham a mesma sessão, a velocidade permanece consistente nesses modos.

## Limitações

- Sites podem sobrescrever a velocidade por código próprio.
- Alguns players personalizados não utilizam elementos HTML5 `video`.
- Iframes isolados e conteúdo protegido por DRM têm limitações próprias.
- A preferência é global, não individual por site ou vídeo.
- A disponibilidade de velocidades e a qualidade do áudio dependem do formato, do player e do dispositivo.

## Privacidade e compatibilidade

O recurso não envia dados a serviços externos e não altera cookies, cabeçalhos, URLs ou fontes de mídia. O script opera sobre os elementos de mídia já presentes na página.
