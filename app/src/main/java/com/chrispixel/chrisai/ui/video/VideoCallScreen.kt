package com.chrispixel.chrisai.ui.video

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chrispixel.chrisai.data.live.LiveStage
import com.chrispixel.chrisai.data.vision.VisionFrameBus
import com.chrispixel.chrisai.ui.ChrisViewModel
import com.chrispixel.chrisai.ui.avatar3d.ChrisAvatar3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * v1.0 standalone video-call screen: user camera + the ChrisAI avatar, with
 * mic/screen/explain/hang-up controls. Reuses the existing call engine and
 * VisionFrameBus capture pipeline (no new hardware paths).
 */
@Composable
fun VideoCallScreen(vm: ChrisViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var cameraFrame by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        VisionFrameBus.frames
            .filter { it.source == VisionFrameBus.SOURCE_CAMERA }
            .map { it.path }
            .distinctUntilChanged()
            .collect { cameraFrame = it }
    }

    val cameraGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startCamera() }

    val micGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> vm.onScreenPermissionResult(result.resultCode, result.data) }

    LaunchedEffect(Unit) {
        (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0E14), Color(0xFF111725))))
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                stage = state.liveStage,
                callActive = state.callActive,
                onClose = { vm.closeVideoCall(hangUp = false) }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChrisAvatar3D(
                        emotion = state.emotion,
                        stage = state.liveStage,
                        intensity = state.emotionState?.intensity ?: 0f,
                        animationsEnabled = state.animationsEnabled,
                        modifier = Modifier.size(260.dp, 300.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (state.videoCallActive) "Videollamada activa" else stageText(state.liveStage),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFA9D7E8)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF1B1E28),
                shadowElevation = 6.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 176.dp, height = 132.dp)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.cameraActive && cameraFrame != null) {
                        CameraPreview(path = cameraFrame!!, mirror = state.cameraFacing == "front")
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📷", fontSize = 30.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (state.cameraActive) "Iniciando cámara…" else "Cámara pausada",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8A93A6)
                            )
                        }
                    }
                }
            }

            if (state.cameraActive) {
                Text(
                    if (state.cameraFacing == "front") "Cámara frontal (mirror)" else "Cámara trasera",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A93A6),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp)
                )
            }

            state.videoError?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Controls(
                callActive = state.callActive,
                cameraActive = state.cameraActive,
                cameraFacing = state.cameraFacing,
                screenSharing = state.screenSharing,
                hasVisionFrame = state.hasVisionFrame,
                micGranted = micGranted,
                onMic = { vm.interruptCall() },
                onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onToggleCamera = {
                    if (state.cameraActive) vm.stopCamera()
                    else if (cameraGranted) vm.startCamera()
                    else cameraLauncher.launch(Manifest.permission.CAMERA)
                },
                onSwitchCamera = { vm.switchCamera() },
                onRequestScreen = {
                    val projection = context.getSystemService(MediaProjectionManager::class.java)
                    if (projection != null) screenCaptureLauncher.launch(projection.createScreenCaptureIntent())
                },
                onStopScreen = { vm.stopScreenSharing() },
                onExplain = { vm.explainScreen() },
                onHangUp = { vm.closeVideoCall(hangUp = true) }
            )
        }
    }
}

private fun stageText(stage: LiveStage?): String = when (stage) {
    LiveStage.LISTENING -> "🎙️ Escuchando…"
    LiveStage.THINKING -> "💭 Procesando…"
    LiveStage.GENERATING -> "✍️ Escribiendo…"
    LiveStage.SPEAKING -> "🔊 Hablando…"
    LiveStage.INTERRUPTED -> "✋ Interrumpido"
    LiveStage.ERROR -> "⚠️ Se detuvo la llamada"
    else -> "Con Chris AI"
}

@Composable
private fun TopBar(
    stage: LiveStage?,
    callActive: Boolean,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Salir de la videollamada",
                tint = Color(0xFFE6EAF2)
            )
        }
        Text(
            "Videollamada",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE6EAF2),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = if (callActive) Color(0xFF0E7490) else Color(0xFF1B1E28),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                stageText(stage),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD3F2FC),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun CameraPreview(path: String, mirror: Boolean) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = "Cámara actual",
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14))
                .graphicsLayer { scaleX = if (mirror) -1f else 1f },
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun Controls(
    callActive: Boolean,
    cameraActive: Boolean,
    cameraFacing: String,
    screenSharing: Boolean,
    hasVisionFrame: Boolean,
    micGranted: Boolean,
    onMic: () -> Unit,
    onRequestMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onRequestScreen: () -> Unit,
    onStopScreen: () -> Unit,
    onExplain: () -> Unit,
    onHangUp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ControlChip(
                label = if (cameraActive) "Pausar" else "Reanudar",
                emoji = "📷",
                active = cameraActive,
                modifier = Modifier.weight(1f),
                onClick = onToggleCamera
            )
            ControlChip(
                label = if (cameraFacing == "back") "Frontal" else "Trasera",
                emoji = "🔄",
                active = false,
                enabled = cameraActive,
                modifier = Modifier.weight(1f),
                onClick = onSwitchCamera
            )
            ControlChip(
                label = if (screenSharing) "Detener" else "Pantalla",
                emoji = "🖥️",
                active = screenSharing,
                modifier = Modifier.weight(1f),
                onClick = if (screenSharing) onStopScreen else onRequestScreen
            )
            ControlChip(
                label = "Qué ves",
                emoji = "👀",
                active = false,
                enabled = hasVisionFrame && callActive,
                modifier = Modifier.weight(1f),
                onClick = onExplain
            )
            ControlChip(
                label = if (callActive) "Interrumpir" else "Micrófono",
                emoji = "🎙️",
                active = false,
                modifier = Modifier.weight(1f),
                onClick = if (micGranted) onMic else onRequestMic
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = Color(0xFFFF5C5C),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onHangUp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Colgar",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ControlChip(
    label: String,
    emoji: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = if (active) Color(0xFF0E7490) else Color(0xFF1B1E28),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) Color(0xFFD3F2FC) else Color(0xFF5A6476),
                maxLines = 1
            )
        }
    }
}