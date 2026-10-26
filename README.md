# ExtBrowser

WebView + extensões próprias em JS (uso pessoal, Android 14+).

## Compilar sem Android Studio (PC fraco): GitHub Actions
1. Crie um repositório no GitHub (pode ser privado) e envie todo o conteúdo desta pasta
   (site: "Add file > Upload files", ou `git push`). Envie também a pasta oculta `.github`.
2. Aba **Actions** > workflow "Build APK" roda sozinho (ou "Run workflow").
3. Ao terminar, baixe o artefato correspondente à arquitetura do celular:
   `extbrowser-arm64-v8a-debug-apk` (maioria dos Androids atuais) ou
   `extbrowser-armeabi-v7a-debug-apk` (alguns aparelhos 32 bits).
   Os artefatos `x86` e `x86_64` atendem principalmente emuladores.
   Cada ZIP contém um APK específico, menor que o APK universal anterior.
4. Instale no celular (permita "instalar apps desconhecidos"). O APK de debug já vem assinado com a chave de debug.

## Extensões
- Primeira execução: `assets/extensions/*` é copiado para `filesDir/extensions/`.
- Depois o app lê `filesDir/extensions/<nome>/manifest.json` (reinicie o app para recarregar).
- Para mudar extensões sem recompilar, use `adb push` + `run-as` (build debug):
    adb push minha-ext /data/local/tmp/minha-ext
    adb shell run-as com.example.extbrowser cp -r /data/local/tmp/minha-ext files/extensions/

## Manifest suportado
name, version, content_scripts[{matches, js, css, run_at}]

## API do content script (`browser`)
runtime.sendMessage(msg), storage.local.get/set/remove/clear (Promises)

## Depuração
chrome://inspect (celular no cabo) | adb logcat -s ExtBrowser ExtManager ExtBridge
