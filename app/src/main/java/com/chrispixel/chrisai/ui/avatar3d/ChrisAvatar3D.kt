package com.chrispixel.chrisai.ui.avatar3d

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.live.LiveStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * v1.1 ChrisAI as a real articulated 3D model (OpenGL ES 3.0) inside Compose.
 *
 * Same inputs as the v1.0 [com.chrispixel.chrisai.ui.components.ChrisAvatar]
 * so screens can swap the 2D fallback for the real model without changing
 * their call sites. Drag rotates the orbital camera; the model can be seen
 * from any angle (volume + lighting = actual 3D).
 */
@Composable
fun ChrisAvatar3D(
    emotion: Emotion,
    stage: LiveStage?,
    intensity: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val store = remember { AvatarSceneStore() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(AvatarRenderer(store))
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            preserveEGLContextOnPause = true
        }
    }

    LaunchedEffect(emotion, stage, intensity, animationsEnabled) {
        store.emotion = emotion
        store.stage = stage
        store.intensity = intensity
        if (!animationsEnabled) store.autoOrbit = false
    }

    // Continuous animation loop (breathing, orbit, blink, mouth...) driven by
    // requestRender so the GL thread only wakes when there is something to draw.
    LaunchedEffect(view) {
        while (isActive) {
            view.requestRender()
            delay(16)
        }
    }

    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.onPause()
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(onDrag = { change: PointerInputChange, amount: Offset ->
                    change.consume()
                    store.drag(amount.x, amount.y)
                })
            }
    )
}