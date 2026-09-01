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
import com.chrispixel.chrisai.data.drive.GoogleAccountPicker
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
import com.chrispixel.chrisai.data.model.SessionKind
import com.chrispixel.chrisai.data.permissions.CapabilityId
import com.chrispixel.chrisai.data.permissions.CapabilityStatus
import com.chrispixel.chrisai.data.permissions.PermissionCenter
import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.data.provider.ProviderCallException
import com.chrispixel.chrisai.data.provider.ProviderErrorType
import com.chrispixel.chrisai.data.speech.BargeInVad
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
    val sessionKind: SessionKind = SessionKind.DEFAULT,
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
    // v1.1: real barge-in (mic VAD) while the assistant speaks in a call.
    val bargeInEnabled: Boolean = true,
    val imagesEnabled: Boolean = true,
    // v0.8.1: pending image attachment (absolute path to a local JPEG).
    val pendingImage: String? = null,
    val imageError: String? = null,
    // v1.1: image generation progress/result for the current request.
    val generatingImage: Boolean = false,
    val generatedImagePath: String? = null,
    val imageGenerationError: String? = null,
    // v1.1: independent media player state (audio file playback).
    val mediaState: com.chrispixel.chrisai.data.media.MediaPlayerController.PlaybackState =
        com.chrispixel.chrisai.data.media.MediaPlayerController.PlaybackState.Idle,
    val generatingAudio: Boolean = false,
    val audioError: String? = null,
    // v0.9: videollamada (controlled visual capture), study mode, permissions.
    val videoCallActive: Boolean = false,
    // v1.0: standalone video-call screen (camera + ChrisAI avatar).
    val videoCallScreenOpen: Boolean = false,
    val cameraActive: Boolean = false,
    // v1.1: which lens the video-call camera is using ("back"/"front").
    val cameraFacing: String = "back",
    val screenSharing: Boolean = false,
    val videoError: String? = null,
    val studyModeEnabled: Boolean = false,
    val captureIntervalSec: Int = 5,
    val lastVisionLabel: String? = null,
    val hasVisionFrame: Boolean = false,
    val permissions: List<CapabilityStatus> = emptyList(),
    val driveConnected: Boolean = false,
    // v1.0 onboarding + Google Drive backup.
    val initialized: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val googleAccounts: List<String> = emptyList(),
    val driveSyncEnabled: Boolean = false,
    val driveAccountEmail: String = "",
    val driveSyncing: Boolean = false,
    val driveLastSync: String? = null,
    val driveSyncMessage: String? = null,
    val providerFallbackAvailable: Boolean = false,
    // v1.1: Developer Mode local agent (SAF folder + files).
    val devAgentUri: String = "",
    val devAgentPath: String? = null,
    val devAgentFiles: List<com.chrispixel.chrisai.data.devagent.DevAgentFile> = emptyList(),
    val devAgentLoading: Boolean = false,
    val devAgentMessage: String? = null,
    val devAttachError: String? = null
)

class ChrisViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as ChrisApplication).container

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamingJob: Job? = null
    // v1.1: in-flight image generation job (independent of the chat stream).
    private var genImageJob: Job? = null

    // v0.8.1: deterministic call/session state machine (Live infra, now wired).
    private val live = LiveStateMachine()
    private var callWaitJob: Job? = null
    private var callPendingText: String? = null

    // v1.1: real barge-in — mic VAD running while the TTS speaks in call mode.
    private val bargeVad = BargeInVad(getApplication())

    // v0.9: cross-action memory + vision capture bookkeeping.
    private val actionContext = ActionContextStore()
    private var latestVisionPath: String? = null

    // v1.1: reflect Google account list changes (added/removed) on the device.
    private val accountReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            refreshGoogleAccounts()
        }
    }

    init {
        loadInitial()
        observeVisionSources()
        observeVisionProblems()
        refreshPermissions()
        observeMedia()
        initDeveloperAgent()
        try {
            val filter = android.content.IntentFilter(android.accounts.AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                getApplication<Application>().registerReceiver(
                    accountReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                getApplication<Application>().registerReceiver(accountReceiver, filter)
            }
        } catch (_: Throwable) {
            // receiver registration failure must never break launch
        }
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(accountReceiver)
        } catch (_: Throwable) {
            // ignore
        }
        super.onCleared()
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
                bargeInEnabled = container.settings.bargeInEnabled.value,
                imagesEnabled = container.settings.imagesEnabled.value,
                studyModeEnabled = container.settings.studyModeEnabled.value,
                captureIntervalSec = container.settings.captureIntervalSec.value,
                providerFallbackAvailable = container.providerEngine.fallbackVisionCapable,
                messagesSent = first?.messages?.count { it.role == ChatRole.USER } ?: 0,
                initialized = true,
                onboardingCompleted = container.settings.onboardingCompleted.value,
                driveSyncEnabled = container.settings.driveSyncEnabled.value,
                driveAccountEmail = container.settings.driveAccountEmail.value
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
                        // v1.1: video capture is independent of the voice call.
                        videoCallActive = active || it.screenSharing
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
                        videoCallActive = it.cameraActive || active
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

    /** Opens the standalone video-call screen; starts the voice session on entry. */
    fun openVideoCall() {
        val current = _state.value
        if (!current.callActive && current.callModeEnabled) {
            startCall()
        }
        _state.update { it.copy(videoCallScreenOpen = true) }
    }

    /** Closes the standalone screen. [hangUp] ends the call (and stops capture). */
    fun closeVideoCall(hangUp: Boolean) {
        if (hangUp && _state.value.callActive) endCall()
        _state.update { it.copy(videoCallScreenOpen = false) }
    }

    fun toggleCamera() {
        if (_state.value.cameraActive) stopCamera() else startCamera()
    }

    /** Hot-swaps the video-call lens (front ↔ back) without dropping the capture. */
    fun switchCamera() {
        val next = if (_state.value.cameraFacing == "front") "back" else "front"
        val facing = if (next == "front") com.chrispixel.chrisai.data.vision.CameraCaptureSession.FACE_FRONT
        else com.chrispixel.chrisai.data.vision.CameraCaptureSession.FACE_BACK
        if (_state.value.cameraActive) {
            container.camera.switchCamera(facing)
        } else {
            container.camera.setLensFacing(facing)
        }
        _state.update { it.copy(cameraFacing = next) }
        container.haptics.confirm()
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
        container.camera.setLensFacing(
            if (_state.value.cameraFacing == "front")
                com.chrispixel.chrisai.data.vision.CameraCaptureSession.FACE_FRONT
            else com.chrispixel.chrisai.data.vision.CameraCaptureSession.FACE_BACK
        )
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

    // ------------------------------------------------------- v1.1 Developer Mode (SAF local agent)

    /** Reads the persisted folder at init into the state (folder path + files). */
    fun initDeveloperAgent() = viewModelScope.launch(Dispatchers.IO) {
        val uri = container.settings.devAgentUri.value
        if (uri.isBlank()) return@launch
        _state.update { it.copy(devAgentUri = uri, devAgentLoading = true, devAgentMessage = null) }
        refreshDeveloperAgentFilesLocked(uri)
    }

    /** Called when the user picks a folder via SAF (OpenDocumentTree). */
    fun setDeveloperAgentFolder(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        container.devAgent.persistFolderAccess(uri)
        container.settings.setDevAgentUri(uri.toString())
        _state.update { it.copy(devAgentUri = uri.toString(), devAgentLoading = true, devAgentMessage = null) }
        refreshDeveloperAgentFilesLocked(uri.toString())
        container.haptics.confirm()
    }

    fun refreshDeveloperAgentFiles() {
        val uri = _state.value.devAgentUri
        if (uri.isBlank()) return
        _state.update { it.copy(devAgentLoading = true, devAgentMessage = null) }
        viewModelScope.launch(Dispatchers.IO) { refreshDeveloperAgentFilesLocked(uri) }
    }

    private suspend fun refreshDeveloperAgentFilesLocked(uriString: String) {
        val uri = Uri.parse(uriString)
        val agent = container.devAgent
        val path = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }
        val files = agent.listFiles(uri)
        _state.update {
            it.copy(
                devAgentPath = path,
                devAgentFiles = files,
                devAgentLoading = false,
                devAgentMessage = if (files.isEmpty()) "La carpeta de trabajo está vacía." else null
            )
        }
    }

    fun clearDeveloperAgentFolder() {
        val uri = _state.value.devAgentUri
        if (uri.isNotBlank()) {
            try { container.devAgent.releaseFolderAccess(Uri.parse(uri)) } catch (_: Exception) {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            container.settings.setDevAgentUri("")
        }
        _state.update {
            it.copy(devAgentUri = "", devAgentPath = null, devAgentFiles = emptyList(),
                devAgentLoading = false, devAgentMessage = null, devAttachError = null)
        }
    }

    fun dismissDevAttachError() {
        _state.update { it.copy(devAttachError = null) }
    }

    /**
     * Attaches a readable file from the local agent folder into the chat as user
     * context, then streams ChrisAI's reply.
     *
     * Returns true when the attach started a reply (and the caller can pop back).
     */
    fun attachDevFile(fileName: String, uriString: String): Boolean {
        val current = _state.value
        if (current.streaming) {
            _state.update { it.copy(devAttachError = "Espera a que termine la respuesta actual.") }
            return false
        }
        val file = current.devAgentFiles.firstOrNull { it.uri == uriString }
        if (file == null || !container.devAgent.isReadableText(file)) {
            _state.update {
                it.copy(devAttachError = "Este archivo no se puede adjuntar como texto.")
            }
            return false
        }
        viewModelScope.launch(Dispatchers.IO) {
            val content = container.devAgent.readTextFile(Uri.parse(uriString))
            withContext(Dispatchers.Main) {
                if (content.isNullOrBlank()) {
                    _state.update {
                        it.copy(devAttachError = "No se pudo leer el contenido de «$fileName».")
                    }
                    return@withContext
                }
                _state.update { it.copy(devAttachError = null) }
                postDevFileMessage(fileName, content)
            }
        }
        return true
    }

    /** Sends a user message with the file content as context and streams the reply. */
    private fun postDevFileMessage(fileName: String, content: String) {
        if (container.settings.apiKey.value.isBlank()) {
            _state.update { it.copy(devAttachError = "No hay una API key disponible en esta build.") }
            return
        }
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.streaming) return

        val header = "📎 He adjuntado el archivo local «$fileName» desde mi carpeta de trabajo. " +
            "Usa su contenido como contexto:"
        val text = "$header\n\n$trimmed"
        container.haptics.confirm()
        startStreaming(text)
    }

    // ------------------------------------------------------- v1.1 image generate

    /** Starts generating an image from [prompt]; never blocks the chat. */
    fun generateImage(prompt: String) {
        val current = _state.value
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        if (current.generatingImage) return

        _state.update {
            it.copy(generatingImage = true, imageGenerationError = null, generatedImagePath = null)
        }
        genImageJob = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val apiKey = container.settings.apiKey.value
                val result = withContext(Dispatchers.IO) {
                    container.providerEngine.generateImage(
                        model = GENERATED_IMAGE_MODEL,
                        prompt = trimmed,
                        primaryKey = apiKey
                    )
                }
                if (result == null) {
                    _state.update {
                        it.copy(
                            generatingImage = false,
                            imageGenerationError = "No se pudo generar la imagen (respuesta vacía)."
                        )
                    }
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) { saveGeneratedImage(result) }
                if (saved == null) {
                    _state.update {
                        it.copy(
                            generatingImage = false,
                            imageGenerationError = "No se pudo guardar la imagen generada."
                        )
                    }
                    return@launch
                }
                addGeneratedMessage(trimmed, saved)
                container.haptics.confirm()
                _state.update {
                    it.copy(generatingImage = false, generatedImagePath = saved, imageGenerationError = null)
                }
            } catch (e: com.chrispixel.chrisai.data.provider.ProviderCallException) {
                _state.update {
                    it.copy(
                        generatingImage = false,
                        imageGenerationError = e.message ?: "Error al generar la imagen."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    it.copy(
                        generatingImage = false,
                        imageGenerationError = "Error inesperado al generar la imagen."
                    )
                }
            }
        }
    }
    fun cancelImageGeneration() {
        genImageJob?.cancel()
        _state.update { it.copy(generatingImage = false, imageGenerationError = null) }
    }

    fun dismissImageGenerationError() {
        _state.update { it.copy(imageGenerationError = null) }
    }

    /** Saves a generated image to app storage and appends it as an assistant message. */
    private suspend fun addGeneratedMessage(prompt: String, path: String) {
        val current = _state.value
        val message = ChatMessage(role = ChatRole.ASSISTANT, content = prompt, generatedImagePath = path)
        val updated = current.copy(
            messages = current.messages + message,
            messagesSent = current.messagesSent
        )
        _state.value = updated
        current.currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                val session = container.chatStore.find(sessionId)
                if (session != null) {
                    container.chatStore.upsert(
                        session.copy(
                            messages = session.messages + message,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    /** Writes the generated bytes (PNG) to a file in app-private storage. */
    private fun saveGeneratedImage(bytes: ByteArray): String? {
        return try {
            val dir = File(getApplication<Application>().filesDir, ATTACH_DIR).apply { mkdirs() }
            val file = File(dir, "gen_${UUID.randomUUID()}.png")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Copies a generated image file into the system clipboard's content URI for sharing. */
    fun shareGeneratedImage(path: String) {
        if (path.isBlank()) return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                getApplication(), getApplication<Application>().packageName + ".fileprovider", File(path)
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            getApplication<Application>().startActivity(
                android.content.Intent.createChooser(send, "Compartir imagen de ChrisAI").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
            // ignore: no share target
        }
    }

    /** Saves a generated image to the system gallery. */
    fun saveGeneratedImageToGallery(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = try {
                val file = File(path)
                if (!file.exists()) return@launch
                val bytes = file.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                val resolver = getApplication<Application>().contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "chrisai_${System.currentTimeMillis()}.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@launch
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } catch (_: Throwable) {
                null
            }
            if (outcome != null) {
                _state.update {
                    it.copy(
                        imageGenerationError = null,
                        driveSyncMessage = null
                    )
                }
                container.haptics.confirm()
            }
        }
    }

    // ------------------------------------------------------- v1.1 media player

    private fun observeMedia() {
        viewModelScope.launch {
            container.mediaPlayer.state.collect { playback ->
                _state.update { it.copy(mediaState = playback) }
            }
        }
    }

    fun toggleAudioPlayback(path: String) {
        if (path.isBlank()) return
        val current = _state.value.mediaState
        val playingHere = current is com.chrispixel.chrisai.data.media.MediaPlayerController.PlaybackState.Playing &&
            current.path == path
        val pausedHere = current is com.chrispixel.chrisai.data.media.MediaPlayerController.PlaybackState.Paused &&
            current.path == path
        when {
            playingHere -> container.mediaPlayer.pause()
            pausedHere -> container.mediaPlayer.resume()
            else -> container.mediaPlayer.play(java.io.File(path))
        }
    }

    fun seekAudio(positionMs: Int) {
        container.mediaPlayer.seekTo(positionMs)
    }

    fun stopAudio() {
        container.mediaPlayer.stop()
    }

    fun dismissAudioError() {
        _state.update { it.copy(audioError = null) }
    }

    /** v1.1: synthesizes the given text to an audio file and attaches it in a message. */
    fun generateAudio(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.generatingAudio) return
        if (!container.tts.available()) {
            _state.update { it.copy(audioError = "La síntesis de audio no está disponible todavía.") }
            return
        }
        _state.update { it.copy(generatingAudio = true, audioError = null) }
        viewModelScope.launch {
            val current = _state.value
            val outDir = java.io.File(getApplication<Application>().filesDir, ATTACH_DIR)
                .apply { mkdirs() }
            val outFile = java.io.File(outDir, "audio_${java.util.UUID.randomUUID()}.wav")
            val ok = withContext(Dispatchers.IO) {
                container.tts.synthesizeToFile(
                    rawText = trimmed,
                    outputPath = outFile.absolutePath,
                    rate = current.ttsRate,
                    pitch = current.ttsPitch
                )
            }
            if (ok && outFile.exists()) {
                addAudioMessage(trimmed, outFile.absolutePath)
                container.haptics.confirm()
            } else {
                _state.update {
                    it.copy(
                        generatingAudio = false,
                        audioError = "No se pudo generar el audio. Prueba de nuevo."
                    )
                }
            }
        }
    }

    private suspend fun addAudioMessage(prompt: String, path: String) {
        val current = _state.value
        val message = ChatMessage(role = ChatRole.ASSISTANT, content = prompt, audioPath = path)
        _state.update {
            it.copy(
                messages = it.messages + message,
                generatingAudio = false,
                audioError = null
            )
        }
        current.currentSessionId?.let { sessionId ->
            viewModelScope.launch {
                val session = container.chatStore.find(sessionId)
                if (session != null) {
                    container.chatStore.upsert(
                        session.copy(
                            messages = session.messages + message,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    fun shareAudio(path: String) {
        if (path.isBlank()) return
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                getApplication(), getApplication<Application>().packageName + ".fileprovider", java.io.File(path)
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            getApplication<Application>().startActivity(
                android.content.Intent.createChooser(send, "Compartir audio de ChrisAI").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {
            // ignore: no share target
        }
    }

    fun saveAudioToDevice(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(path)
                if (!file.exists() || file.length() == 0L) return@launch
                val resolver = getApplication<Application>().contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "chrisai_${System.currentTimeMillis()}.wav")
                    put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                    put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@launch
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                container.haptics.confirm()
            } catch (_: Throwable) {
                // ignore
            }
        }
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
                sessionKind = session.kind,
                error = null,
                emotion = Emotion.NEUTRAL,
                emotionState = null,
                messagesSent = session.messages.count { it.role == ChatRole.USER }
            )
        }
    }

    fun startNewChat(kind: SessionKind = _state.value.sessionKind) {
        streamingJob?.cancel()
        container.tts.stop()
        val current = _state.value
        _state.value = current.copy(
            currentSessionId = null,
            messages = emptyList(),
            sessionKind = kind,
            error = null,
            emotion = Emotion.NEUTRAL,
            emotionState = null,
            toolEvents = emptyList(),
            messagesSent = 0,
            listening = false,
            sttPartial = null
        )
    }

    /** Switches the working context; independent conversations per kind. */
    fun selectSessionKind(kind: SessionKind) {
        val current = _state.value
        if (current.currentSessionId == null) {
            _state.value = current.copy(sessionKind = kind)
            return
        }
        val active = current.sessions.firstOrNull { it.id == current.currentSessionId }
        if (active == null || active.kind == kind) {
            _state.value = current.copy(sessionKind = kind)
            return
        }
        val existing = current.sessions.firstOrNull { it.kind == kind }
        if (existing != null) openSession(existing.id) else startNewChat(kind)
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

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setBargeInEnabled(enabled) }
        _state.value = _state.value.copy(bargeInEnabled = enabled)
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

    // --------------------------------------------------- v1.1 per-message actions

    /** Copies the assistant answer (clean text) to the system clipboard. */
    fun copyMessage(text: String) {
        if (text.isBlank()) return
        try {
            val manager = getApplication<Application>().getSystemService(
                android.content.ClipboardManager::class.java
            )
            manager?.setPrimaryClip(
                android.content.ClipData.newPlainText("ChrisAI", text.trim())
            )
            container.haptics.confirm()
        } catch (_: Throwable) {
            // clipboard unavailable: nothing to do
        }
    }

    /** Shares the assistant answer through the Android sharesheet. */
    fun shareMessage(text: String) {
        if (text.isBlank()) return
        try {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text.trim())
            }
            val chooser = android.content.Intent.createChooser(send, "Compartir respuesta de Chris AI")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(chooser)
            container.haptics.confirm()
        } catch (_: Throwable) {
            // no app can handle sharing: ignore
        }
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
            startBargeInMonitoring()
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
                    startBargeInMonitoring()
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
        stopBargeInMonitoring()
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
            stopBargeInMonitoring()
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
            startBargeInMonitoring()
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
        stopBargeInMonitoring()
        callWaitJob?.cancel()
        container.tts.stop()
        live.on(LiveEvent.BargeIn)
        live.on(LiveEvent.Interrupt)
        live.on(LiveEvent.ListenAgain)
        syncLiveStage()
        if (live.state.stage == LiveStage.LISTENING) armCallListening()
    }

    // ------------------------------------------------------- v1.1 real barge-in

    /** Starts the mic VAD so the user can interrupt the assistant by speaking. */
    private fun startBargeInMonitoring() {
        val enabled = _state.value.bargeInEnabled &&
            _state.value.callActive &&
            container.tts.status.value is TtsStatus.Speaking
        if (!enabled) return
        if (!bargeVad.start(::onBargeInDetected) && _state.value.callActive &&
            _state.value.bargeInEnabled
        ) {
            // Mic unavailable or permission missing: rely on the manual mic tap.
        }
    }

    private fun onBargeInDetected() {
        val status = container.tts.status.value
        val speaking = status is TtsStatus.Speaking || status is TtsStatus.Paused
        if (!_state.value.callActive || !speaking) return
        interruptCall()
    }

    private fun stopBargeInMonitoring() {
        bargeVad.stop()
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
            ?: ChatSession(model = model, createdAt = now, kind = current.sessionKind)

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
            ?: ChatSession(model = current.selectedModel, createdAt = now, kind = current.sessionKind)

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

    // ---------------------------------------------------------- v1.0 Drive

    /** Reads the Google accounts visible on the device (GET_ACCOUNTS granted). */
    fun refreshGoogleAccounts() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) { GoogleAccountPicker.accounts(ctx) }
            _state.update { it.copy(googleAccounts = list) }
        }
    }

    /** Skips the cloud backup; the app works fully offline. */
    fun skipOnboarding() {
        viewModelScope.launch(Dispatchers.IO) {
            container.settings.setOnboardingCompleted(true)
            _state.update { it.copy(onboardingCompleted = true) }
        }
    }

    fun connectDrive(email: String) = runSync(email = email, completeOnboarding = true)

    fun syncDriveNow() = runSync(email = _state.value.driveAccountEmail, completeOnboarding = false)

    private fun runSync(email: String, completeOnboarding: Boolean) {
        if (_state.value.driveSyncing) return
        val byEmail = email.isNotBlank()
        viewModelScope.launch {
            _state.update { it.copy(driveSyncing = true, driveSyncMessage = null) }
            val ctx = getApplication<Application>()
            val outcome = try {
                withContext(Dispatchers.IO) out@{
                    if (byEmail) {
                        val token = GoogleAccountPicker.requestToken(ctx, email)
                        if (token == null) {
                            return@out "No se pudo autorizar la cuenta en Google."
                        }
                        val result = container.syncManager.sync(token)
                        if (result.errors.isNotEmpty()) {
                            GoogleAccountPicker.invalidateToken(ctx, token)
                            return@out "Sincronización con errores: ${result.errors.first()}"
                        }
                        if (completeOnboarding) {
                            container.settings.setDriveAccountEmail(email)
                            container.settings.setDriveSyncEnabled(true)
                        }
                        summarize(result)
                    } else {
                        // A sincronización manual requiere cuenta y token previo.
                        "No hay cuenta conectada para sincronizar."
                    }
                }
            } catch (_: Throwable) {
                "No se pudo sincronizar con Google. Puedes seguir usando ChrisAI y reintentar desde Ajustes."
            }
            if (completeOnboarding) {
                container.settings.setOnboardingCompleted(true)
            }
            _state.update {
                it.copy(
                    driveSyncing = false,
                    driveSyncMessage = outcome,
                    driveLastSync = if (outcome.startsWith("Sincronización")) {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    } else {
                        it.driveLastSync
                    },
                    onboardingCompleted = if (completeOnboarding) true else it.onboardingCompleted,
                    driveSyncEnabled = if (completeOnboarding && byEmail) true else it.driveSyncEnabled,
                    driveAccountEmail = if (completeOnboarding && byEmail) email else it.driveAccountEmail
                )
            }
        }
    }

    /** Disconnects the account and revokes the stored OAuth token. */
    fun disconnectDrive() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val email = _state.value.driveAccountEmail
            if (email.isNotBlank()) {
                GoogleAccountPicker.requestToken(ctx, email)?.let {
                    GoogleAccountPicker.invalidateToken(ctx, it)
                }
            }
            container.settings.setDriveAccountEmail("")
            container.settings.setDriveSyncEnabled(false)
            _state.update {
                it.copy(
                    driveSyncEnabled = false,
                    driveAccountEmail = "",
                    driveLastSync = null,
                    driveSyncMessage = null
                )
            }
        }
    }

    private fun summarize(result: com.chrispixel.chrisai.data.drive.SyncResult): String {
        val parts = mutableListOf<String>()
        if (result.uploaded.isNotEmpty()) parts.add("subidas: ${result.uploaded.size}")
        if (result.downloaded.isNotEmpty()) parts.add("descargas: ${result.downloaded.size}")
        if (result.deletedRemotes.isNotEmpty()) parts.add("borrados: ${result.deletedRemotes.size}")
        if (result.conflictsKeptLocal.isNotEmpty()) parts.add("conflictos resueltos: ${result.conflictsKeptLocal.size}")
        return if (parts.isEmpty()) "Todo al día." else "Sincronización completada (${parts.joinToString(", ")})."
    }

    private companion object {
        const val PLACEHOLDER_ID = "__streaming_placeholder__"
        const val TITLE_MAX_CHARS = 45
        const val CALL_UTTERANCE_ID = "__call_utterance__"
        const val CALL_GREETING = "Hola, ¿qué necesitas?"
        const val ATTACH_DIR = "attachments"
        const val MAX_IMAGE_DIM = 1280
        // v1.1: default OpenRouter image model (DALL·E-class). Override at runtime.
        const val GENERATED_IMAGE_MODEL = "openai/dall-e-3"
    }
}