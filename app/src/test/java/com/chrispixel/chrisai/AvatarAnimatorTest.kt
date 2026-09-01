package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.emotion.Emotion
import com.chrispixel.chrisai.data.live.LiveStage
import com.chrispixel.chrisai.ui.avatar3d.AvatarAnimator
import com.chrispixel.chrisai.ui.avatar3d.Joint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1: the pure avatar animator maps stage+emotion to poses deterministically. */
class AvatarAnimatorTest {

    private fun rot(j: Joint, p: com.chrispixel.chrisai.ui.avatar3d.AvatarPose, axis: Int): Float =
        p.rotations[j.ordinal * 3 + axis]

    @Test
    fun `idle neutral pose has relaxed fingers and closed mouth`() {
        val p = AvatarAnimator.pose(LiveStage.IDLE, Emotion.NEUTRAL, 0f, 0L)
        assertEquals(Joint.entries.size * 3, p.rotations.size)
        assertTrue("idle mouth nearly closed", p.face.mouthOpen < 0.2f)
        assertEquals(1f, p.face.eyeOpen, 0.001f)
        assertEquals(0f, p.emotionMix, 0.001f)
    }

    @Test
    fun `speaking opens the mouth and gestures`() {
        val p = AvatarAnimator.pose(LiveStage.SPEAKING, Emotion.NEUTRAL, 0f, 1_000L)
        assertTrue("speaking opens the mouth", p.face.mouthOpen > 0.3f)
        assertTrue("speaking bends right elbow", rot(j = Joint.ELBOW_R, p = p, axis = 0) < -40f)
    }

    @Test
    fun `thinking triggers face dots and raised arm`() {
        val p = AvatarAnimator.pose(LiveStage.THINKING, Emotion.THOUGHTFUL, 0.5f, 123_456L)
        assertTrue(p.face.showThinking)
        assertTrue(p.face.thinkingWave != 0f)
        assertTrue("right arm raised", rot(j = Joint.ELBOW_R, p = p, axis = 0) < -80f)
    }

    @Test
    fun `listening shows arcs and leans forward`() {
        val p = AvatarAnimator.pose(LiveStage.LISTENING, Emotion.NEUTRAL, 0f, 500L)
        assertTrue("listening arcs lit", p.face.listeningArc > 0f)
        assertTrue("leaning forward", rot(j = Joint.SPINE_UPPER, p = p, axis = 0) > 2f)
    }

    @Test
    fun `emotion intensity drives glow mix`() {
        val p = AvatarAnimator.pose(LiveStage.IDLE, Emotion.HAPPY, 0.6f, 0L)
        assertEquals(0.6f, p.emotionMix, 0.001f)
        assertTrue("happy glow is warm, not red", p.emotionR > 0.4f && p.emotionG > 0.3f)
    }

    @Test
    fun `angry closes fists harder than idle`() {
        val angry = AvatarAnimator.pose(LiveStage.IDLE, Emotion.ANGRY, 1f, 0L)
        val idle = AvatarAnimator.pose(LiveStage.IDLE, Emotion.NEUTRAL, 0f, 0L)
        assertTrue(
            "angry curls fingers deeper",
            rot(j = Joint.INDEX_3_L, p = angry, axis = 0) + 1f < rot(j = Joint.INDEX_3_L, p = idle, axis = 0)
        )
    }

    @Test
    fun `surprise widens the eyes`() {
        val p = AvatarAnimator.pose(LiveStage.IDLE, Emotion.SURPRISED, 0.8f, 0L)
        assertTrue("surprise widens eyes", p.face.eyeOpen > 1.15f)
    }

    @Test
    fun `error looks down and heats the face red`() {
        val p = AvatarAnimator.pose(LiveStage.ERROR, Emotion.NEUTRAL, 0f, 0L)
        assertTrue(p.face.lookingDown)
        assertTrue("error tints red", p.emotionR > 0.8f && p.emotionG < 0.6f)
    }

    @Test
    fun `neutral emotion never glows`() {
        val p = AvatarAnimator.pose(LiveStage.SPEAKING, Emotion.NEUTRAL, 1f, 0L)
        assertEquals(0f, p.emotionMix, 0.001f)
    }

    @Test
    fun `blink dips the eyes occasionally`() {
        val normal = AvatarAnimator.pose(LiveStage.LISTENING, Emotion.NEUTRAL, 0f, 10_000L)
        val mid = AvatarAnimator.pose(LiveStage.LISTENING, Emotion.NEUTRAL, 0f, 4_100L) // >94% of 4200ms
        assertTrue("blink closes eyes", mid.face.eyeOpen < 0.9f)
        assertTrue(normal.face.eyeOpen <= 1f)
    }

    @Test
    fun `pose is deterministic for same inputs`() {
        val a = AvatarAnimator.pose(LiveStage.SPEAKING, Emotion.HAPPY, 0.7f, 1234L)
        val b = AvatarAnimator.pose(LiveStage.SPEAKING, Emotion.HAPPY, 0.7f, 1234L)
        assertArrayEquals(a.rotations, b.rotations, 0.0001f)
    }
}