package com.chrispixel.chrisai.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.chrispixel.chrisai.ui.chat.ChatScreen
import com.chrispixel.chrisai.ui.history.HistoryScreen
import com.chrispixel.chrisai.ui.settings.SettingsScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Filled.Home),
    HISTORY("Historial", Icons.AutoMirrored.Filled.List),
    SETTINGS("Ajustes", Icons.Filled.Settings)
}

@Composable
fun MainScreen(vm: ChrisViewModel) {
    var tab by rememberSaveable { mutableStateOf(MainTab.CHAT) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) }
                    )
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.CHAT -> ChatScreen(vm, onOpenSettings = { tab = MainTab.SETTINGS })
                MainTab.HISTORY -> HistoryScreen(vm, onOpenChat = { tab = MainTab.CHAT })
                MainTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}