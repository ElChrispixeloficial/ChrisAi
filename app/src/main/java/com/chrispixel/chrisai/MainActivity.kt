package com.chrispixel.chrisai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrispixel.chrisai.ui.ChrisViewModel
import com.chrispixel.chrisai.ui.MainScreen
import com.chrispixel.chrisai.ui.OnboardingScreen
import com.chrispixel.chrisai.ui.theme.ChrisAiTheme
import com.chrispixel.chrisai.ui.theme.Cyan

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChrisViewModel = viewModel()
            val state by vm.state.collectAsState()
            ChrisAiTheme {
                when {
                    !state.initialized -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = Cyan) }
                    !state.onboardingCompleted -> OnboardingScreen(vm)
                    else -> MainScreen(vm)
                }
            }
        }
    }
}