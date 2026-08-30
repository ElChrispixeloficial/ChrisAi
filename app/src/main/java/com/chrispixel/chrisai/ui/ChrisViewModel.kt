package com.chrispixel.chrisai.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.ChrisApplication
import com.chrispixel.chrisai.data.actions.ActionContextStore
import com.chrispixel.chrisai.data.actions.ActionPlanner
import com.chrispixel.chrisai.data.actions.FastAction
import com.chrispixel.chrisai.data.actions.PlanResult
import com.chrispixel.chrisai.data.actions.summaryLabel
import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.emotion.EmotionClassifier
import com.chrispixel.chrisai.data.emotion.EmotionEngine
import com.chrispixel.chrisai.data.emotion.EmotionState
import com.chrispixel.chrisai.data.live.LiveErrorReason
import com.chrispixel.chrisai.data.live.LiveEvent
import com.chrispixel.chrisai.data.live.LiveStage
import com.chrispixel.chrisai.data.live.LiveStateMachine
import com.chrispixel.chrisai.data.local.MemoryIntent
import com.chrispixel.chrisai.data.local.MemoryWriteResult
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.Memory
import com.chrispixel.chrisai.data.permissions.CapabilityId
import com.chrispixel.chrisai.data.permissions.CapabilityStatus
import com.chrispixel.chrisai.data.permissions.PermissionCenter
import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.data.provider.ProviderCallException
import com.chrispixel.chrisai.data.provider.ProviderErrorType
import com.chrispixel.chrisai.data.speech.SttEvent
import com.chrispixel.chrisai.data.speech.TtsStatus
import com.chrispixel.chrisai.data.tools.ToolCall
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.android.ToolEvent
import com.chrispixel.chrisai.data.update.ReleaseInfo
import com.chrispixel.chrisai.data.vision.ScreenCaptureService
import com.chrispixel.chrisai.data.vision.VisionFrameBus
import com.chrispixel.chrisai.data.vision.VisionMessage
import com.chrispixel.chrisai.data.vision.VisionSupport
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class UpdateUiState(
    val checking: Boolean = false,
    val available: ReleaseInfo? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val readyFile: File? = null,
    val message: String? = null
)

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    val selectedModel: String = BuildConfig.DEFAULT_MODEL,
    val availableModels: List<AiModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val memories: List<Memory> = emptyList(),
    val memoryFeedback: String? = null,
    val apiKeySet: Boolean = false,
    val temperature: Double = 0.7,
    val update: UpdateUiState = UpdateUiState(),
    // v0.6
    val personality: PersonalityConfig = PersonalityConfig(),
    val personalityFeedback: String? = null,
    val emotion: Emotion = Emotion.NEUTRAL,
    // v0.7
    val emotionState: EmotionState? = null,
    val toolEvents: List<ToolEvent> = emptyList(),
    val hapticsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val autoRead: Boolean = false,
    val ttsEnabled: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVoice: String = "",
    val sttLanguage: String = "",
    val ttsStatus: TtsStatus = TtsStatus.Unavailable,
    val listening: Boolean = false,
    val sttPartial: String? = null,
    val listeningError: String? = null,
    val ttsError: String? = null,
    val exportText: String? = null,
    val exportTextId: String? = null,
    // metric hops/aggregation (root totals across the current session)
    val messagesSent: Int = 0,
    // v0.8.1: call mode (continuous voice conversation).
    val callActive: Boolean = false,
    val liveStage: LiveStage? = null,
    val callModeEnabled: Boolean = true,
    val callGreetingEnabled: Boolean = true,
    val callContinuousEnabled: Boolean = true,
    val imagesEnabled: Boolean = true,
    // v0.8.1: pending image attachment (absolute path to a local JPEG).
    val pendingImage: String? = null,
    val imageError: String? = null,
    // v0.9: videollamada (controlled visual capture), study mode, permissions.
    val videoCallActive: Boolean = false,
    val cameraActive: Boolean = false,
    val screenSharing: Boolean = false,
    val videoError: String? = null,
    val studyModeEnabled: Boolean = false,
    val captureIntervalSec: Int = 5,
    val lastVisionLabel: String? = null,
    val hasVisionFrame: Boolean = false,
    val permissions: List<CapabilityStatus> = emptyList(),
    val driveConnected: Boolean = false,
    val providerFallbackAvailable: Boolean = false
)

class ChrisViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as ChrisApplication).container

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamingJob: Job? = null

    // v0.8.1: deterministic call/session state machine (Live infra, now wired).
    private val live = LiveStateMachine()
    private var callWaitJob: Job? = null
    private var callPendingText: String? = null

    // v0.9: cross-action memory + vision capture bookkeeping.
    private val actionContext = ActionContextStore()
    private var latestVisionPath: String? = null

    init {
        loadInitial()
        observeVisionSources()
        observeVisionProblems()
        refreshPermissions()
    }

    private fun loadInitial() {
        viewModelScope.launch {
            val sessions = container.chatStore.list()
            val first = sessions.firstOrNull()
            _state.value = ChatUiState(
                sessions = sessions,
                currentSessionId = first?.id,
                messages = first?.messages ?: emptyList(),
                selectedModel = container.settings.model.value,
                availableModels = ensureDefaultModel(container.settings.availableModels.value),
                memories = container.memory.list(),
                apiKeySet = container.settings.apiKey.value.isNotBlank(),
                temperature = container.settings.temperature.value,
                personality = container.settings.personality.value,
                hapticsEnabled = container.settings.hapticsEnabled.value,
                animationsEnabled = container.settings.animationsEnabled.value,
                autoRead = container.settings.autoRead.value,
                ttsEnabled = container.settings.ttsEnabled.value,
                ttsRate = container.settings.ttsRate.value,
                ttsPitch = container.settings.ttsPitch.value,
                ttsVoice = container.settings.ttsVoice.value,
                sttLanguage = container.settings.sttLanguage.value,
                callModeEnabled = container.settings.callModeEnabled.value,
                callGreetingEnabled = container.settings.callGreetingEnabled.value,
                callContinuousEnabled = container.settings.callContinuousEnabled.value,
                imagesEnabled = container.settings.imagesEnabled.value,
                studyModeEnabled = container.settings.studyModeEnabled.value,
                captureIntervalSec = container.settings.captureIntervalSec.value,
                providerFallbackAvailable = container.providerEngine.fallbackVisionCapable,
                messagesSent = first?.messages?.count { it.role == ChatRole.USER } ?: 0
            )
            container.contextSource.studyActive = _state.value.studyModeEnabled
            observeTts()
            checkForUpdates(
                quiet = true
            )
        }
    }

    private fun observeTts() {
        viewModelScope.launch {
            container.tts.status.collect { status ->
                _state.value = _state.value.copy(
                    ttsStatus = status,
                    ttsError = if (status is TtsStatus.Error) status.message else _state.value.ttsError
                )
            }
        }
    }

    // ------------------------------------------------------ v0.9 vision sources

    /** Mirrors real capture state from the camera session and projection service. */
    private fun observeVisionSources() {
        viewModelScope.launch {
            VisionFrameBus.cameraActive.collect { active ->
                _state.update {
                    it.copy(
                        cameraActive = active,
                        videoCallActive = it.callActive && (active || it.screenSharing)
                    )
                }
                refreshPermissions()
            }
        }
        viewModelScope.launch {
            VisionFrameBus.screenActive.collect { active ->
                _state.update {
                    it.copy(
                        screenSharing = active,
                        videoCallActive = it.callActive && (it.cameraActive || active)
                    )
                }
                refreshPermissions()
            }
        }
        viewModelScope.launch {
            VisionFrameBus.frames.collect { frame ->
                if (frame.path == latestVisionPath) return@collect
                latestVisionPath = frame.path
                val sourceLabel = if (frame.source == VisionFrameBus.SOURCE_CAMERA) "cámara" else "pantalla"
                val stamp = java.text.SimpleDateFormat(
                    "HH:mm", java.util.Locale.getDefault()
                ).format(java.util.Date())
                _state.update {
                    it.copy(hasVisionFrame = true, lastVisionLabel = "Captura de $sourceLabel a las $stamp")
                }
                container.contextSource.visionAnalysis = _state.value.lastVisionLabel
            }
        }
    }

    private fun observeVisionProblems() {
        viewModelScope.launch {
            VisionFrameBus.problems.collect { problem ->
                _state.value = _state.value.copy(videoError = problem.message)
                // Not a capture source (service shut down) → sync flags below.
                refreshPermissions()
            }
        }
    }

    /** Recomputes the Permission Center snapshot from the live platform state. */
    private fun refreshPermissions() {
        val app = getApplication<Application>()
        val current = _state.value
        val snapshot = PermissionCenter.snapshot(
            micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                app, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            screenShareActive = current.screenSharing,
            notificationsEnabled = com.chrispixel.chrisai.data.tools.android.ToolNotifier.canNotify(app),
            toolsEnabled = true,
            driveConnected = current.driveConnected,
            visionAvailable = VisionMessage.support(current.selectedModel) != VisionSupport.NOT_SUPPORTED ||
                container.providerEngine.fallbackVisionCapable,
            fallbackProvider = container.providerEngine.fallbackVisionCapable
        )
        _state.update { it.copy(permissions = snapshot) }
    }

    // -------------------------------------------------- v0.9 videollamada

    fun toggleCamera() {
        if (_state.value.cameraActive) stopCamera() else startCamera()
    }

    fun startCamera() {
        if (_state.value.cameraActive) return
        val app = getApplication<Application>()
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _state.value = _state.value.copy(videoError = "Necesita dar permiso de cámara para la videollamada.")
            return
        }
        val interval = _state.value.captureIntervalSec
        val dir = File(app.filesDir, ATTACH_DIR)
        container.camera.start(
            intervalSec = interval,
            dir = dir,
            onFrame = { file -> VisionFrameBus.publish(VisionFrameBus.SOURCE_CAMERA, file.absolutePath) },
            onProblem = { message ->
                _state.update { it.copy(videoError = message) }
                refreshPermissions()
            }
        )
        container.haptics.confirm()
    }

    fun stopCamera() {
        container.camera.stop()
        container.haptics.confirm()
    }

    /** Called by the UI after the system screen-projection dialog. */
    fun onScreenPermissionResult(resultCode: Int, data: Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            _state.value = _state.value.copy(videoError = "Rechazaste compartir la pantalla.")
            return
        }
        ScreenCaptureService.start(getApplication(), resultCode, data)
        container.haptics.confirm()
    }

    fun stopScreenSharing() {
        ScreenCaptureService.stop(getApplication())
        container.haptics.confirm()
    }

    fun changeCaptureInterval(seconds: Int) {
        val bounded = seconds.coerceIn(2, 60)
        _state.value = _state.value.copy(captureIntervalSec = bounded)
        viewModelScope.launch { container.settings.setCaptureIntervalSec(bounded) }
        // Live-apply to an active camera session.
        if (_state.value.cameraActive) {
            container.camera.stop()
            startCamera()
        }
    }

    fun setStudyModeEnabled(enabled: Boolean) {
        container.contextSource.studyActive = enabled
        viewModelScope.launch { container.settings.setStudyModeEnabled(enabled) }
        _state.value = _state.value.copy(studyModeEnabled = enabled)
    }

    fun dismissVideoError() {
        _state.value = _state.value.copy(videoError = null)
    }

    // ------------------------------------------------------------------ chat

    fun sendMessage(text: String) {
        val current = _state.value
        if (current.pendingImage != null) {
            sendImageMessage(text.trim(), current.pendingImage)
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (current.streaming) return

        if (container.settings.apiKey.value.isBlank()) {
            _state.value = current.copy(error = "No hay una API key disponible en esta build.")
            return
        }

        // v0.9 fast actions: deterministic commands (open app, alarms, colgar,
        // contexto entre acciones) run locally before hitting the model.
        when (val plan = ActionPlanner.plan(trimmed)) {
            is PlanResult.Plan -> {
                container.haptics.confirm()
                handleFastPlan(plan, trimmed)
                return
            }
            else -> Unit
        }

        container.haptics.confirm()

        viewModelScope.launch {
            val localReply = handleMemoryIntent(trimmed)
            if (localReply != null) {
                appendLocalExchange(trimmed, localReply)
                if (_state.value.callActive) speakReplyAndLoop(localReply)
                return@launch
            }
            // During an active video call, attach the latest bounded capture.
            val currentState = _state.value
            val frame = if (currentState.videoCallActive && latestVisionPath != null) latestVisionPath else null
            startStreaming(
                trimmed,
                visionImagePath = frame,
                needsVision = frame != null
            )
        }
    }

    /** Sends a user message with an attached image (multimodal/vision). */
    private fun sendImageMessage(caption: String, imagePath: String) {
        val current = _state.value
        if (current.streaming) return
        if (container.settings.apiKey.value.isBlank()) {
            _state.value = current.copy(error = "No hay una API key disponible en esta build.")
            return
        }
        if (!current.imagesEnabled) {
            _state.value = current.copy(imageError = "El envío de imágenes está desactivado en Ajustes.")
            return
        }
        val support = VisionMessage.support(current.selectedModel)
        if (support == VisionSupport.NOT_SUPPORTED) {
            _state.value = current.copy(
                imageError = VisionMessage.unsupportedErrorMessage(current.selectedModel)
            )
            return
        }
        container.haptics.confirm()
        _state.value = current.copy(pendingImage = null, imageError = null)
        startStreaming(caption, imagePath)
    }

    fun attachImage(uri: Uri) {
        val current = _state.value
        if (!current.imagesEnabled) {
            _state.value = current.copy(imageError = "El envío de imágenes está desactivado en Ajustes.")
            return
        }
        val support = VisionMessage.support(current.selectedModel)
        if (support == VisionSupport.NOT_SUPPORTED) {
            _state.value = current.copy(
                imageError = VisionMessage.unsupportedErrorMessage(current.selectedModel)
            )
            return
        }
        _state.value = current.copy(imageError = null)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { saveImageToFilesDir(uri) }
            if (saved != null) {
                _state.value = _state.value.copy(pendingImage = saved, imageError = null)
                container.haptics.confirm()
            } else {
                _state.value = _state.value.copy(imageError = "No se pudo leer la imagen seleccionada.")
            }
        }
    }

    fun clearPendingImage() {
        _state.value = _state.value.copy(pendingImage = null, imageError = null)
    }

    fun dismissImageError() {
        _state.value = _state.value.copy(imageError = null)
    }

    /** Copies, downscales and compresses the picked image to app storage. */
    private fun saveImageToFilesDir(uri: Uri): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scale = if (maxDim > MAX_IMAGE_DIM) maxDim.toFloat() / MAX_IMAGE_DIM else 1f
            val out: Bitmap = if (scale > 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width / scale).toInt().coerceAtLeast(1),
                    (bitmap.height / scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val dir = File(getApplication<Application>().filesDir, ATTACH_DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            if (out !== bitmap) {
                bitmap.recycle()
                out.recycle()
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun stopStreaming() {
        if (!_state.value.streaming) return
        streamingJob?.cancel()
        viewModelScope.launch { container.providerEngine.cancelActiveCall() }
    }

    fun openSession(id: String) {
        streamingJob?.cancel()
        viewModelScope.launch {
            val session = container.chatStore.find(id) ?: return@launch
            _state.value = _state.value.copy(
                currentSessionId = session.id,
                messages = session.messages,
                error = null,
                emotion = Emotion.NEUTRAL,
                emotionState = null,
                messagesSent = session.messages.count { it.role == ChatRole.USER }
            )
        }
    }

    fun startNewChat() {
        streamingJob?.cancel()
        container.tts.stop()
        val current = _state.value
        _state.value = current.copy(
            currentSessionId = null,
            messages = emptyList(),
            error = null,
            emotion = Emotion.NEUTRAL,
            emotionState = null,
            toolEvents = emptyList(),
            messagesSent = 0,
            listening = false,
            sttPartial = null
        )
    }

    fun deleteSession(id: String) {
        streamingJob?.cancel()
        val current = _state.value
        val sessions = current.sessions.filterNot { it.id == id }
        val wasCurrent = current.currentSessionId == id
        _state.value = current.copy(
            sessions = sessions,
            currentSessionId = if (wasCurrent) null else current.currentSessionId,
            messages = if (wasCurrent) emptyList() else current.messages,
            messagesSent = if (wasCurrent) 0 else current.messagesSent,
            emotion = if (wasCurrent) Emotion.NEUTRAL else current.emotion,
            emotionState = if (wasCurrent) null else current.emotionState,
            toolEvents = if (wasCurrent) emptyList() else current.toolEvents
        )
        viewModelScope.launch { container.chatStore.delete(id) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ model

    fun selectModel(model: String) {
        if (model.isBlank()) return
        viewModelScope.launch { container.settings.setSelectedModel(model) }
        _state.value = _state.value.copy(selectedModel = model)
    }

    fun refreshModels() {
        val current = _state.value
        if (current.modelsLoading) return
        _state.value = current.copy(modelsLoading = true)
        viewModelScope.launch {
            try {
                val models = ensureDefaultModel(container.chatRepository.fetchModels())
                container.settings.saveCachedModels(models)
                _state.value = _state.value.copy(availableModels = models, modelsLoading = false, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(modelsLoading = false, error = "No se pudieron cargar los modelos")
            }
        }
    }

    // ------------------------------------------------------------------ settings

    fun setApiKey(key: String) {
        val normalized = key.trim()
        viewModelScope.launch {
            container.settings.setApiKey(normalized)
            _state.value = _state.value.copy(apiKeySet = container.settings.apiKey.value.isNotBlank())
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            container.settings.setApiKey("")
            _state.value = _state.value.copy(apiKeySet = container.settings.apiKey.value.isNotBlank())
        }
    }

    fun setTemperature(value: Double) {
        val bounded = value.coerceIn(0.0, 1.0)
        viewModelScope.launch { container.settings.setTemperature(bounded) }
        _state.value = _state.value.copy(temperature = bounded)
    }

    // ------------------------------------------------------- v0.6 personality

    fun setPersonality(config: PersonalityConfig) {
        viewModelScope.launch {
            container.settings.setPersonality(config)
            _state.value = _state.value.copy(
                personality = container.settings.personality.value,
                personalityFeedback = "Personalidad actualizada."
            )
        }
    }

    fun resetPersonality() {
        setPersonality(PersonalityConfig())
    }

    fun dismissPersonalityFeedback() {
        _state.value = _state.value.copy(personalityFeedback = null)
    }

    // ------------------------------------------------------ v0.6 preferences

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setHapticsEnabled(enabled) }
        _state.value = _state.value.copy(hapticsEnabled = enabled)
        if (enabled) container.haptics.confirm()
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAnimationsEnabled(enabled) }
        _state.value = _state.value.copy(animationsEnabled = enabled)
    }

    fun setAutoRead(enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoRead(enabled) }
        _state.value = _state.value.copy(autoRead = enabled)
        if (!enabled) container.tts.stop()
    }

    // -------------------------------------------------- v0.8.1 feature flags

    fun setCallModeEnabled(enabled: Boolean) {
        if (!enabled && _state.value.callActive) endCall()
        viewModelScope.launch { container.settings.setCallModeEnabled(enabled) }
        _state.value = _state.value.copy(callModeEnabled = enabled)
    }

    fun setCallGreetingEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setCallGreetingEnabled(enabled) }
        _state.value = _state.value.copy(callGreetingEnabled = enabled)
    }

    fun setCallContinuousEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setCallContinuousEnabled(enabled) }
        _state.value = _state.value.copy(callContinuousEnabled = enabled)
    }

    fun setImagesEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setImagesEnabled(enabled) }
        _state.value = _state.value.copy(imagesEnabled = enabled)
        if (!enabled) clearPendingImage()
    }

    // --------------------------------------------------------------- v0.6 TTS

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setTtsEnabled(enabled) }
        _state.value = _state.value.copy(ttsEnabled = enabled)
        if (enabled) {
            container.tts.initialize { }
        } else {
            container.tts.shutdown()
        }
    }

    fun setTtsRate(rate: Float) {
        val bounded = rate.coerceIn(0.5f, 2.0f)
        viewModelScope.launch { container.settings.setTtsRate(bounded) }
        _state.value = _state.value.copy(ttsRate = bounded)
    }

    fun setTtsPitch(pitch: Float) {
        val bounded = pitch.coerceIn(0.5f, 2.0f)
        viewModelScope.launch { container.settings.setTtsPitch(bounded) }
        _state.value = _state.value.copy(ttsPitch = bounded)
    }

    fun setTtsVoice(voiceName: String) {
        viewModelScope.launch { container.settings.setTtsVoice(voiceName) }
        _state.value = _state.value.copy(ttsVoice = voiceName)
        container.tts.setVoicePreference(voiceName)
    }

    fun previewTts(text: String) {
        val current = _state.value
        if (!current.ttsEnabled) return
        container.tts.preview(text, current.ttsRate, current.ttsPitch)
    }

    fun ttsVoices(): List<String> = container.tts.listVoices().map { it.name }
    fun ttsVoiceInfos(): List<com.chrispixel.chrisai.data.speech.TtsVoiceInfo> =
        container.tts.listVoices()

    fun speakMessage(messageId: String, text: String) {
        if (!_state.value.ttsEnabled) return
        val current = _state.value
        val pitch = current.ttsPitch
        val status = current.ttsStatus
        when {
            status is TtsStatus.Speaking && status.messageId == messageId -> {
                container.tts.pause()
            }
            status is TtsStatus.Paused && status.messageId == messageId -> {
                container.tts.resume(current.ttsRate, pitch)
            }
            else -> {
                container.tts.speakWithEmotion(
                    messageId,
                    text,
                    current.ttsRate,
                    pitch,
                    current.emotion
                )
            }
        }
    }

    fun stopSpeech() {
        container.tts.stop()
    }

    fun dismissTtsError() {
        _state.value = _state.value.copy(ttsError = null)
    }

    // --------------------------------------------------------------- v0.6 STT

    fun startListening() {
        if (_state.value.listening) return
        _state.value = _state.value.copy(listening = true, listeningError = null)
        container.stt.start { event ->
            viewModelScope.launch {
                when (event) {
                    is SttEvent.Listening -> _state.update { it.copy(listening = true, listeningError = null) }
                    is SttEvent.Partial -> _state.update { it.copy(sttPartial = event.text) }
                    is SttEvent.Result -> {
                        _state.update {
                            it.copy(listening = false, sttPartial = null, listeningError = null)
                        }
                        sendMessage(event.text)
                    }
                    is SttEvent.Error -> _state.update {
                        it.copy(listening = false, sttPartial = null, listeningError = event.message)
                    }
                    is SttEvent.Processing -> _state.update { it.copy(listening = true) }
                }
            }
        }
    }

    fun stopListening() {
        container.stt.cancel()
        _state.value = _state.value.copy(listening = false, sttPartial = null)
    }

    fun dismissListeningError() {
        _state.value = _state.value.copy(listeningError = null)
    }

    // ------------------------------------------------------- v0.8.1 call mode

    /** Starts or ends the voice call (continuous conversation). */
    fun toggleCall() {
        if (_state.value.callActive) endCall() else startCall()
    }

    /** Wired stream of the deterministic Live session state (v0.8 infra). */
    private fun syncLiveStage() {
        _state.value = _state.value.copy(liveStage = live.state.stage)
    }

    private fun startCall() {
        val current = _state.value
        if (current.callActive || !current.callModeEnabled) return
        if (current.streaming) stopStreaming()

        live.on(LiveEvent.Start)
        _state.value = current.copy(
            callActive = true,
            liveStage = live.state.stage,
            listening = false,
            listeningError = null,
            sttPartial = null,
            error = null
        )
        container.haptics.confirm()

        val greetingWanted = current.ttsEnabled && current.callGreetingEnabled
        if (greetingWanted && container.tts.available()) {
            container.tts.speakWithEmotion(
                CALL_UTTERANCE_ID,
                CALL_GREETING,
                current.ttsRate,
                current.ttsPitch,
                Emotion.NEUTRAL
            )
            waitForUtteranceThenListen()
        } else if (greetingWanted) {
            container.tts.initialize { result ->
                if (result is com.chrispixel.chrisai.data.speech.TtsController.InitResult.Ok) {
                    container.tts.speakWithEmotion(
                        CALL_UTTERANCE_ID,
                        CALL_GREETING,
                        _state.value.ttsRate,
                        _state.value.ttsPitch,
                        Emotion.NEUTRAL
                    )
                    waitForUtteranceThenListen()
                } else {
                    armCallListening()
                }
            }
        } else {
            armCallListening()
        }
    }

    fun endCall() {
        if (!_state.value.callActive) return
        callWaitJob?.cancel()
        callPendingText = null
        if (_state.value.streaming) {
            streamingJob?.cancel()
            viewModelScope.launch { container.providerEngine.cancelActiveCall() }
        }
        // v0.9: stop any active visual capture with the call.
        if (_state.value.cameraActive) container.camera.stop()
        if (_state.value.screenSharing) ScreenCaptureService.stop(getApplication())
        container.tts.stop()
        container.stt.cancel()
        live.on(LiveEvent.End)
        _state.value = _state.value.copy(
            callActive = false,
            liveStage = null,
            listening = false,
            sttPartial = null,
            listeningError = null,
            videoCallActive = false
        )
    }

    /** Waits for the current TTS utterance to finish, then re-listens. */
    private fun waitForUtteranceThenListen() {
        callWaitJob?.cancel()
        callWaitJob = viewModelScope.launch {
            try {
                container.tts.status.first { status ->
                    status is TtsStatus.Idle ||
                        status is TtsStatus.Error ||
                        status is TtsStatus.Unavailable ||
                        status is TtsStatus.Paused
                }
            } catch (_: CancellationException) {
                return@launch
            }
            val current = _state.value
            if (!current.callActive) return@launch
            live.on(LiveEvent.TtsFinished)
            syncLiveStage()
            if (!current.callContinuousEnabled) return@launch
            armCallListening()
        }
    }

    /** Arms STT for the current call turn (no-op while streaming/generating). */
    private fun armCallListening() {
        val current = _state.value
        if (!current.callActive || current.listening) return
        if (current.streaming) return

        _state.value = current.copy(listening = true, listeningError = null, sttPartial = null)
        val ok = container.stt.start { event -> handleCallStt(event) }
        if (!ok) {
            live.on(LiveEvent.Failure(LiveErrorReason.STT_FAILED))
            syncLiveStage()
            _state.value = _state.value.copy(listening = false)
            handleCallFailure("Este dispositivo no tiene reconocimiento de voz.")
        }
    }

    /** Renders STT events inside an active call. */
    private fun handleCallStt(event: SttEvent) {
        viewModelScope.launch {
            when (event) {
                is SttEvent.Listening -> _state.update {
                    it.copy(listening = true, listeningError = null)
                }
                is SttEvent.Partial -> _state.update { it.copy(sttPartial = event.text) }
                is SttEvent.Result -> {
                    _state.update {
                        it.copy(listening = false, sttPartial = null, listeningError = null)
                    }
                    live.on(LiveEvent.TranscriptionReady)
                    live.rememberUserText(event.text)
                    syncLiveStage()

                    // Barge-in while a reply is still streaming: queue and send
                    // after it finishes, mirroring a real call.
                    if (_state.value.streaming) {
                        callPendingText = event.text
                    } else {
                        sendMessage(event.text)
                    }
                }
                is SttEvent.Processing -> _state.update { it.copy(listening = true) }
                is SttEvent.Error -> {
                    _state.update {
                        it.copy(listening = false, sttPartial = null, listeningError = event.message)
                    }
                    live.on(LiveEvent.Failure(errorReason(event.message)))
                    syncLiveStage()
                    handleCallFailure(event.message)
                }
            }
        }
    }

    private fun errorReason(message: String): LiveErrorReason = when {
        message.contains("No se entendió") -> LiveErrorReason.STT_NO_MATCH
        message.contains("No se detectó voz") -> LiveErrorReason.STT_TIMEOUT
        message.contains("permiso", ignoreCase = true) -> LiveErrorReason.NO_MIC_PERMISSION
        message.contains("reconocimiento") -> LiveErrorReason.STT_FAILED
        else -> LiveErrorReason.STT_FAILED
    }

    /** Bounded retries while the user stays silent; stops at the state machine's bound. */
    private fun handleCallFailure(message: String) {
        if (!_state.value.callActive) return
        if (live.lockedOut) {
            _state.value = _state.value.copy(
                listening = false,
                liveStage = live.state.stage,
                listeningError = LiveErrorReason.TOO_MANY_FAILURES.message
            )
            return
        }
        if (!_state.value.callContinuousEnabled) return
        // Go back to LISTENING unless the machine rejected it (too many failures).
        live.on(LiveEvent.ListenAgain)
        syncLiveStage()
        if (live.state.stage == LiveStage.LISTENING) armCallListening()
    }

    /** After a reply finished (streamed or local): speak it, then re-listen. */
    private fun handleCallReply(messageId: String, text: String, emotionState: EmotionState?) {
        live.on(LiveEvent.GenerationFinished)
        syncLiveStage()
        speakReplyAndLoop(text)
    }

    /** Speaks a call reply (or falls back to text) and continues the loop. */
    private fun speakReplyAndLoop(text: String) {
        val current = _state.value
        if (!current.callActive) return
        if (handleBargeInQueue()) return

        if (current.ttsEnabled && text.isNotBlank()) {
            val emotion = current.emotion
            container.tts.speakWithEmotion(
                CALL_UTTERANCE_ID,
                text,
                current.ttsRate,
                current.ttsPitch,
                emotion
            )
            waitForUtteranceThenListen()
        } else {
            live.on(LiveEvent.TtsFinished)
            syncLiveStage()
            viewModelScope.launch {
                kotlinx.coroutines.delay(250)
                if (_state.value.callActive && _state.value.callContinuousEnabled) armCallListening()
            }
        }
    }

    /** If the user spoke while the model was still writing, answer that now. */
    private fun handleBargeInQueue(): Boolean {
        val pending = callPendingText
        callPendingText = null
        if (pending != null && pending.isNotBlank()) {
            sendMessage(pending)
            return true
        }
        return false
    }

    /** Tap the mic during a call = barge in and speak immediately. */
    fun interruptCall() {
        if (!_state.value.callActive) return
        callWaitJob?.cancel()
        container.tts.stop()
        live.on(LiveEvent.BargeIn)
        live.on(LiveEvent.Interrupt)
        live.on(LiveEvent.ListenAgain)
        syncLiveStage()
        if (live.state.stage == LiveStage.LISTENING) armCallListening()
    }

    // ------------------------------------------------- v0.9 fast actions

    /** Executes a locally-resolvable plan (ordered steps, no model round-trip). */
    private fun handleFastPlan(plan: PlanResult.Plan, original: String) {
        // "Explica esta pantalla": needs the vision model, handled separately.
        if (plan.steps.any { it is FastAction.ExplainScreen }) {
            handleExplainScreen()
            return
        }
        viewModelScope.launch {
            val events = mutableListOf<ToolEvent>()
            val lines = mutableListOf<String>()
            var endedCall = false
            for (action in plan.steps) {
                when (action) {
                    is FastAction.EndCall -> {
                        endedCall = true
                        if (_state.value.callActive) endCall()
                    }
                    else -> runFastAction(action, events)?.let { lines += it }
                }
            }
            actionContext.push(
                plan.steps.mapIndexed { index, a -> ActionContextStore.Step(index + 1, a.summaryLabel()) }
            )
            if (events.isNotEmpty()) _state.update { it.copy(toolEvents = events) }
            if (lines.isEmpty()) {
                if (endedCall && !_state.value.callActive) {
                    val done = "Llamada finalizada."
                    appendLocalExchange(original, done)
                    if (_state.value.callActive) speakReplyAndLoop(done)
                }
                return@launch
            }
            val body = lines.joinToString("\n")
            val reply = if (plan.expectsSummary) "He hecho esto por ti:\n\n$body" else body
            appendLocalExchange(original, reply)
            if (events.isNotEmpty() && events.any { it.status == ToolResultStatus.SUCCESS }) {
                container.haptics.confirm()
            }
            if (_state.value.callActive) speakReplyAndLoop(reply)
        }
    }

    /** Runs one deterministic action, emitting a discrete [ToolEvent]. */
    private suspend fun runFastAction(action: FastAction, events: MutableList<ToolEvent>): String? = when (action) {
        is FastAction.OpenApp -> runTool("open_app", mapOf("appName" to action.query), events)
        is FastAction.SearchInApp -> {
            val target = action.appLabel ?: action.searchQuery
            val opened = runTool("open_app", mapOf("appName" to target), events)
            when {
                opened == null -> "No pude abrir ${action.appLabel ?: action.searchQuery}."
                opened.startsWith("✓") ->
                    "$opened Para buscar «${action.searchQuery}», usa la barra de búsqueda de ${action.appLabel ?: action.searchQuery}."
                else -> opened
            }
        }
        is FastAction.OpenSettings -> openSystemSettings(events)
        is FastAction.SetAlarm -> runTool(
            "create_alarm",
            mapOf("hours" to action.hour.toString(), "minutes" to action.minute.toString()),
            events
        )
        is FastAction.SetTimer -> runTool("create_timer", mapOf("durationSeconds" to (action.minutes * 60).toString()), events)
        is FastAction.WhatTime -> runTool("get_time", emptyMap(), events)
        is FastAction.Battery -> runTool("get_battery_status", emptyMap(), events)
        is FastAction.DeviceInfo -> runTool("get_device_info", emptyMap(), events)
        is FastAction.AskContext -> actionContext.resolve(action.reference)
            ?.let { "Ese paso era: ${it.label}${if (it.detail.isNotBlank()) " — ${it.detail}" else ""}" }
            ?: "No tengo registrado ese paso."
        else -> null
    }

    private suspend fun runTool(id: String, args: Map<String, String>, events: MutableList<ToolEvent>): String? {
        val results = container.tools.execute(ToolCall(id = id, arguments = args))
        events += ToolEvent(id, results.status, results.message)
        if (results.status == ToolResultStatus.SUCCESS) container.haptics.pulse()
        return results.message
    }

    /** Honest, key-safe message for provider failures (never logs secrets). */
    private fun friendlyProviderError(e: ProviderCallException): String = when {
        e.status == 401 -> "El proveedor rechazó la API key (401). Revísala en Ajustes."
        e.status == 403 -> "El proveedor denegó el acceso (403). Revisa credenciales y permisos."
        e.kind == ProviderErrorType.RETRYABLE -> e.message ?: "El proveedor está saturado o tardó demasiado."
        else -> e.message ?: "Error con el proveedor de IA."
    }

    private fun openSystemSettings(events: MutableList<ToolEvent>): String? {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
            events += ToolEvent("open_settings", ToolResultStatus.SUCCESS, "✓ Abriendo ajustes del sistema.")
            "✓ Abriendo ajustes del sistema."
        } catch (_: Exception) {
            "No pude abrir los ajustes del sistema."
        }
    }

    /** "Explica esta pantalla / dime qué ves": asks the model with the latest frame. */
    fun explainScreen() {
        handleExplainScreen()
    }

    private fun handleExplainScreen() {
        val frame = latestVisionPath
        if (frame == null) {
            val reply = "Activa la videollamada (cámara o pantalla) para que pueda ver lo que tienes delante."
            viewModelScope.launch {
                appendLocalExchange("Explica esta pantalla.", reply)
                if (_state.value.callActive) speakReplyAndLoop(reply)
            }
            return
        }
        startStreaming(
            "Describe lo que se ve en la captura de pantalla/cámara y responde a lo que te he pedido.",
            visionImagePath = frame,
            needsVision = true
        )
    }

    // ------------------------------------------------------------ v0.6 history

    fun renameSession(id: String, newTitle: String) {
        viewModelScope.launch {
            val ok = container.chatStore.rename(id, newTitle)
            if (!ok) return@launch
            val sessions = _state.value.sessions.map { session ->
                if (session.id == id) session.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis()) else session
            }.sortedByDescending { it.updatedAt }
            _state.value = _state.value.copy(sessions = sessions)
        }
    }

    fun exportSession(id: String) {
        viewModelScope.launch {
            container.chatStore.exportText(id)?.let { text ->
                _state.value = _state.value.copy(exportText = text, exportTextId = id)
            }
        }
    }

    fun clearExport() {
        _state.value = _state.value.copy(exportText = null, exportTextId = null)
    }

    // ------------------------------------------------------------------ memory

    fun addMemory(text: String) {
        viewModelScope.launch {
            val result = container.memory.add(text)
            val similar = container.memory.findSimilar(text)
            val feedback = when (result) {
                MemoryWriteResult.Added ->
                    if (similar.isNotEmpty()) "Lo recordaré. Nota: hay un recuerdo parecido: \"${similar.first().text}\""
                    else "Lo recordaré. 🧠"
                else -> "Ya existía ese recuerdo."
            }
            _state.value = _state.value.copy(
                memories = container.memory.list(),
                memoryFeedback = feedback
            )
        }
    }

    fun editMemory(id: String, newText: String) {
        viewModelScope.launch {
            val result = container.memory.edit(id, newText)
            _state.value = _state.value.copy(
                memories = container.memory.list(),
                memoryFeedback = when (result) {
                    MemoryWriteResult.Updated -> "Recuerdo actualizado."
                    MemoryWriteResult.Duplicate -> "Ya existe un recuerdo con ese texto."
                    else -> "No se pudo actualizar el recuerdo."
                }
            )
        }
    }

    fun removeMemory(text: String) {
        viewModelScope.launch {
            container.memory.removeContaining(text)
            _state.value = _state.value.copy(
                memories = container.memory.list(),
                memoryFeedback = "Recuerdo eliminado."
            )
        }
    }

    fun dismissMemoryFeedback() {
        _state.value = _state.value.copy(memoryFeedback = null)
    }

    // ------------------------------------------------------------------ updates

    fun checkForUpdates(quiet: Boolean = false) {
        val current = _state.value.update
        if (current.checking || current.downloading || current.available != null) return
        _state.value = _state.value.copy(update = UpdateUiState(checking = true))
        viewModelScope.launch {
            try {
                val info = container.updater.checkForUpdates(BuildConfig.VERSION_NAME)
                _state.value = _state.value.copy(
                    update = if (info == null) {
                        UpdateUiState(message = if (quiet) null else "Estás al día: v${BuildConfig.VERSION_NAME}")
                    } else {
                        UpdateUiState(available = info)
                    }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    update = UpdateUiState(message = "No se pudo consultar actualizaciones: ${redactedError(e)}")
                )
            }
        }
    }

    fun downloadUpdate() {
        val release = _state.value.update.available ?: return
        if (_state.value.update.downloading) return
        _state.value = _state.value.copy(update = UpdateUiState(available = release, downloading = true, progress = 0f))
        viewModelScope.launch {
            try {
                val file = container.updater.downloadApk(release) { progress ->
                    _state.update { it.copy(update = it.update.copy(progress = progress)) }
                }
                _state.value = _state.value.copy(
                    update = UpdateUiState(available = release, downloading = false, progress = 1f, readyFile = file)
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    update = UpdateUiState(available = release, message = "Error en la descarga: ${redactedError(e)}")
                )
            }
        }
    }

    fun installUpdate(context: android.content.Context) {
        val file = _state.value.update.readyFile ?: return
        try {
            context.startActivity(container.updater.installIntent(file))
        } catch (e: android.content.ActivityNotFoundException) {
            _state.value = _state.value.copy(
                update = _state.value.update.copy(message = "No se encontró un instalador de aplicaciones.")
            )
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(
                update = _state.value.update.copy(message = "Permite instalar apps de fuentes desconocidas en Ajustes del sistema.")
            )
        }
    }

    fun clearUpdateMessage() {
        _state.value = _state.value.copy(update = _state.value.update.copy(message = null))
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun handleMemoryIntent(text: String): String? = when {
        MemoryIntent.forgetAll(text) -> {
            container.memory.removeAll()
            "He borrado todos mis recuerdos. 🧠"
        }
        else -> {
            MemoryIntent.saveText(text)?.let { toSave ->
                when (container.memory.add(toSave)) {
                    MemoryWriteResult.Added -> "Lo recordaré. 🧠\n\n\"$toSave\""
                    else -> "Ya recordaba eso. 🧠"
                }
            } ?: MemoryIntent.forgetText(text)?.let { toForget ->
                val removed = container.memory.removeContaining(toForget)
                if (removed > 0) "He olvidado $removed recuerdo(s) sobre eso. 🧠"
                else "No tengo ningún recuerdo sobre \"$toForget\"."
            } ?: if (MemoryIntent.listRequested(text)) {
                val memories = container.memory.list()
                if (memories.isEmpty()) {
                    "Mi memoria está vacía."
                } else {
                    "Estos son mis recuerdos:\n\n" + memories.joinToString("\n") { "- ${it.text}" }
                }
            } else {
                null
            }
        }
    }

    private fun startStreaming(
        text: String,
        imagePath: String? = null,
        visionImagePath: String? = null,
        needsVision: Boolean = imagePath != null
    ) {
        val current = _state.value
        val model = current.selectedModel
        val now = System.currentTimeMillis()
        val previousEmotion = current.emotionState
        if (current.callActive) live.on(LiveEvent.GenerationStarted)

        val base: ChatSession = current.currentSessionId
            ?.let { id -> current.sessions.firstOrNull { it.id == id } }
            ?: ChatSession(model = model, createdAt = now)

        val titleSource = text.ifBlank { "📷 Imagen" }
        val withUser = base.copy(
            title = if (base.messages.isEmpty()) titleSource.take(TITLE_MAX_CHARS) else base.title,
            model = model,
            updatedAt = now,
            messages = base.messages + ChatMessage(role = ChatRole.USER, content = text, imagePath = imagePath)
        )
        commit(withUser, clearError = true)
        addStreamingPlaceholder(withUser.id)

        streamingJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            _state.value = _state.value.copy(
                emotion = Emotion.GENERATING,
                emotionState = EmotionEngine.generating(),
                toolEvents = emptyList()
            )
            val messageId = withUser.id
            try {
                val reply = container.chatRepository.streamReply(
                    withUser,
                    onDelta = { delta ->
                        accumulated.append(delta)
                        updateStreamingPlaceholder(withUser.id, accumulated.toString())
                        // Subtle haptic pulse per streamed fragment (throttled internally).
                        if (delta.isNotBlank()) container.haptics.pulse()
                    },
                    emotionContext = emotionContext(previousEmotion),
                    visionImagePath = visionImagePath ?: imagePath,
                    needsVision = needsVision
                )

                // v0.7: discrete action indicators + barely-there haptics.
                if (reply.toolEvents.isNotEmpty()) {
                    _state.value = _state.value.copy(toolEvents = reply.toolEvents)
                    if (reply.toolSucceeded) container.haptics.confirm()
                    else if (reply.toolCallCount > 0) container.haptics.error()
                }

                val emotionState = EmotionEngine.finalState(
                    userText = text,
                    replyText = reply.text,
                    toolSucceeded = reply.toolSucceeded.takeIf { reply.toolCallCount > 0 },
                    previous = previousEmotion
                )
                finalizeAssistant(
                    sessionId = withUser.id,
                    content = reply.text,
                    failed = false,
                    errorText = null,
                    latencyMs = reply.latencyMs,
                    totalMs = reply.totalMs,
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens,
                    emotionState = emotionState
                )
                if (_state.value.callActive) {
                    handleCallReply(messageId, reply.text, emotionState)
                } else {
                    maybeAutoRead(messageId, reply.text, emotionState)
                }
            } catch (e: CancellationException) {
                finalizeAssistant(withUser.id, accumulated.toString(), failed = false, errorText = null)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL, emotionState = null)
                throw e
            } catch (e: ProviderCallException) {
                val msg = friendlyProviderError(e)
                finalizeAssistant(withUser.id, msg, failed = true, errorText = msg)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL, emotionState = null)
                if (_state.value.callActive) handleCallReply(messageId, msg, null)
            } catch (e: Exception) {
                val msg = "Error inesperado: ${e.message ?: "desconocido"}"
                finalizeAssistant(withUser.id, msg, failed = true, errorText = msg)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL, emotionState = null)
                if (_state.value.callActive) handleCallReply(messageId, msg, null)
            }
        }
    }

    /** Brief simulated-mood context for the model (only when intensity is noticeable). */
    private fun emotionContext(state: EmotionState?): String? {
        val s = state ?: return null
        if (s.type == Emotion.NEUTRAL || s.intensity < EmotionEngine.INTENSITY_SUBTLE) return null
        return "Estado emocional actual (simulado, computacional; no es una emoción humana real): " +
            "${s.type.label} con intensidad ${s.intensity}. " +
            "Refleja consecuencias suaves y apropiadas, sin exagerar."
    }

    private fun maybeAutoRead(messageId: String, text: String, emotionState: EmotionState?) {
        val current = _state.value
        if (!current.autoRead || !current.ttsEnabled) return
        if (text.isBlank() || current.ttsStatus is TtsStatus.Error) return
        val emotion = emotionState?.type ?: current.emotion
        container.tts.speakWithEmotion(messageId, text, current.ttsRate, current.ttsPitch, emotion)
    }

    private fun appendLocalExchange(userText: String, assistantText: String) {
        val current = _state.value
        val now = System.currentTimeMillis()
        val base: ChatSession = current.currentSessionId
            ?.let { id -> current.sessions.firstOrNull { it.id == id } }
            ?: ChatSession(model = current.selectedModel, createdAt = now)

        val withMessages = base.copy(
            title = if (base.messages.isEmpty()) userText.take(TITLE_MAX_CHARS) else base.title,
            model = current.selectedModel,
            updatedAt = now,
            messages = base.messages +
                ChatMessage(role = ChatRole.USER, content = userText) +
                ChatMessage(role = ChatRole.ASSISTANT, content = assistantText)
        )
        commit(withMessages, clearError = true)
    }

    private fun commit(session: ChatSession, clearError: Boolean) {
        val current = _state.value
        val mutable = current.sessions.toMutableList()
        val index = mutable.indexOfFirst { it.id == session.id }
        if (index >= 0) mutable[index] = session else mutable.add(0, session)
        mutable.sortByDescending { it.updatedAt }
        _state.value = current.copy(
            sessions = mutable,
            currentSessionId = session.id,
            messages = session.messages,
            error = if (clearError) null else current.error,
            messagesSent = session.messages.count { it.role == ChatRole.USER }
        )
        viewModelScope.launch { container.chatStore.upsert(session) }
    }

    private fun addStreamingPlaceholder(sessionId: String) {
        val current = _state.value
        val placeholder = ChatMessage(id = PLACEHOLDER_ID, role = ChatRole.ASSISTANT, content = "", streamed = true)
        val sessions = current.sessions.map { session ->
            if (session.id == sessionId) session.copy(messages = session.messages + placeholder) else session
        }
        _state.value = current.copy(
            sessions = sessions,
            messages = sessions.first { it.id == sessionId }.messages,
            streaming = true,
            error = null
        )
    }

    private fun updateStreamingPlaceholder(sessionId: String, text: String) {
        val current = _state.value
        val sessions = current.sessions.map { session ->
            if (session.id == sessionId) {
                val messages = session.messages.toMutableList()
                val index = messages.indexOfLast { it.id == PLACEHOLDER_ID }
                if (index >= 0) messages[index] = messages[index].copy(content = text)
                session.copy(messages = messages)
            } else {
                session
            }
        }
        _state.value = current.copy(
            sessions = sessions,
            messages = if (current.currentSessionId == sessionId)
                sessions.first { it.id == sessionId }.messages else current.messages
        )
    }

    private suspend fun finalizeAssistant(
        sessionId: String,
        content: String,
        failed: Boolean,
        errorText: String?,
        latencyMs: Long? = null,
        totalMs: Long? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        emotionState: EmotionState? = null
    ) {
        val current = _state.value
        val finalMessage = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = content,
            streamed = false,
            failed = failed,
            latencyMs = latencyMs,
            totalMs = totalMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens
        )
        val sessions = current.sessions.map { session ->
            if (session.id == sessionId) {
                val messages = session.messages.toMutableList()
                val index = messages.indexOfLast { it.id == PLACEHOLDER_ID }
                if (index >= 0) messages[index] = finalMessage else messages.add(finalMessage)
                session.copy(updatedAt = System.currentTimeMillis(), messages = messages)
            } else {
                session
            }
        }
        sessions.sortedByDescending { it.updatedAt }
        val updatedSession = sessions.first { it.id == sessionId }
        val finalEmotionState = if (failed) null else emotionState
        _state.value = current.copy(
            sessions = sessions,
            messages = if (current.currentSessionId == sessionId) updatedSession.messages else current.messages,
            streaming = false,
            error = errorText,
            emotion = finalEmotionState?.type ?: if (failed) Emotion.NEUTRAL else EmotionClassifier.classify(content),
            emotionState = finalEmotionState
        )
        container.chatStore.upsert(updatedSession)
    }

    private fun ensureDefaultModel(models: List<AiModel>): List<AiModel> {
        if (models.any { it.id == BuildConfig.DEFAULT_MODEL }) return models
        return listOf(
            AiModel(id = BuildConfig.DEFAULT_MODEL, name = "OpenRouter Free (por defecto)")
        ) + models
    }

    private fun redactedError(e: Exception): String =
        e.message?.replace(container.settings.apiKey.value, "***") ?: "error desconocido"

    private companion object {
        const val PLACEHOLDER_ID = "__streaming_placeholder__"
        const val TITLE_MAX_CHARS = 45
        const val CALL_UTTERANCE_ID = "__call_utterance__"
        const val CALL_GREETING = "Hola, ¿qué necesitas?"
        const val ATTACH_DIR = "attachments"
        const val MAX_IMAGE_DIM = 1280
    }
}