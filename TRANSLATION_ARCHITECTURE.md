# Tradução de páginas

## Implementação

O Navegador Maluco mantém o Android WebView como motor de navegação. A tradução é executada por uma camada Kotlin independente, usando **Google ML Kit Translation** e identificação de idioma no dispositivo.

O ML Kit oferece tradução em mais de 50 idiomas, com modelos dinâmicos baixados sob demanda. Os modelos têm aproximadamente 30 MB cada. Após o download do par necessário, a inferência acontece localmente e o texto da página não é enviado para uma API de tradução.

## Como usar

1. Abra uma página HTTP ou HTTPS.
2. Abra **Menu**.
3. Selecione **Traduzir página**.
4. Na primeira utilização, o aplicativo baixa o modelo necessário para o idioma detectado e português.
5. Depois do download, a tradução funciona sem conexão.
6. Abra o Menu novamente e selecione **Restaurar texto original** para desfazer a tradução.

O idioma é identificado pelo atributo `lang` do documento quando disponível; na ausência ou quando o código não é aceito, o identificador local do ML Kit analisa o texto da página. O destino desta implementação é português (`pt`).

## Arquitetura

- `TranslationEngine.kt`: contrato do motor e resolução do idioma de origem.
- `MlKitTranslationEngine.kt`: integração real com ML Kit, download de modelos e inferência.
- `TranslationManager.kt`: extração, lotes, cache, execução em background, cancelamento e aplicação no WebView.
- `TranslationCache.kt`: cache LRU em memória, separado por idioma de origem/destino e texto original.
- `TranslationPageScript.kt`: extração de nós de texto visíveis, aplicação das traduções e restauração.
- `MainActivity.kt`: ação no Menu do navegador.

A implementação mantém um único gerenciador de tradução e um tradutor ativo por vez para evitar manter vários modelos de idioma carregados simultaneamente.

## Segurança e privacidade

- O texto da página é processado no dispositivo.
- O texto não é enviado a um serviço de tradução remoto.
- Os downloads de modelos são feitos pela infraestrutura do ML Kit/Google.
- Nenhuma interface JavaScript nativa privilegiada é adicionada aos sites.
- A comunicação com o DOM usa `WebView.evaluateJavascript`.
- Scripts, estilos, formulários, campos editáveis, código, SVG, MathML e elementos marcados com `translate="no"` são excluídos da extração.

## Limitações

- A tradução é acionada manualmente pelo usuário; não ocorre automaticamente em toda navegação.
- O destino atual é português.
- A identificação de idioma é feita para a página como um conjunto; páginas com vários idiomas podem ter resultados mistos.
- Canvas, imagens, conteúdo de vídeo, PDFs, shadow DOM e documentos de outros frames não são traduzidos por esta camada.
- Conteúdo carregado dinamicamente depois da extração pode permanecer no idioma original.
- A qualidade do ML Kit é indicada para tradução simples/casual. Para pares entre idiomas que não sejam inglês, o ML Kit pode usar inglês como idioma intermediário, o que pode afetar a qualidade.
- Os modelos precisam ser baixados uma vez; a primeira tradução depende de conexão e espaço de armazenamento.
- Os modelos do ML Kit são proprietários; esta implementação não é uma integração Bergamot/Mozilla.

## Dependências

```kotlin
implementation("com.google.mlkit:translate:17.0.3")
implementation("com.google.mlkit:language-id:17.0.6")
```

Documentação oficial:
- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/language/identification/android
