# Verificación y limitaciones conocidas

Estado de validación de ChrisAI v0.7.0. Todo el código compila, los 69 tests unitarios
pasan y el APK release está firmado y publicado; pero parte de la funcionalidad **no se puede
verificar en el entorno de compilación** porque no hay emulador ni `adb`.

## Verificado automáticamente (sandbox CI)

| Área | Cómo |
| --- | --- |
| Compilación | `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → BUILD SUCCESSFUL |
| Tests unitarios | 69/69 verdes: `TtsTextTest`, `EmotionEngineTest`, `ToolRegistryTest`, `MemoryIntentTest`, `SemanticVersionTest`, `PersonalityPromptTest`, `EmotionClassifierTest`, `ExampleUnitTest` |
| Parser de herramientas | `[TOOLS]...[/TOOLS]`, fences ```json, malformed, cadenas, texto visible |
| Pipeline TTS (`TtsText`) | emojis, markdown, URLs, números→palabras, %, moneda, símbolos, abreviaturas, puntos, bloques [TOOLS] |
| Motor de emociones | buckets, estabilidad, GENERATING, outcomes de tools, classifyUser |
| APK release | `apksigner verify` → cert SHA-256 `df93f810...` (idéntico a v0.6.0) |
| Integridad del release | descarga de GitHub == APK local (sha256 `80cbd99a...`) |

## Requiere dispositivo físico (no verificable aquí)

Sin emulador/`adb` en el entorno, estos flujos necesitan una prueba manual en un teléfono real:

| Funcionalidad | Qué comprobar en el dispositivo |
| --- | --- |
| **Voz (voz/pitch/preview)** | Que el selector de voces lista voces reales, el preview suena con el pitch elegido y el género heurístico es correcto; que el texto hablado coincide con el preparado por `TtsText` |
| **STT** | Reconocimiento de voz de extremo a extremo (permiso RECORD_AUDIO en runtime) |
| **Hápticos** | Que el patrón de pulsos (normal/éxito/error) es sutil y respeta el interruptor de Ajustes; throttles funcionan |
| **Notificaciones** | Canal `chrisai_actions`: POST_NOTIFICATIONS en Android 13+, icono `ic_notification`, tap/full-screen |
| **Temporizador y alarma** | Que la alarma dispara con la app cerrada (AlarmManager `setAndAllowWhileIdle`), reaprovechamiento de espectro por `requestKey` |
| **Búsqueda/apertura de apps** | Coincidencia parcial case-insensitive sin acentos ("musica" → "Música"); abrir app con QUERY_ALL_PACKAGES; comportamiento cuando la app no existe (NOT_FOUND/NO_COMPATIBLE_APP) |
| **Confirmación y Shizuku** | Flujo de `REQUIRES_CONFIRMATION` con token determinista; herramientas RESTRICTED cuando Shizuku no está disponible |
| **Abrir URL / Play Store** | Que abren en el navegador/Play Store con los intents correctos |
| **Actualizador** | Que detecta v0.7.0, descarga el APK, valida el checksum `.sha256` y `REQUEST_INSTALL_PACKAGES` instala sobre v0.6.0 (firma idéntica) |
| **Backend emocional / GENERATING** | Nivel de sutileza del fondo y la viñeta; que no interfieren con la lectura de texto |

## Cómo reproducir la verificación

```bash
export JAVA_HOME=/opt/java/jdk-17
export ANDROID_HOME=/opt/android_sdk
unset LD_LIBRARY_PATH   # crítico: LD_LIBRARY_PATH rompe gradle/curl por libcrypto

# Tests y builds
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon

# Firma del APK release
export PATH=/opt/java/jdk-17/bin:$PATH
/opt/android_sdk/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
# Esperado: Signer #1 SHA-256 df93f8102c9f5aed5bae4e8bdc440bee5d3f0cb7a84583fbd04396a1ee14f9ca
```

> **Importante**: `Test/ChrisAi-debug.apk` es un build *debug* v0.6.0 firmado con el certificado
> Android Debug (`3fe57967...`). No sirve como base para updates encadenados; el APK de producción
> siempre sale de `app/build/outputs/apk/release/` firmado con `keystore/chrisai-release.jks`.

## Limitaciones conocidas

- Las emociones son computacionales y simuladas: nunca representan estados humanos reales.
- El parser de ChrisTools depende del formato `[TOOLS]...[/TOOLS]` acordado; sin ejecución real
  del modelo en CI no se puede validar el cumplimiento estricto del formato.
- `aapt`/`aapt2` nativos y `apkanalyzer` no funcionan en el sandbox (QEMU/JAXB); la inspección de
  APK se hace vía `output-metadata.json`, `sha256sum` y `apksigner`.
- El texto hablado es una versión limpia del visible (`TtsText`): puede diferir en emojis, URLs,
  markdown y números escritos por diseño, nunca al revés.