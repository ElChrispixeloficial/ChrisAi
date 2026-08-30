# Changelog

## v0.9.0 — Videollamada, Permission Center y Fast Actions (actual)

- **Videollamada con visión** (`data/vision/`): activación explícita de cámara
  (Camera2, captura JPEG periódica acotada, 2–60 s configurable) y de pantalla
  (MediaProjection en servicio foreground con consentimiento del sistema). Las
  capturas se publican en `VisionFrameBus` y la última se adjunta al siguiente
  mensaje para que el modelo vea lo que tienes delante; todo se para al colgar
  y muestra indicadores visibles en el chat.
- **Screen Understanding**: botón "Qué ves" en la videollamada que pregunta al
  modelo por la última captura; antecesor de modos futuros sin romper la
  latencia del chat.
- **Context Engine** (`ContextEngine` + `ChrisContextSource`): el contexto de
  la respuesta se construye desde fuentes vivas (app en primer plano, emoción,
  visión, modo estudio, memoria) y se inyecta en el payload vía bloques de
  sistema en `ChatRepository`.
- **Provider Engine** (`ProviderEngine`): OpenRouter como proveedor primario;
  Gemini como respaldo SOLO ante errores recuperables (429/timeout/5xx/red) o
  cuando el modelo actual no soporta visión y el respaldo sí. Una clave inválida
  (401/403) nunca provoca fallback. La key se resuelve en runtime
  (`apiKeyProvider`), no en build.
- **Fast Actions deterministicas** (`FastActions` + `ActionPlanner`): abrir
  apps, buscar dentro de apps, alarmas, temporizadores, hora, batería,
  info del dispositivo, colgar llamada y "explícame esto" se resuelven LOCALMENTE
  sin llamar al modelo. Planes compuestos ("abre YouTube y después pon una
  alarma") y ambigüedad → el modelo decide. `ActionContextStore` (máx. 3
  secuencias) permite "¿y cuál era el segundo?" entre llamadas.
- **Permission Center** (`PermissionCenter`): snapshot determinista de 8
  capacidades (micrófono, cámara, pantalla, notificaciones, ChrisTools, Drive,
  visión, proveedor) agrupado por secciones en Ajustes.
- **EmotionEngine 3.0** (`EmotionEffects`): una emoción = un efecto visual
  primario; frases honestas ("estado prioritario…") que nunca afirman sentir.
- **Modo estudio** y frecuencia de captura visual configurables en Ajustes.
- versionCode 10.
- **Tests**: +ActionPlannerTest, +FastActionsTest, +PermissionCenterTest,
  +EmotionEffectsTest, +ProviderEngineTest → 157 unit tests verdes.

## v0.8.1 — Llamada de voz, envio de imagenes y ajustes (previo)

- **Modo llamada** (boton 📞 en el chat): conversacion de voz continua con loop
  saludo → escucha → procesa → responde → vuelve a escuchar. `LiveStateMachine`
  por fin conectada al ViewModel (en v0.8.0 existia la infraestructura sin wiring).
  Barge-in: si hablas mientras ChrisAI escribe, tu mensaje se encola y se responde
  antes de seguir; boton de interrupcion manual; el estado se muestra en la barra
  (escuchando/procesando/hablando).
- **Envio de imagenes** (vision): boton 🖼️ con selector de fotos del sistema (sin
  permisos de almacenamiento), vista previa antes de enviar, downscale a 1280 px /
  JPEG 80 en `filesDir/attachments/`, y el contenido se manda al modelo como array
  multimodal (`text` + `image_url` base64) mediante `VisionMessage.userContentArray`
  (antes VisionMessage era solo modelo sin integracion real). Room migra a v2 con
  `ALTER TABLE messages ADD COLUMN imagePath TEXT` (no destructiva).
- **Ajustes**: nueva seccion "Llamada y vision" con 4 conmutadores — Modo llamada,
  Saludo inicial, Escucha continua y Envio de imagenes, todos configurables y por
  defecto activados (v0.8.1: cada funcion nueva configurable desde Ajustes).
- **Correcciones**: `OpenRouterApi.streamChat` acepta contenido multimodal
  (`List<Map<String, Any>>`); payload del chat tipado para vision; al inhabilitar
  una funcion se revierte el estado en marcha (colgar llamada / quitar imagen).
- versionCode 9.
- **Tests**: 125 unit tests verdes (existente); la capa multimodal y de llamada no
  introduce dependencias nuevas.

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