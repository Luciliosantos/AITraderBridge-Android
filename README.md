# AI Trader Bridge — app Android

Ponte nativa entre a IQ Option (captura de tela) e o painel **Multi Frame Candle Compass**.
O app captura a tela por `MediaProjection`, lê os candles do gráfico, converte pixels em preço
pela calibração, envia os candles autenticados para `/api/bridge/candles` e mostra a decisão
do motor num painel flutuante sobre a IQ Option.

## Requisitos

- Android Studio (Ladybug ou superior) com JDK 17
- Android SDK Platform 34 + Build-Tools 34.0.0
- Celular Android 8.0+ (API 26)

## Como compilar o APK

1. Abra **esta pasta `android/`** no Android Studio (File → Open).
2. Aguarde o *Gradle Sync* (ele baixa o Gradle 8.7 e as dependências).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
   O APK sai em `app/build/outputs/apk/debug/app-debug.apk` (assinado com a chave de debug, pronto para instalar).

Pela linha de comando, na pasta `android/`:

O projeto já inclui `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` e
`gradle/wrapper/gradle-wrapper.properties`; não é necessário instalar o Gradle separadamente.
Configure `JAVA_HOME` para o JDK 17 e `ANDROID_HOME` para o Android SDK (ou configure
`sdk.dir` em um arquivo local `local.properties`, que não é distribuído por depender da máquina).
A primeira compilação precisa de internet para baixar o Gradle e as dependências.

Linux/macOS:
```bash
chmod +x gradlew
./gradlew assembleDebug
```

Windows (PowerShell):
```powershell
.\gradlew.bat assembleDebug
```

Caminho do APK após uma compilação bem-sucedida, contado a partir da raiz do ZIP:
`android/app/build/outputs/apk/debug/app-debug.apk`.
Este pacote entrega o código-fonte; não inclui um APK previamente compilado.

## Compilar o APK sem instalar nada: GitHub Actions

O ZIP já traz `.github/workflows/build-apk.yml`, que compila o `app-debug.apk` na nuvem do GitHub.

1. Crie um repositório (pode ser privado) em github.com.
2. Envie **o conteúdo desta pasta `android/` como raiz do repositório** (o `.github/` incluído já vai junto).
3. Abra a aba **Actions** do repositório → workflow **Build Debug APK** → **Run workflow**.
4. Quando o build terminar (cerca de 3–5 min), baixe o artefato **app-debug**, que contém o `app-debug.apk`.

Para um APK de distribuição, crie um keystore e use `assembleRelease` com `signingConfigs`.

Instalação: transfira o `app-debug.apk` para o celular (ou `adb install -r app-debug.apk`)
e permita "instalar de fontes desconhecidas" quando o Android pedir.

## Fluxo de uso

1. **Painel → Pareamento** → *Gerar código* (vale 10 minutos).
2. No app: informe a URL do painel (`https://seu-app.base44.app`), o nome do aparelho,
   o código e o ativo (ex.: `EUR/USD`) → **Parear dispositivo**.
   O token do dispositivo é guardado só no aparelho; o painel mostra apenas um prefixo.
3. **Permitir sobreposição** (Android → "Desenhar sobre outros apps").
4. Abra a IQ Option, deixe o gráfico visível e toque em **Iniciar captura** →
   aceite o aviso do Android de que a tela será gravada.
5. **Calibrar detector** (com a captura rodando, para existir um frame):
   - digite o preço mais alto e o mais baixo que aparecem no eixo de preço;
   - toque na imagem, nesta ordem: **ponto do preço A → ponto do preço B → borda esquerda do gráfico → borda direita**;
   - salvar. A calibração fica em frações da tela, então sobrevive a mudanças de resolução.
6. O painel flutuante aparece sobre a IQ Option e é arrastável pelo título.
   Os botões **M15 / M5 / M1** dizem ao detector qual timeframe está na tela no momento.
7. No painel web, abra **Dados** para ver os candles chegando e o **Histórico** para as decisões.

## Como o detector funciona

- Varre a região do gráfico coluna por coluna e classifica cada pixel em verde/vermelho por matiz
  (independente do tema claro/escuro da corretora).
- Colunas vizinhas da mesma cor viram um candle; a **cobertura de linha** separa corpo e pavios.
- O eixo Y é convertido em preço pelas duas âncoras da calibração.
- Cada candle detectado recebe um timestamp alinhado ao timeframe (M1 = 60 s, M5 = 300 s, M15 = 900 s).
- Confiança vem do preenchimento do corpo e da largura do candle; abaixo do limite o candle é descartado.

## Comunicação com o painel

| Endpoint | Auth | Conteúdo |
| --- | --- | --- |
| `POST /api/bridge/pair` | — | `{ code, device_name, android_version, app_version }` → `{ device_id, token }` |
| `POST /api/bridge/status` | Bearer | `capture_state`, `data_state`, `asset`, `timeframe`, `resolution`, `last_reason` |
| `POST /api/bridge/candles` | Bearer | `asset`, `timeframe`, `sequence`, `price_mode`, `candles[]` → devolve a análise |
| `POST /api/bridge/analysis` | Bearer | `{ asset }` → decisão do motor |

A `sequence` é sempre crescente; se o servidor responder `409`, o app ressincroniza o contador
com o valor devolvido e continua enviando.

## Limitações conhecidas

- O app **não opera** a IQ Option: só lê a tela. Não há clique automático nem ordem enviada.
- A captura reflete a tela no momento em que o serviço iniciou; girar o celular depois exige
  parar e iniciar a captura de novo.
- Se a IQ Option trocar o tema/zoom, recalibre.
- Um candle só é reconhecido se o gráfico estiver visível e sem sobreposição de menus sobre ele.