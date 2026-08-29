# Changelog

## v0.7.0 — TTS 2.0, ChrisTools y emociones computacionales (2026-08-29)

- **TTS 2.0** (`TtsText.kt`): pipeline de limpieza de texto hablado que nunca modifica el mensaje visible:
  emojis, markdown (fences, inline, links), URLs, números a palabras (incluye "cien"/"ciento",
  ordinales, porcentajes, moneda), símbolos, abreviaturas (p.ej., etc., sr.), puntos suspensivos,
  comillas y bloques `[TOOLS]` residuales.
- **Selector de voz**: `TtsVoiceInfo` con nombre legible, género heurístico (👨/👩/🔊) y locale;
  pitch ajustable 0.5–2.0 con preview instantáneo en Ajustes.
- **ChrisTools**: modelo tipado (`ToolModel.kt`) con `ToolRegistry` (parámetros declarados, riesgo,
  confirmación determinista, Shizuku) y 11 herramientas Android:
  buscar/abrir apps, abrir URL, buscar en Play Store, notificaciones, temporizador y alarma,
  hora, batería e info del dispositivo. Ejecución en dos rondas: el modelo ve el
  `ToolExecutionReport` real antes de redactar su respuesta final.
- **Emociones computacionales** (`EmotionEngine.kt`): intensidad en buckets 0/.25/.5/.75/1,
  estado estable durante la respuesta, prioridad visual GENERATING, backend emocional con
  intensidad y viñeta, respuesta neutral serena, retorno a NEUTRAL sin señal fuerte.
- **Integración**: `ChatRepository` reescrito (payload tipado, filtro en vivo de `[TOOLS]`,
  reporte a segunda ronda), `ToolManager` con eventos y hápticos sutiles throttled,
  `ToolNotifier` (canal de acción, POST_NOTIFICATIONS), `ChrisSchedulerReceiver` (alarmas que
  sobreviven al cierre).
- **Misc**: `QUERY_ALL_PACKAGES` para búsqueda de apps; `ic_notification`; versionCode 7.
- **Tests**: +TtsTextTest, +EmotionEngineTest, +ToolRegistryTest → 69 unit tests verdes.

## v0.6.0 — Personalidad, memoria 2.0, métricas y actualizador (previo)

- Memoria persistente 2.0 en Room (`chrisai.db`), relevancia por intención.
- Personalidad, emociones base y métricas de conversación.
- Actualizador vía GitHub Releases con validación de checksum SHA-256 antes de instalar.
- STT (Vosk/custom) y TTS con voz configurable; hápticos.
- NativeBridge C++ (`chriscore.cpp`) + API key en BuildConfig.

## v0.5.0 — Memoria inteligente y actualizador vía GitHub Releases (previo)

- Memoria persistente con intenciones, versionado semántico, actualizador GitHub Releases.