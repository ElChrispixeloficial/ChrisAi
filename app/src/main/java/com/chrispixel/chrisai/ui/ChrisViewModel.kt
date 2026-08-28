package com.chrispixel.chrisai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.ChrisApplication
import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.emotion.EmotionClassifier
import com.chrispixel.chrisai.data.local.MemoryIntent
import com.chrispixel.chrisai.data.local.MemoryWriteResult
import com.chrispixel.chrisai.data.model.AiModel
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.Memory
import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.data.remote.OpenRouterException
import com.chrispixel.chrisai.data.speech.SttEvent
import com.chrispixel.chrisai.data.speech.TtsStatus
import com.chrispixel.chrisai.data.update.ReleaseInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val hapticsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val autoRead: Boolean = false,
    val ttsEnabled: Boolean = false,
    val ttsRate: Float = 1.0f,
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
    val messagesSent: Int = 0
)

class ChrisViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as ChrisApplication).container

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamingJob: Job? = null

    init {
        loadInitial()
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
                ttsVoice = container.settings.ttsVoice.value,
                sttLanguage = container.settings.sttLanguage.value,
                messagesSent = first?.messages?.count { it.role == ChatRole.USER } ?: 0
            )
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

    // ------------------------------------------------------------------ chat

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = _state.value
        if (current.streaming) return

        if (container.settings.apiKey.value.isBlank()) {
            _state.value = current.copy(error = "No hay una API key disponible en esta build.")
            return
        }

        container.haptics.confirm()

        viewModelScope.launch {
            val localReply = handleMemoryIntent(trimmed)
            if (localReply != null) {
                appendLocalExchange(trimmed, localReply)
                return@launch
            }
            startStreaming(trimmed)
        }
    }

    fun stopStreaming() {
        if (!_state.value.streaming) return
        streamingJob?.cancel()
        viewModelScope.launch { container.api.cancelActiveCall() }
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
            emotion = if (wasCurrent) Emotion.NEUTRAL else current.emotion
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
            } catch (e: OpenRouterException) {
                _state.value = _state.value.copy(modelsLoading = false, error = "No se pudieron cargar los modelos: ${e.message}")
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

    fun setTtsVoice(voiceName: String) {
        viewModelScope.launch { container.settings.setTtsVoice(voiceName) }
        _state.value = _state.value.copy(ttsVoice = voiceName)
        container.tts.setVoicePreference(voiceName)
    }

    fun ttsVoices(): List<String> = container.tts.listVoices()

    fun speakMessage(messageId: String, text: String) {
        if (!_state.value.ttsEnabled) return
        val current = _state.value
        val status = current.ttsStatus
        when {
            status is TtsStatus.Speaking && status.messageId == messageId -> {
                container.tts.pause()
            }
            status is TtsStatus.Paused && status.messageId == messageId -> {
                container.tts.resume(current.ttsRate)
            }
            else -> {
                container.tts.speak(messageId, text, current.ttsRate)
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

    private fun startStreaming(text: String) {
        val current = _state.value
        val model = current.selectedModel
        val now = System.currentTimeMillis()

        val base: ChatSession = current.currentSessionId
            ?.let { id -> current.sessions.firstOrNull { it.id == id } }
            ?: ChatSession(model = model, createdAt = now)

        val withUser = base.copy(
            title = if (base.messages.isEmpty()) text.take(TITLE_MAX_CHARS) else base.title,
            model = model,
            updatedAt = now,
            messages = base.messages + ChatMessage(role = ChatRole.USER, content = text)
        )
        commit(withUser, clearError = true)
        addStreamingPlaceholder(withUser.id)

        streamingJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            _state.value = _state.value.copy(emotion = Emotion.GENERATING)
            val messageId = withUser.id
            try {
                val reply = container.chatRepository.streamReply(withUser) { delta ->
                    accumulated.append(delta)
                    updateStreamingPlaceholder(withUser.id, accumulated.toString())
                    // Subtle haptic pulse per streamed fragment (throttled internally).
                    if (delta.isNotBlank()) container.haptics.pulse()
                }
                finalizeAssistant(
                    sessionId = withUser.id,
                    content = reply.text,
                    failed = false,
                    errorText = null,
                    latencyMs = reply.latencyMs,
                    totalMs = reply.totalMs,
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens
                )
                maybeAutoRead(messageId, reply.text)
            } catch (e: CancellationException) {
                finalizeAssistant(withUser.id, accumulated.toString(), failed = false, errorText = null)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL)
                throw e
            } catch (e: OpenRouterException) {
                val msg = e.message ?: "Error desconocido"
                finalizeAssistant(withUser.id, msg, failed = true, errorText = msg)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL)
            } catch (e: Exception) {
                val msg = "Error inesperado: ${e.message ?: "desconocido"}"
                finalizeAssistant(withUser.id, msg, failed = true, errorText = msg)
                _state.value = _state.value.copy(emotion = Emotion.NEUTRAL)
            }
        }
    }

    private fun maybeAutoRead(messageId: String, text: String) {
        val current = _state.value
        if (!current.autoRead || !current.ttsEnabled) return
        if (text.isBlank() || current.ttsStatus is TtsStatus.Error) return
        container.tts.speak(messageId, text, current.ttsRate)
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
        completionTokens: Int? = null
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
        _state.value = current.copy(
            sessions = sessions,
            messages = if (current.currentSessionId == sessionId) updatedSession.messages else current.messages,
            streaming = false,
            error = errorText,
            emotion = if (failed) Emotion.NEUTRAL else EmotionClassifier.classify(content)
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
    }
}