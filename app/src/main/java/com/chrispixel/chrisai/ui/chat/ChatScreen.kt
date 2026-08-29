package com.chrispixel.chrisai.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.speech.TtsStatus
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.android.ToolEvent
import com.chrispixel.chrisai.nativebridge.NativeBridge
import com.chrispixel.chrisai.ui.ChatUiState
import com.chrispixel.chrisai.ui.ChrisViewModel
import com.chrispixel.chrisai.ui.components.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(vm: ChrisViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val micPermissionGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startListening()
    }

    // STT partial text flows into the composer while listening.
    LaunchedEffect(state.sttPartial) {
        state.sttPartial?.let { input = it }
    }

    // Clean up speech on leave.
    DisposableEffect(Unit) {
        onDispose { vm.stopListening() }
    }

    LaunchedEffect(state.messages.size, state.streaming) {
        val count = state.messages.size
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                model = state.selectedModel,
                messagesSent = state.messagesSent,
                onNewChat = { vm.startNewChat() },
                onOpenSettings = onOpenSettings
            )
        },
        bottomBar = {
            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                streaming = state.streaming,
                listening = state.listening,
                onSend = {
                    if (input.isNotBlank()) {
                        vm.sendMessage(input)
                        input = ""
                    }
                },
                onStop = { vm.stopStreaming() },
                onMicClick = {
                    if (micPermissionGranted) {
                        if (state.listening) vm.stopListening() else vm.startListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.error != null) {
                ErrorBanner(message = state.error!!, onDismiss = { vm.dismissError() })
            }
            if (state.listening) {
                ListeningBanner()
            }
            if (state.listeningError != null) {
                ErrorBanner(message = state.listeningError!!, onDismiss = { vm.dismissListeningError() })
            }
            if (state.toolEvents.isNotEmpty()) {
                ToolIndicators(events = state.toolEvents)
            }
            if (state.update.checking) {
                UpdatingBanner()
            } else {
                val release = state.update.available
                if (release != null && !state.update.downloading) {
                    UpdateAvailableBanner(
                        version = release.versionLabel,
                        onOpenSettings = onOpenSettings
                    )
                }
            }
            if (state.messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    model = state.selectedModel,
                    hasKey = state.apiKeySet
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    EmotionBackdrop(
                        emotion = state.emotion,
                        intensity = state.emotionState?.intensity ?: 0f,
                        animationsEnabled = state.animationsEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.6f)
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                ttsEnabled = state.ttsEnabled,
                                ttsStatus = state.ttsStatus,
                                onSpeak = { vm.speakMessage(message.id, message.content) },
                                onStopSpeak = { vm.stopSpeech() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * v0.7 single primary visual effect per emotion.
 *
 * The tint scales with [intensity] (buckets 0..1) so it is clearly visible in
 * a screenshot but stays calm. GENERATING gets priority: a violet gradient plus
 * a slow breathing glow and a soft vignette, transitioning smoothly to the
 * final emotion once the reply finishes.
 */
@Composable
private fun EmotionBackdrop(
    emotion: Emotion,
    intensity: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = emotion.accent
    val animated by animateColorAsState(
        targetValue = if (animationsEnabled) accent else Color.Transparent,
        animationSpec = tween(durationMillis = 1400),
        label = "emotionBackdrop"
    )
    val transition = rememberInfiniteTransition(label = "emotionBreath")
    val breath by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "breath"
    )

    val isGenerating = emotion == Emotion.GENERATING
    val effectiveIntensity = intensity.coerceIn(0f, 1f)
    // 0.2 → ~13% tint, 0.5 → ~19%, 1.0 → ~30%: visible but never a flash.
    val baseAlpha = if (isGenerating) breath else 0.06f + effectiveIntensity * 0.24f
    val alpha = if (!animationsEnabled || effectiveIntensity == 0f && !isGenerating) {
        0f
    } else {
        baseAlpha
    }
    if (alpha <= 0f) return

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(animated.copy(alpha = alpha), Color.Transparent)
            )
        )
    ) {
        if (isGenerating) {
            // Soft vignette: edges glow, center stays readable. Not a flash.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                animated.copy(alpha = breath * 0.5f)
                            ),
                            radius = 1100f
                        )
                    )
            )
        }
    }
}

