package com.chrispixel.chrisai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import com.chrispixel.chrisai.BuildConfig
import com.chrispixel.chrisai.data.personality.PersonalityConfig
import com.chrispixel.chrisai.data.personality.PersonalityPreset
import com.chrispixel.chrisai.data.speech.TtsVoiceInfo
import com.chrispixel.chrisai.data.update.ReleaseInfo
import com.chrispixel.chrisai.ui.ChrisViewModel
import com.chrispixel.chrisai.ui.UpdateUiState

@Composable
fun SettingsScreen(vm: ChrisViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    val ttsVoices = remember { mutableStateListOf<TtsVoiceInfo>() }
    LaunchedEffect(state.ttsEnabled) {
        if (state.ttsEnabled) {
            ttsVoices.clear()
            ttsVoices.addAll(vm.ttsVoiceInfos())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Ajustes", style = MaterialTheme.typography.titleLarge)

        SectionHeader("API")
        ApiKeyStatus(enabled = state.apiKeySet)

        SectionHeader("Modelo de IA")
        ModelSection(
            selectedModel = state.selectedModel,
            models = state.availableModels,
            loading = state.modelsLoading,
            onSelect = { vm.selectModel(it) },
            onRefresh = { vm.refreshModels() }
        )

        SectionHeader("Temperatura")
        TemperatureSection(temperature = state.temperature, onChanged = { vm.setTemperature(it) })

        SectionHeader("Personalidad")
        PersonalitySection(
            config = state.personality,
            feedback = state.personalityFeedback,
            onSave = { vm.setPersonality(it) },
            onReset = { vm.resetPersonality() },
            onDismissFeedback = { vm.dismissPersonalityFeedback() }
        )

        SectionHeader("Voz y audio")
        VoiceSection(
            ttsEnabled = state.ttsEnabled,
            ttsRate = state.ttsRate,
            ttsPitch = state.ttsPitch,
            ttsVoice = state.ttsVoice,
            autoRead = state.autoRead,
            onTtsEnabled = { vm.setTtsEnabled(it) },
            onRate = { vm.setTtsRate(it) },
            onPitch = { vm.setTtsPitch(it) },
            onVoice = { vm.setTtsVoice(it) },
            onPreview = { vm.previewTts(it) },
            onAutoRead = { vm.setAutoRead(it) },
            ttsVoices = ttsVoices
        )

        SectionHeader("Experiencia")
        ExperienceSection(
            hapticsEnabled = state.hapticsEnabled,
            animationsEnabled = state.animationsEnabled,
            onHaptics = { vm.setHapticsEnabled(it) },
            onAnimations = { vm.setAnimationsEnabled(it) }
        )

        SectionHeader("Llamada y vision")
        CallVisionSection(
            callMode = state.callModeEnabled,
            callGreeting = state.callGreetingEnabled,
            callContinuous = state.callContinuousEnabled,
            images = state.imagesEnabled,
            onCallMode = { vm.setCallModeEnabled(it) },
            onCallGreeting = { vm.setCallGreetingEnabled(it) },
            onCallContinuous = { vm.setCallContinuousEnabled(it) },
            onImages = { vm.setImagesEnabled(it) }
        )

        SectionHeader("Memoria persistente")
        MemorySection(
            memories = state.memories,
            feedback = state.memoryFeedback,
            onAdd = { vm.addMemory(it) },
            onRemove = { vm.removeMemory(it) },
            onDismissFeedback = { vm.dismissMemoryFeedback() }
        )

        SectionHeader("Actualizaciones")
        UpdateSection(
            update = state.update,
            onCheck = { vm.checkForUpdates() },
            onDownload = { vm.downloadUpdate() },
            onInstall = { context -> vm.installUpdate(context) }
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "ChrisAI v${BuildConfig.VERSION_NAME} · modelos vía OpenRouter",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.height(12.dp))
}

/** The API key is baked into the build: the user never has to type it. */
@Composable
private fun ApiKeyStatus(enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (enabled) "API key configurada automáticamente." else "Sin API key en esta build.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "No es necesario introducirla: ChrisAI la usa de la propia aplicación.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ModelSection(
    selectedModel: String,
    models: List<com.chrispixel.chrisai.data.model.AiModel>,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var custom by rememberSaveable { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Modelo activo: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SelectionContainer {
            Text(
                selectedModel,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, enabled = !loading) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar modelos")
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    models.forEach { model ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(model.id) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = model.id == selectedModel, onClick = { onSelect(model.id) })
            Spacer(Modifier.width(4.dp))
            Column {
                Text(model.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    model.id.ifBlank { model.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = custom,
        onValueChange = { custom = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Modelo personalizado") },
        placeholder = { Text("openrouter/auto") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
    Spacer(Modifier.height(6.dp))
    Button(onClick = { onSelect(custom.trim()); custom = "" }, enabled = custom.isNotBlank()) {
        Text("Usar modelo")
    }
}

@Composable
private fun TemperatureSection(temperature: Double, onChanged: (Double) -> Unit) {
    var value by rememberSaveable { mutableStateOf(temperature.toFloat()) }
    Text(
        "Valor: %.2f".format(value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChanged(value.toDouble()) },
        valueRange = 0f..1f,
        steps = 19
    )
}

@Composable
private fun PersonalitySection(
    config: PersonalityConfig,
    feedback: String?,
    onSave: (PersonalityConfig) -> Unit,
    onReset: () -> Unit,
    onDismissFeedback: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(config.name) }
    var presetId by rememberSaveable { mutableStateOf(config.presetId) }
    var humor by rememberSaveable { mutableIntStateOf(config.humorLevel) }
    var detail by rememberSaveable { mutableIntStateOf(config.detailLevel) }
    var style by rememberSaveable { mutableStateOf(config.communicationStyle) }
    var instructions by rememberSaveable { mutableStateOf(config.customInstructions) }

    Text(
        "Define cómo habla y responde ChrisAI. Las reglas de seguridad siempre tienen prioridad.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(30) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Nombre del asistente") },
        singleLine = true,
        supportingText = { Text("${name.length}/30") }
    )
    Spacer(Modifier.height(8.dp))

    Text("Preset", style = MaterialTheme.typography.titleSmall)
    PersonalityPreset.all().forEach { preset ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { presetId = preset.id }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = preset.id == presetId, onClick = { presetId = preset.id })
            Spacer(Modifier.width(4.dp))
            Column {
                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    preset.description,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Humor: $humor/5", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = humor.toFloat(),
        onValueChange = { humor = it.toInt().coerceIn(1, 5) },
        valueRange = 1f..5f,
        steps = 3
    )
    Text("Detalle: $detail/5", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = detail.toFloat(),
        onValueChange = { detail = it.toInt().coerceIn(1, 5) },
        valueRange = 1f..5f,
        steps = 3
    )

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = style,
        onValueChange = { style = it.take(120) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Estilo de comunicación") },
        placeholder = { Text("Ej.: conversacional y directa") },
        singleLine = true
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = instructions,
        onValueChange = { instructions = it.take(800) },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        label = { Text("Instrucciones personalizadas") },
        placeholder = { Text("Siempre salúdame por mi nombre, evita tecnicismos…") },
        maxLines = 4
    )

    if (feedback != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            feedback,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onDismissFeedback() }
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                onSave(
                    PersonalityConfig(
                        name = name,
                        presetId = presetId,
                        humorLevel = humor,
                        detailLevel = detail,
                        communicationStyle = style,
                        customInstructions = instructions
                    )
                )
            }
        ) {
            Text("Guardar personalidad")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onReset) {
            Text("Restablecer")
        }
    }
}

@Composable
private fun VoiceSection(
    ttsEnabled: Boolean,
    ttsRate: Float,
    ttsPitch: Float,
    ttsVoice: String,
    autoRead: Boolean,
    onTtsEnabled: (Boolean) -> Unit,
    onRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onVoice: (String) -> Unit,
    onPreview: (String) -> Unit,
    onAutoRead: (Boolean) -> Unit,
    ttsVoices: List<TtsVoiceInfo>
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Leer respuestas en voz alta", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = ttsEnabled, onCheckedChange = onTtsEnabled)
    }
    Text(
        "Añade un botón 🔊 en cada respuesta. La síntesis es nativa de Android, sin conexión extra.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (ttsEnabled) {
        Spacer(Modifier.height(8.dp))
        Text("Velocidad: %.2fx".format(ttsRate), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = ttsRate,
            onValueChange = onRate,
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Spacer(Modifier.height(4.dp))
        Text("Tono (pitch): %.2fx".format(ttsPitch), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = ttsPitch,
            onValueChange = onPitch,
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Spacer(Modifier.height(8.dp))
        Text("Probar voz", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onPreview("Hola, soy ChrisAI. Esta es mi voz.") }) {
            Text("▶ Reproducir ejemplo")
        }

        if (ttsVoices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Voz actual: ${ttsVoice.ifBlank { "predeterminada" }}",
                style = MaterialTheme.typography.bodyMedium
            )
            ttsVoices.take(8).forEach { voice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVoice(voice.name) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = voice.name == ttsVoice, onClick = { onVoice(voice.name) })
                    Spacer(Modifier.width(4.dp))
                    Text(
                        voice.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        voice.localeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (ttsVoices.size > 8) {
                Text(
                    "…y ${ttsVoices.size - 8} voces más (elige por nombre con texto o voz del sistema).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Ninguna voz disponible ahora. Reinicia la aplicación si acabas de activar el TTS.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lectura automática al terminar", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = autoRead, onCheckedChange = onAutoRead)
        }
        Text(
            "Reproduce cada respuesta del asistente automáticamente sin tocar nada.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExperienceSection(
    hapticsEnabled: Boolean,
    animationsEnabled: Boolean,
    onHaptics: (Boolean) -> Unit,
    onAnimations: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Vibración sutil", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = hapticsEnabled, onCheckedChange = onHaptics)
    }
    Text(
        "Pequeñas pulsaciones al enviar y en cada fragmento de la respuesta.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Animaciones", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = animationsEnabled, onCheckedChange = onAnimations)
    }
    Text(
        "Fondos de color según el estado de ChrisAI y transiciones suaves.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CallVisionSection(
    callMode: Boolean,
    callGreeting: Boolean,
    callContinuous: Boolean,
    images: Boolean,
    onCallMode: (Boolean) -> Unit,
    onCallGreeting: (Boolean) -> Unit,
    onCallContinuous: (Boolean) -> Unit,
    onImages: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Modo llamada", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = callMode, onCheckedChange = onCallMode)
    }
    Text(
        "Muestra el botón 📞 en el chat para conversar por voz de forma continua.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (callMode) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saludo inicial", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = callGreeting, onCheckedChange = onCallGreeting)
        }
        Text(
            "ChrisAI te saluda al empezar cada llamada.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Escucha continua", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = callContinuous, onCheckedChange = onCallContinuous)
        }
        Text(
            "Vuelve a escuchar automáticamente tras cada respuesta.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Envio de imagenes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = images, onCheckedChange = onImages)
    }
    Text(
        "Permite adjuntar fotos en el chat y que ChrisAI las analice.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MemorySection(
    memories: List<com.chrispixel.chrisai.data.model.Memory>,
    feedback: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismissFeedback: () -> Unit
) {
    var memoryInput by rememberSaveable { mutableStateOf("") }

    Text(
        "Dile «recuerda que…» en el chat y ChrisAI guardará el dato. " +
            "También puede decidir guardar memorias por sí mismo cuando son importantes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = memoryInput,
            onValueChange = { memoryInput = it },
            modifier = Modifier.weight(1f),
            label = { Text("Nuevo recuerdo") },
            singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                onAdd(memoryInput)
                memoryInput = ""
            },
            enabled = memoryInput.isNotBlank()
        ) {
            Text("Guardar")
        }
    }
    if (feedback != null) {
        Text(
            feedback,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { onDismissFeedback() }
        )
    }
    Spacer(Modifier.height(8.dp))
    if (memories.isEmpty()) {
        Text(
            "Mi memoria está vacía.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        memories.forEach { memory ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    memory.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onRemove(memory.text) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Eliminar recuerdo", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun UpdateSection(
    update: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (android.content.Context) -> Unit
) {
    val context = LocalContext.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Versión instalada: v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onCheck, enabled = !update.checking && !update.downloading && update.available == null) {
            Text("Buscar")
        }
    }

    when {
        update.checking && update.available == null -> {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Comprobando actualizaciones…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        update.available != null -> {
            val release: ReleaseInfo = update.available
            Spacer(Modifier.height(10.dp))
            Text(
                "Nueva versión v${release.versionLabel} disponible.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Release: ${release.name.ifBlank { release.tagName }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(Modifier.height(8.dp))

            if (update.downloading) {
                LinearProgressIndicator(
                    progress = { update.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Text(
                    "Descargando… ${(update.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (update.readyFile != null) {
                Button(onClick = { onInstall(context) }) {
                    Text("Instalar v${release.versionLabel}")
                }
            } else {
                Button(onClick = onDownload) {
                    Text("Descargar e instalar")
                }
            }

            update.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        update.message != null -> {
            Spacer(Modifier.height(10.dp))
            Text(
                update.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> {
            Spacer(Modifier.height(10.dp))
            Text(
                "Se comprobó al iniciar la app.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    Text(
        "Solo se usan releases oficiales del repositorio de ChrisAI. " +
            "La descarga e instalación siempre requieren tu confirmación.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}