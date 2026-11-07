# Tradução de páginas — arquitetura incremental

## Objetivo

Adicionar tradução de páginas sem substituir o motor de navegação. O Android WebView já utilizado pelas abas continua responsável por carregar, renderizar e executar os sites.

A tradução é uma camada independente que recebe nós de texto, chama um motor local e aplica somente os textos traduzidos. O conteúdo da página não precisa ser enviado a um serviço remoto.

## Estado desta entrega

Esta alteração estabelece a primeira camada reutilizável:

- contrato TranslationEngine, sem acoplamento a um fornecedor;
- extração de nós de texto visíveis;
- exclusão de scripts, estilos, campos de formulário, código, conteúdo editável e elementos marcados para não tradução;
- preservação dos textos originais para permitir restauração;
- processamento em lotes;
- execução da tradução fora da thread principal;
- cache LRU em memória;
- cancelamento lógico de resultados de páginas anteriores;
- validação de quantidade de resultados do motor.

**O motor Bergamot, o Web Worker, o WebAssembly e os modelos de idiomas ainda não estão incluídos.** Portanto, esta entrega não ativa tradução no navegador nem deve ser apresentada como tradução offline pronta. A interface TranslationEngine é o ponto de integração para a implementação real.

## Fluxo planejado

1. A aba solicita TranslationManager.translatePage().
2. Um script isolado percorre os nós de texto visíveis do documento.
3. O gerenciador consulta o cache e separa os textos ainda não traduzidos.
4. Os textos pendentes são divididos em lotes limitados.
5. TranslationEngine executa a inferência fora da thread principal.
6. O gerenciador descarta resultados de solicitações antigas ou de uma URL que já mudou.
7. Somente os nós de texto originais recebem as traduções.
8. restorePage() recupera o texto original armazenado em memória.

## Integração futura com Bergamot

A implementação do motor deverá usar um ambiente auxiliar confiável, carregado com WebViewAssetLoader, para hospedar o JavaScript/WASM e o Web Worker do Bergamot. Esse WebView auxiliar não deve navegar por sites externos.

O WebView que abre páginas desconhecidas não deve receber uma interface JavaScript nativa privilegiada (addJavascriptInterface). A comunicação privilegiada deve permanecer restrita ao conteúdo local do auxiliar. A camada de página desta entrega usa evaluateJavascript para ler e alterar nós de texto, sem expor métodos Kotlin ao site.

Os modelos devem ser baixados sob demanda para armazenamento privado do aplicativo, com tamanho e hash SHA-256 definidos por um manifesto confiável. Não colocar modelos grandes dentro de assets nem baixar arquivos sem verificação de integridade.

## Limitações conhecidas

- A implementação atual depende de um TranslationEngine fornecido por quem integra o motor; não há implementação Bergamot nesta entrega.
- A detecção automática de idioma pertence ao motor.
- Conteúdo em canvas, imagens, PDFs, shadow DOM e documentos de outros frames não é traduzido por esta camada.
- Sites dinâmicos podem inserir novos textos depois da extração; observação incremental com MutationObserver será uma etapa posterior.
- A aplicação é por nós de texto e não traduz atributos, URLs, nomes de classes ou código.
- O cache atual é em memória e é descartado ao fechar o gerenciador.

## Critérios antes de ativar por padrão

- testar modelos e pares de idioma em aparelhos ARM de 32 e 64 bits;
- medir pico de RAM, CPU, bateria e tempo de inicialização;
- validar cancelamento durante navegação e troca de abas;
- testar páginas SPA, formulários, leitores de tela e conteúdo dinâmico;
- verificar a licença dos binários, modelos e dados de treinamento distribuídos;
- confirmar por inspeção de rede que o texto da página não sai do dispositivo.
