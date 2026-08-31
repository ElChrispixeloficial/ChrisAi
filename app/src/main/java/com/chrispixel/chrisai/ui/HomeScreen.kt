package com.chrispixel.chrisai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession

/** v1.0 HOME: a dashboard that opens every ChrisAI function in one tap. */
@Composable
fun HomeScreen(
    vm: ChrisViewModel,
    onOpenChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.state.collectAsState()
    var memoryDraft by rememberSaveable { mutableStateOf("") }

    val recent = state.sessions
        .sortedByDescending { it.updatedAt }
        .filter { it.messages.isNotEmpty() }
        .take(5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Inicio",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    state.selectedModel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        DriveCard(
            connected = state.driveSyncEnabled,
            syncing = state.driveSyncing,
            email = state.driveAccountEmail,
            lastSync = state.driveLastSync,
            onSync = { vm.syncDriveNow() },
            onOpenSettings = onOpenSettings
        )

        Spacer(Modifier.height(18.dp))

        Text("Acciones rápidas", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(
                icon = Icons.Filled.Add,
                label = "Conversar",
                weight = 1f,
                onClick = onOpenChat
            )
            QuickAction(
                icon = Icons.Filled.Person,
                label = "Videollamada",
                weight = 1f,
                onClick = { vm.openVideoCall() }
            )
            QuickAction(
                icon = Icons.Filled.Call,
                label = "Llamada",
                weight = 1f,
                onClick = {
                    if (!state.callActive) vm.toggleCall()
                    onOpenChat()
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(
                icon = Icons.Filled.Build,
                label = if (state.studyModeEnabled) "Estudio: ON" else "Modo estudio",
                weight = 1f,
                onClick = { vm.setStudyModeEnabled(!state.studyModeEnabled) }
            )
            QuickAction(
                icon = Icons.Filled.Star,
                label = "Historial",
                weight = 1f,
                onClick = onOpenHistory
            )
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = memoryDraft,
            onValueChange = { memoryDraft = it },
            label = { Text("Guardar algo en mi memoria") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                IconButton(
                    enabled = memoryDraft.isNotBlank(),
                    onClick = {
                        vm.addMemory(memoryDraft.trim())
                        memoryDraft = ""
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Guardar memoria")
                }
            }
        )
        state.memoryFeedback?.let { feedback ->
            Spacer(Modifier.height(4.dp))
            Text(
                feedback,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(18.dp))

        Text("Continuar conversaciones", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (recent.isEmpty()) {
            Text(
                "Aún no hay conversaciones. Empieza una nueva desde el tab Chat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            recent.forEach { session ->
                SessionCard(session = session, onClick = {
                    vm.openSession(session.id)
                    onOpenChat()
                })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DriveCard(
    connected: Boolean,
    syncing: Boolean,
    email: String,
    lastSync: String?,
    onSync: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSettings() },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(10.dp)
                    .background(
                        if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connected) "Copia de seguridad activa" else "Copia de seguridad en la nube",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        !connected -> "Conecta tu Google Drive para guardar historial, memoria y ajustes."
                        syncing -> "Sincronizando…"
                        lastSync != null -> "$email · últ. sync hoy a las $lastSync"
                        else -> email
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (connected) {
                IconButton(onClick = onSync) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sincronizar ahora")
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickAction(
    icon: ImageVector,
    label: String,
    weight: Float,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(weight)
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SessionCard(session: ChatSession, onClick: () -> Unit) {
    val preview = session.messages.lastOrNull { it.role == ChatRole.USER }?.content
        ?: session.messages.lastOrNull()?.content
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        session.kind.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            if (preview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}