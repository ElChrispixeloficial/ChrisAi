package com.chrispixel.chrisai.ui.dev

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrispixel.chrisai.data.devagent.DevAgentFile
import com.chrispixel.chrisai.ui.ChrisViewModel

/**
 * v1.1 Developer Mode: a local agent built on the Storage Access Framework.
 * Pick a folder anywhere on the device or Google Drive; ChrisAI lists its files
 * and can attach a readable file's content into the chat as context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperModeScreen(
    vm: ChrisViewModel,
    onBack: () -> Unit,
    onAttached: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.setDeveloperAgentFolder(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (state.devAgentUri.isBlank()) {
                EmptyAgent(state = state, onPick = { folderPicker.launch(null) })
            } else {
                ConfiguredAgent(
                    state = state,
                    onPick = { folderPicker.launch(null) },
                    onRefresh = { vm.refreshDeveloperAgentFiles() },
                    onClear = { vm.clearDeveloperAgentFolder() },
                    onAttach = { file -> if (vm.attachDevFile(file.name, file.uri)) onAttached() },
                    onDismissError = { vm.dismissDevAttachError() }
                )
            }
        }
    }
}

@Composable
private fun EmptyAgent(
    state: com.chrispixel.chrisai.ui.ChatUiState,
    onPick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Agente local",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Elige una carpeta de tu dispositivo o de Google Drive. ChrisAI " +
                "podrá leer sus archivos de texto y usarlos como contexto en el chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onPick) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Seleccionar carpeta de trabajo")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "El acceso se conserva en este dispositivo. Solo se leen archivos de " +
                "texto pequeños; los binarios y las carpetas no se adjuntan.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ColumnScope.ConfiguredAgent(
    state: com.chrispixel.chrisai.ui.ChatUiState,
    onPick: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onAttach: (DevAgentFile) -> Unit,
    onDismissError: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Carpeta de trabajo",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Con acceso de lectura persistente",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRefresh, enabled = !state.devAgentLoading) {
            if (state.devAgentLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualizar archivos")
            }
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Delete, contentDescription = "Quitar carpeta", tint = MaterialTheme.colorScheme.error)
        }
    }
    if (state.devAgentPath != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            state.devAgentPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 2
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onPick) {
        Text("Cambiar carpeta")
    }

    state.devAttachError?.let {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismissError) { Text("OK") }
        }
    }

    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.height(8.dp))

    Text(
        "Archivos de la carpeta (${state.devAgentFiles.size})",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Toca «Adjuntar» para enviar el contenido de un archivo de texto a " +
            "ChrisAI como contexto y leer su análisis.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    if (state.devAgentLoading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Leyendo la carpeta…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.devAgentMessage?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(state.devAgentFiles, key = { it.uri }) { file ->
            AgentFileRow(file = file, onAttach = onAttach)
        }
    }
}

@Composable
private fun AgentFileRow(file: DevAgentFile, onAttach: (DevAgentFile) -> Unit) {
    val icon: ImageVector = if (file.isDirectory) Icons.Filled.Add else Icons.Filled.Star
    val readable = !file.isDirectory &&
        (file.mimeType.startsWith("text/") ||
            file.name.substringAfterLast('.', "").lowercase() in TextExt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Text(
                (if (file.isDirectory) "Carpeta" else file.displaySize.ifBlank { file.mimeType.ifBlank { "archivo" } }),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (!file.isDirectory && readable) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onAttach(file) }) { Text("Adjuntar") }
        }
    }
}

private val TextExt = setOf(
    "txt", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "kt",
    "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "sql", "yml", "yaml",
    "toml", "ini", "cfg", "properties", "log", "csv", "tsv", "sh", "bat", "gradle"
)
