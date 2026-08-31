package com.chrispixel.chrisai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.live.LiveStage

/**
 * v1.0 3D-styled ChrisAI android drawn in pure Compose.
 *
 * White rounded head with a black "screen" face, cyan neon eyes and mouth,
 * black shoulders and segmented arms; reacts to [Emotion] (glow tint) and to
 * the live [LiveStage] (listening / thinking dots / speaking mouth / idle).
 * No real 3D assets: a perspective rotationY + layered shapes ("esto ya no es
 * una beta" without heavy files).
 */
@Composable
fun ChrisAvatar(
    emotion: Emotion,
    stage: LiveStage?,
    intensity: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val cyan = Color(0xFF22D3EE)
    val white = Color(0xFFF2F5FA)
    val dark = Color(0xFF101419)
    val softDark = Color(0xFF1B1E28)
    val red = Color(0xFFFF6B6B)

    // Horizontal "breathing" tilt: a subtle 3D sway keeps it alive.
    val transition = rememberInfiniteTransition(label = "avatarSway")
    val sway by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing), RepeatMode.Reverse),
        label = "swayDeg"
    )
    val blinkScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 3400, delayMillis = 2100),
            RepeatMode.Restart
        ),
        label = "blink"
    )
    val armSwing by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "arms"
    )

    val speaking = stage == LiveStage.SPEAKING
    val thinking = stage == LiveStage.THINKING || stage == LiveStage.GENERATING
    val mouthOpen by animateFloatAsState(
        targetValue = if (speaking) 0.6f else 0f,
        animationSpec = tween(if (speaking) 240 else 350),
        label = "mouth"
    )

    val glowTint by animateFloatAsState(
        targetValue = if (animationsEnabled && intensity > 0f && emotion != Emotion.NEUTRAL) 1f else 0f,
        animationSpec = tween(1400),
        label = "glow"
    )
    val accent = emotion.accent

    val tilt = sway + if (speaking) 2f else 0f

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(210.dp, 250.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // Emotion glow behind the whole bot.
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (glowTint > 0.01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.38f * glowTint),
                                Color.Transparent
                            ),
                            center = center.copy(x = center.x, y = size.height * 0.42f),
                            radius = size.width * 0.62f
                        )
                    )
                }
            }

            // Torso + shoulders + arms.
            Column(
                modifier = Modifier
                    .padding(top = 128.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChristopherArm(sway = armSwing, left = true)
                    Torso(softDark)
                    ChristopherArm(sway = armSwing, left = false)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(124.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        .background(softDark)
                )
            }

            // The head floats on top.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(132.dp)
                    .graphicsLayer {
                        rotationY = tilt
                        cameraDistance = 12f * density
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(30.dp))
                        .background(white)
                )
                HeadFace(
                    thinking = thinking,
                    speaking = speaking,
                    mouthOpen = mouthOpen,
                    blinkScale = blinkScale,
                    stage = stage,
                    animationsEnabled = animationsEnabled
                )
            }
        }
    }
}

@Composable
private fun Torso(softDark: Color) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
            .background(Color(0xFFE6EAF2)), // lighter white so the bot reads as the hero
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Chris AI",
            color = Color(0xFF0E7490),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(5.dp)
                .background(Color(0xFF22D3EE))
        )
    }
}

@Composable
private fun ChristopherArm(sway: Float, left: Boolean) {
    val anchor = if (left) Modifier else Modifier
    Column(
        modifier = anchor
            .graphicsLayer {
                rotationZ = if (left) -sway * 0.7f else sway * 0.7f
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFFF2F5FA))
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = 14.dp, height = 22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFFE6EAF2))
        )
    }
}

@Composable
private fun HeadFace(
    thinking: Boolean,
    speaking: Boolean,
    mouthOpen: Float,
    blinkScale: Float,
    stage: LiveStage?,
    animationsEnabled: Boolean
) {
    // Face area inside the screen: neon cyan on near-black.
    val face = Color(0xFF0B0E14)
    val neon = Color(0xFF22D3EE)
    val red = Color(0xFFFF6B6B)

    val dotsTransition = rememberInfiniteTransition(label = "thinkingDots")
    val dotX by dotsTransition.animateFloat(
        initialValue = -18f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dots"
    )
    val listeningPulse by dotsTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "listen"
    )

    val showError = stage == LiveStage.ERROR

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(face)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (thinking) {
                // Three animated dots as "processing" cursor.
                repeat(3) { i ->
                    val x = w / 2 - 22f + i * 22f + dotX * 0.2f
                    val y = h * 0.72f
                    val r = 3.5f + i * 0.8f
                    drawCircle(
                        color = neon.copy(alpha = 0.9f),
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            } else {
                // Eyes: neon pills that blink (scaleY) and brighten when speaking.
                val eyeW = w * 0.11f
                val eyeH = h * 0.16f * (if (speaking) 1.15f else 1f) * blinkScale
                val eyeY = h * 0.40f
                val gap = w * 0.22f
                val eyeColor = if (speaking) neon.copy(alpha = 1f) else neon.copy(alpha = 0.92f)
                drawRoundRect(
                    color = eyeColor,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2f - gap - eyeW, eyeY - eyeH / 2f),
                    size = androidx.compose.ui.geometry.Size(eyeW, eyeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW / 2f, eyeH / 2f)
                )
                drawRoundRect(
                    color = eyeColor,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2f + gap, eyeY - eyeH / 2f),
                    size = androidx.compose.ui.geometry.Size(eyeW, eyeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(eyeW / 2f, eyeH / 2f)
                )

                // Mouth: rounded bar; opens (or traces a smile) while speaking.
                val mouthW = w * 0.34f
                val mouthH = 4f + 10f * mouthOpen
                val mouthY = h * 0.72f
                if (showError) {
                    drawRoundRect(
                        color = red,
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2f - mouthW / 2f, mouthY - mouthH / 2f),
                        size = androidx.compose.ui.geometry.Size(mouthW, mouthH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(mouthH / 2f, mouthH / 2f)
                    )
                } else {
                    drawRoundRect(
                        color = neon.copy(alpha = 0.95f),
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2f - mouthW / 2f, mouthY - mouthH / 2f),
                        size = androidx.compose.ui.geometry.Size(mouthW, mouthH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(mouthH / 2f, mouthH / 2f)
                    )
                }

                // Listening: soft cyan "reception" arcs beside the face.
                if (stage == LiveStage.LISTENING) {
                    val arcAlpha = 0.25f + 0.5f * listeningPulse
                    drawArc(
                        color = neon.copy(alpha = arcAlpha),
                        startAngle = 250f,
                        sweepAngle = 40f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.30f),
                        size = androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.40f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                    )
                    drawArc(
                        color = neon.copy(alpha = arcAlpha),
                        startAngle = 70f,
                        sweepAngle = 40f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.30f),
                        size = androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.40f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                    )
                }
            }
        }
    }
}