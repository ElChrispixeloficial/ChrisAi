package com.chrispixel.chrisai.ui

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrispixel.chrisai.data.drive.GoogleAccountPicker
import com.chrispixel.chrisai.ui.theme.Cyan
import com.chrispixel.chrisai.ui.theme.ErrorRed

/**
 * v1.0 first-run screen: "Continuar con Google" (minimal Drive backup) or
 * "Usar sin sincronización" (fully offline). The app never blocks on Drive.
 */
@Composable
fun OnboardingScreen(vm: ChrisViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showAccounts by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.refreshGoogleAccounts()
            showAccounts = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Hola, soy Chris AI",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Tu asistente personal con voz, memoria y acompañamiento.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Copia de seguridad en la nube",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tu historial, memoria y ajustes se guardan en tu propia cuenta de " +
                "Google Drive, con los permisos mínimos posible (solo archivos de ChrisAI).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (GoogleAccountPicker.hasPermission(context)) {
                    vm.refreshGoogleAccounts()
                    showAccounts = true
                } else {
                    permissionLauncher.launch(Manifest.permission.GET_ACCOUNTS)
                }
            }
        ) {
            Text("Continuar con Google")
        }

        Spacer(Modifier.height(10.dp))

        if (state.driveSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Cyan)
            Spacer(Modifier.height(8.dp))
        }

        state.driveSyncMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("No se pudo") || message.startsWith("Sincronización"))
                    ErrorRed
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))

        TextButton(
            onClick = { vm.skipOnboarding() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Usar sin sincronización", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "Puedes conectarlo más tarde desde Ajustes.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showAccounts) {
        val accounts = state.googleAccounts
        AlertDialog(
            onDismissRequest = { showAccounts = false },
            title = { Text("Elige tu cuenta de Google") },
            text = {
                if (accounts.isEmpty() && !state.driveSyncing) {
                    Column {
                        Text("No se encontraron cuentas de Google en este dispositivo.")
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent("android.intent.action.ADD_ACCOUNT").apply {
                                        putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google")
                                    }
                                )
                                showAccounts = false
                            }
                        ) {
                            Text("Añadir cuenta")
                        }
                    }
                } else {
                    Column {
                        accounts.forEach { email ->
                            TextButton(
                                onClick = {
                                    showAccounts = false
                                    vm.connectDrive(email)
                                }
                            ) {
                                Text(email, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccounts = false }) { Text("Cancelar") }
            }
        )
    }
}