# YouTube sem distrações

O NavegadorMaluco inclui uma extensão local chamada **YouTube sem distrações**. Ela usa o mecanismo de content scripts já existente no navegador e não carrega código remoto.

## Preferências

As 25 opções são configuráveis individualmente no menu **Ferramentas → YouTube sem distrações**:

- Ocultar feed da página inicial
- Redirecionar a página inicial para inscrições
- Ocultar Explorar e Em alta
- Ocultar Mais do YouTube
- Ocultar blocos irrelevantes da pesquisa
- Ocultar a aba Shorts
- Ocultar vídeos Shorts
- Desativar reprodução automática
- Ocultar mural de vídeos no encerramento
- Ocultar cartões sobre o vídeo
- Desativar anotações
- Ocultar toda a barra lateral
- Ocultar vídeos recomendados
- Ocultar chat ao vivo
- Ocultar playlists laterais
- Ocultar comentários
- Ocultar fotos de perfil nos comentários
- Ocultar Mix e rádios automáticas
- Ocultar produtos, ingressos e ofertas
- Ocultar informações do vídeo
- Ocultar botões do vídeo
- Ocultar canal e inscrição
- Ocultar descrição
- Ocultar cabeçalho superior
- Ocultar sino de notificações

## Comportamento

- As preferências são armazenadas localmente no armazenamento privado da extensão.
- Os padrões priorizam retirar feed, Shorts, recomendações e autoplay, preservando pesquisa, vídeos longos e reprodução manual.
- O filtro acompanha a navegação interna do YouTube (SPA) e elementos adicionados dinamicamente.
- O redirecionamento para inscrições só ocorre ao abrir a raiz do YouTube; links diretos e páginas de vídeos continuam funcionando.
- As opções de ocultação atuam na interface. Não alteram o algoritmo do YouTube nem bloqueiam o acesso direto a URLs.

## Limitações técnicas

O YouTube muda frequentemente sua estrutura HTML. Os seletores são compatíveis com os componentes atuais conhecidos, mas podem precisar de ajustes quando a plataforma mudar. A desativação de autoplay depende de o botão de autoplay estar presente e expor seu estado acessível. Anotações e cartões são ocultados visualmente; isso não desativa funcionalidades do servidor.

O recurso aplica-se a `www.youtube.com` e `m.youtube.com` no motor WebView Chromium do navegador. A atividade GeckoView separada não compartilha o mecanismo de extensões do navegador principal.


## Recursos adicionais

A tela de preferências inclui ferramentas opcionais de organização e aprendizagem:

- **Biblioteca e fila pessoal:** salva vídeos localmente e oferece acesso à lista.
- **Marcar momentos:** registra o timestamp atual do vídeo.
- **Anotações por vídeo:** salva texto associado ao vídeo e ao instante.
- **Temporizador de sessão:** pausa o vídeo ao fim do período escolhido.
- **Velocidade por canal:** memoriza a velocidade definida no player para cada canal.
- **Modo estudo:** reúne os controles de marcação e anotação.

Os dados são armazenados no armazenamento local privado da extensão, sem envio a servidor. As listas têm limites para evitar crescimento indefinido. O painel flutuante aparece apenas quando pelo menos uma ferramenta correspondente está ativa. O observador ignora suas próprias alterações de estilo para evitar ciclos de mutação.
