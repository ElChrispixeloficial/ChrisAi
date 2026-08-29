# ChrisAI

Asistente inteligente para Android, nativo en **Kotlin + Jetpack Compose** con núcleo C++,
memoria persistente y actualizaciones vía GitHub Releases.

## Características

- **Chat** con streaming sobre OpenRouter y memoria persistente en Room (`chrisai.db`).
- **Personalidad, emociones y métricas** de conversación.
- **TTS 2.0**: limpieza de texto hablado (emojis, markdown, URLs, números→palabras) que nunca
  altera el mensaje visible; selector de voz con género heurístico y pitch ajustable.
- **ChrisTools**: 11 herramientas Android (apps, notificaciones, temporizador/alarma, hora,
  batería, URL, Play Store) ejecutadas en dos rondas: el modelo ve el resultado real antes de
  responder.
- **Emociones computacionales**: intensidad en 5 niveles, estado estable y backend visual sutil.
- **STT** (RECORD_AUDIO) y **hápticos** configurables.
- **Actualizador** integrado: GitHub Releases + validación de checksum SHA-256 y firma idéntica
  para updates encadenados.

## Estructura

```
app/src/main/java/com/chrispixel/chrisai/
├─ data/            ChatRepository, SettingsRepository, AppContainer, haptics, emotion, speech, tools
├─ data/update/     UpdaterRepository (checksum SHA-256, GitHub releases/latest)
├─ data/tools/      ToolModel, ToolRegistry, parser [TOOLS], reportes y 8 tools Android
├─ ui/               ChrisViewModel, ChrisApplication, MainActivity, chat, settings
├─ cpp/              chriscore.cpp + CMakeLists (NativeBridge)
└─ test/             TtsTextTest, EmotionEngineTest, ToolRegistryTest, MemoryIntentTest, etc.
```

## Compilar

```bash
export JAVA_HOME=/opt/java/jdk-17
export ANDROID_HOME=/opt/android_sdk
unset LD_LIBRARY_PATH   # crítico: LD_LIBRARY_PATH rompe gradle/curl por libcrypto
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest --no-daemon
```

Detalles de verificación: [`docs/VERIFICACION.md`](docs/VERIFICACION.md). Historial de versiones:
[`CHANGELOG.md`](CHANGELOG.md).

## Releases

- APK de producción: `app/build/outputs/apk/release/app-release.apk` (arm64-v8a).
- Firmado con `keystore/chrisai-release.jks` (alias `chrisai`), mismo certificado para todas las
  versiones → actualizaciones encadenadas sin desinstalar.
- Cada release incluye `app-release.apk.sha256`; el actualizador compara ese checksum antes de
  instalar.