/** Discrete per-action indicators (⚙️ running, ✓ done, ⚠️ not found...). */
@Composable
private fun ToolIndicators(events: List<ToolEvent>) {
    val finalEvents = events.filter { it.status != ToolResultStatus.RUNNING }
    val lastMessage = finalEvents.lastOrNull()?.message
    val succeeded = finalEvents.count { it.status == ToolResultStatus.SUCCESS }
    if (lastMessage == null && succeeded == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        lastMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        if (succeeded > 1) {
            Text(
                "⚙️ $succeeded acciones ejecutadas",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(model: String, messagesSent: Int, onNewChat: () -> Unit, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("ChrisAI", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$model · $messagesSent mensajes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "Nueva conversación")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun EmptyState(modifier: Modifier, model: String, hasKey: Boolean) {
    var aurora by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        aurora = withContext(Dispatchers.Default) {
            val width = 240
            val height = 420
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            NativeBridge.fillAurora(bitmap, width, height, System.currentTimeMillis())
            bitmap.asImageBitmap()
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        aurora?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f),
                contentScale = ContentScale.Crop
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("🤖", fontSize = MaterialTheme.typography.displayLarge.fontSize)
            Spacer(Modifier.height(12.dp))
            Text("ChrisAI", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Modelo activo: $model",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            if (!hasKey) {
                Text(
                    "Configure su API key en Ajustes para empezar a chatear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Descartar")
            }
        }
    }
}

@Composable
private fun ListeningBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎙️", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text("Escuchando…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UpdateAvailableBanner(version: String, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSettings() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Nueva versión v$version disponible. Toca para verla en Ajustes.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun UpdatingBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Comprobando actualizaciones…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    ttsEnabled: Boolean,
    ttsStatus: TtsStatus,
    onSpeak: () -> Unit,
    onStopSpeak: () -> Unit
) {
    val isUser = message.role == ChatRole.USER
    val shape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (message.failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser || message.failed) {
                if (message.failed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = shape
        ) {
            when {
                message.streamed && message.content.isEmpty() -> {
                    TypingIndicator(Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
                message.failed -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                isUser -> {
                    SelectionContainer {
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
                else -> {
                    Column {
                        MarkdownText(
                            text = message.content,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!message.streamed && (message.totalMs != null || message.promptTokens != null || message.completionTokens != null)) {
                            Text(
                                metricsLabel(message),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (!message.streamed && ttsEnabled && message.content.isNotBlank()) {
                            TtsButtonRow(
                                messageId = message.id,
                                status = ttsStatus,
                                onSpeak = onSpeak,
                                onStop = onStopSpeak
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsButtonRow(
    messageId: String,
    status: TtsStatus,
    onSpeak: () -> Unit,
    onStop: () -> Unit
) {
    val speaking = status is TtsStatus.Speaking && status.messageId == messageId
    val paused = status is TtsStatus.Paused && status.messageId == messageId
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSpeak) {
            Text(if (speaking) "⏸ Pausar" else if (paused) "▶ Continuar" else "🔊 Leer")
        }
        if (speaking || paused) {
            TextButton(onClick = onStop) { Text("■ Detener") }
        }
    }
}

private fun metricsLabel(message: ChatMessage): String {
    val parts = buildList {
        message.latencyMs?.let { add("latencia ${it}ms") }
        message.totalMs?.let { add("$it ms") }
        if (message.promptTokens != null || message.completionTokens != null) {
            val prompt = message.promptTokens ?: 0
            val completion = message.completionTokens ?: 0
            add("${prompt}→${completion} tok")
            add("total ${prompt + completion}")
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 400, delayMillis = index * 120),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(8.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.Gray, CircleShape)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    streaming: Boolean,
    listening: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMicClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = onMicClick) {
                    Text(
                        "🎙️",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.alpha(if (listening) 1f else 0.7f)
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (listening) "Habla…" else "Escribe un mensaje…") },
                    maxLines = 6,
                    enabled = !streaming
                )
                Spacer(Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    if (streaming) {
                        Icon(Icons.Filled.Close, contentDescription = "Detener")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                    }
                }
            }
        }
    }
}