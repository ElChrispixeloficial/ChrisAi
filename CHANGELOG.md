# Changelog

## v1.1.0 — ChrisAI (actual)

- **Avatar 3D GL real** (`ui/avatar3d/`): `AvatarRenderer` GL ES 3.0 con rig,
  animador y `ChrisAvatar3D` composable. ChrisAI se dibuja con Vertex/Fragment
  shaders (cuerpo blanco, pantalla negra, ojos/boca cyan). Presente en la
  videollamada y, a 190×220dp, en el centro del HOME. NO es 2D ni `rotationY`.
- **Videollamada front/back con hot-swap** (`data/vision/CameraCaptureSession.kt`):
  el `switchCamera(facing)` cambia el objetivo en caliente sin cortar el
  pipeline ni `VisionFrameBus`; el preview se refleja (espejo) en frontal,
  chip "🔄 Frontal/Trasera" y etiquetas "Pausar/Reanudar"; el estado
  `cameraFacing` está en el UiState.
- **Voz real** (`ui/settings/SettingsScreen.kt` + `data/speech/`):
  - **Selector de voces** (`VoiceSection`): búsqueda, filtro por idioma
    (`VoiceLangChip`), botón "Probar" y etiqueta idioma/región.
  - **Acciones por mensaje** (`MessageActionsRow`): 🔊 Leer / ⏸ Pausar /
    ▶ Continuar / ■, 📋 Copiar (portapapeles) y ↗️ Compartir (ACTION_SEND).
  - **Barge-in real** (`data/speech/BargeInVad.kt`): VAD de energía por
    AudioRecord 16kHz, umbral adaptativo sobre el floor y cool-down; al
    interrumpir se corta el TTS y se re-escucha. Activable en Ajustes.
  - **Audio focus ducking**: el TTS atenúa la música de fondo al hablar.
- **Onboarding Drive + HOME avatar 3D** (`ui/OnboardingScreen.kt` +
  `ui/HomeScreen.kt`): `runSync` endurecido que nunca cierra la app y marca el
  onboarding como completado aunque falle; BroadcastReceiver de cambio de
  cuentas; `addAccountLauncher` que reabre el selector tras añadir cuenta.
- **Generación de imágenes** (`data/remote/OpenRouterApi.kt` +
  `data/provider/`): `images/generations` con `dall-e-3`, guardar en galería,
  compartir con FileProvider, banner de progreso con cancelar y tarjeta de
  resultado; el fallback solo saltaría si el proveedor declara la capacidad.
- **Generación de audio + reproductor independiente**
  (`data/media/MediaPlayerController.kt`): botón 🎵, TTS se sintetiza a `.wav`
  en disco y se reproduce con su propio MediaPlayer (play/pausa/progreso,
  guardar, compartir) totalmente independiente del habla viva de la llamada.
- **Developer Mode (agente local SAF)**
  (`ui/dev/DeveloperModeScreen.kt` + `data/devagent/`): elige una carpeta de tu
  dispositivo o de Google Drive vía Storage Access Framework con acceso de
  lectura persistente; lista sus archivos y adjunta el contenido de archivos de
  texto al chat como contexto. Accesible desde Ajustes.
- Migraciones Room v3→v4 (`generatedImagePath`) y v4→v5 (`audioPath`) no
  destructivas; FileProvider expone `files-path attachments/`.
- versionCode 12.
- **Tests**: el conjunto completo sigue en verde (200 unit tests).

## v1.0.0 — Release estable ChrisAI (previo)

- **Onboarding con Google Drive** (`ui/OnboardingScreen.kt` + `data/drive/`):
  primera opción "Continuar con Google" (permisos mínimos vía AccountManager +
  token `drive.file`, sin play-services); copia de seguridad con estructura
  `ChrisAI/{Conversations,Memory,Settings,Data}/`, lectora de los backups v0.8
  de la raíz, tombstones de borrado y SIN secretos en el cloud. Opción "Usar
  sin sincronización" para no depender del login local.
- **HOME** (`ui/HomeScreen.kt`): dashboard con tarjeta de Drive (estado/última
  sync), acciones rápidas (Conversar, Videollamada, Llamada, Modo estudio,
  Historial), "Guardar algo en mi memoria" y continuación de conversaciones.
- **Sesiones por contexto** (`SessionKind` + `ContextChips`): contextos
  independientes General/Estudio/Programación/Proyecto ChrisAI/Acompañante con
  sus prompts propios; migración Room v2→v3 no destructiva.
- **Videollamada independiente** (`ui/video/VideoCallScreen.kt`): pantalla
  completa con cámara del usuario + avatar ChrisAI, micrófono/pantalla/qué
  ves/colgar reutilizando la infraestructura de llamada y VisionFrameBus.
- **Avatar 3D** (`ui/components/ChrisAvatar.kt`): ChrisAI blanco redondeado con
  pantalla negra, ojos/boca cyan neón y torso "Chris AI" en Compose puro;
  reacciona a la emoción y al estado en vivo.
- **Provider Engine pulido**: reintentos acotados del mismo proveedor antes de
  fallback único; errores fatales (401/403) jamás reintentan.
- versionCode 11.
- **Tests**: +DriveServiceTest, +SyncManagerTest, +CloudCodecSettingsTest,
  +ContextEngineSessionTest → 189 unit tests verdes.

## v0.9.0 — Videollamada, Permission Center y Fast Actions (previo)

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