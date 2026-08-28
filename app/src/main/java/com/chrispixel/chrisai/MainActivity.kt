package com.chrispixel.chrisai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrispixel.chrisai.ui.ChrisViewModel
import com.chrispixel.chrisai.ui.MainScreen
import com.chrispixel.chrisai.ui.theme.ChrisAiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChrisViewModel = viewModel()
            ChrisAiTheme {
                MainScreen(vm)
            }
        }
    }
}