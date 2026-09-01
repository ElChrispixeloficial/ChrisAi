package com.chrispixel.chrisai.ui.avatar3d

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp

/**
 * Real-3D (OpenGL ES 3.0) renderer for the articulated ChrisAI android.
 *
 * Walks the [Joint] tree, draws each body mesh with per-joint world matrices,
 * applies shading, a contact shadow and an additive neon face overlay
 * (eyes/mouth/thinking dots/listening waves) driven by [AvatarAnimator].
 * The camera orbits around the model (draggable) so the volume reads in 3D.
 *
 * Body style: rounded tapered capsules (white), black spheres at the joints
 * and shoulders, a head slab + speaker, tapered limbs, feet with a separate
 * heel/forefoot volume — one cohesive android, not a stack of primitive boxes.
 */
internal class AvatarRenderer(private val store: AvatarSceneStore) : android.opengl.GLSurfaceView.Renderer {

    private var solidProg = 0
    private var glowProg = 0
    private var shadowProg = 0

    private val vPMatrix = FloatArray(16)
    private val vMatrix = FloatArray(16)

    private val jointWorld = Array(Joint.entries.size) { FloatArray(16) }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var smoothRot = FloatArray(Joint.entries.size * 3)
    private var smoothMouth = 0.12f
    private var smoothEye = 1f
    private var smoothListening = 0f
    private var smoothMix = 0f
    private var lastNanos = 0L

    private val parts = ArrayList<Part>()

    private class Part(
        val joint: Joint,
        val offset: FloatArray,
        val scale: FloatArray,
        val color: FloatArray,
        val emotionBlend: Boolean
    ) {
        lateinit var mesh: Mesh
    }

    private class Mesh {
        lateinit var vboPos: FloatBuffer
        lateinit var ibo: IntBuffer
        var indexCount = 0
        var uploaded = false
        private val vbo = IntArray(1)
        private val ebo = IntArray(1)

        fun build(vertices: FloatArray, normals: FloatArray, indices: IntArray) {
            val inter = FloatArray(vertices.size / 3 * 6)
            var w = 0
            for (i in 0 until vertices.size step 3) {
                inter[w++] = vertices[i]
                inter[w++] = vertices[i + 1]
                inter[w++] = vertices[i + 2]
                inter[w++] = normals[i]
                inter[w++] = normals[i + 1]
                inter[w++] = normals[i + 2]
            }
            vboPos = toFloatBuffer(inter)
            ibo = toIntBuffer(indices)
            indexCount = indices.size
        }

        fun upload(attrLocations: IntArray) {
            if (!uploaded) {
                GLES30.glGenBuffers(1, vbo, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboPos.capacity() * 4, vboPos, GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                GLES30.glGenBuffers(1, ebo, 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo[0])
                GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo.capacity() * 4, ibo, GLES30.GL_STATIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
                uploaded = true
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glEnableVertexAttribArray(attrLocations[0])
            GLES30.glVertexAttribPointer(attrLocations[0], 3, GLES30.GL_FLOAT, false, 24, 0)
            if (attrLocations.size > 1) {
                GLES30.glEnableVertexAttribArray(attrLocations[1])
                GLES30.glVertexAttribPointer(attrLocations[1], 3, GLES30.GL_FLOAT, false, 24, 12)
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo[0])
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.036f, 0.043f, 0.065f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        solidProg = buildShader(SOLID_VS, SOLID_FS)
        glowProg = buildShader(GLOW_VS, GLOW_FS)
        shadowProg = buildShader(GLOW_VS, SHADOW_FS)
        buildBody()
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height
        Matrix.perspectiveM(vPMatrix, 0, 40f, aspect, 0.05f, 40f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dtSec = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val pose = AvatarAnimator.pose(
            stage = store.stage,
            emotion = store.emotion,
            intensity = store.intensity,
            timeMs = System.currentTimeMillis()
        )

        if (store.autoOrbit) store.yawDeg += dtSec * 9f
        applyCamera()

        // Smooth pose transitions (barge-in, stage jumps) instead of snapping.
        val k = 1f - exp(-dtSec * 8f)
        for (i in smoothRot.indices) smoothRot[i] += (pose.rotations[i] - smoothRot[i]) * k
        smoothMouth += (pose.face.mouthOpen - smoothMouth) * (1f - exp(-dtSec * 16f))
        smoothEye += (pose.face.eyeOpen - smoothEye) * (1f - exp(-dtSec * 14f))
        smoothListening += (pose.face.listeningArc - smoothListening) * (1f - exp(-dtSec * 10f))
        smoothMix += (pose.emotionMix - smoothMix) * (1f - exp(-dtSec * 6f))

        computeJointWorld(smoothRot)
        drawOpaque(pose, smoothMix)
        drawFaceOverlay(pose)
        drawShadow()
    }

    // ------------------------------------------------------------ camera

    private fun applyCamera() {
        val yaw = store.yawDeg * (Math.PI.toFloat() / 180f)
        val pitch = store.pitchDeg * (Math.PI.toFloat() / 180f)
        val dist = 8.6f
        val cp = Math.cos(pitch.toDouble()).toFloat()
        val sp = Math.sin(pitch.toDouble()).toFloat()
        val sy = Math.sin(yaw.toDouble()).toFloat()
        val cy = Math.cos(yaw.toDouble()).toFloat()
        Matrix.setLookAtM(
            vMatrix, 0,
            dist * cp * sy, 1.8f + dist * sp, dist * cp * cy,
            0f, 1.75f, 0f,
            0f, 1f, 0f
        )
    }

    // ----------------------------------------------------------- skeleton

    private fun computeJointWorld(rot: FloatArray) {
        for (j in Joint.entries) {
            val parent = j.parent
            val pWorld = if (parent == null) identity else jointWorld[parent.ordinal]
            val tmp = FloatArray(16)
            Matrix.setIdentityM(tmp, 0)
            Matrix.translateM(tmp, 0, j.ox, j.oy, j.oz)
            val r = FloatArray(16)
            val idx = j.ordinal * 3
            Matrix.setRotateEulerM(r, 0, rot[idx], rot[idx + 1], rot[idx + 2])
            Matrix.multiplyMM(tmp, 0, tmp, 0, r, 0)
            Matrix.multiplyMM(jointWorld[j.ordinal], 0, pWorld, 0, tmp, 0)
        }
    }

    private fun partModel(p: Part): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, p.offset[0], p.offset[1], p.offset[2])
        val s = FloatArray(16)
        Matrix.setIdentityM(s, 0)
        Matrix.scaleM(s, 0, p.scale[0], p.scale[1], p.scale[2])
        Matrix.multiplyMM(m, 0, m, 0, s, 0)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, jointWorld[p.joint.ordinal], 0, m, 0)
        return out
    }

    private fun faceModel(offset: FloatArray, world: FloatArray): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, offset[0], offset[1], offset[2])
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, world, 0, m, 0)
        return out
    }

    // --------------------------------------------------------------- body

    private fun buildBody() {
        val G = AvatarGeometry
        val C = AvatarColors

        // Torso: pelvis → abdomen → chest.
        part(Joint.HIPS, G.capsule(0.60f, 0.55f, 0.26f), Y, C.WHITE, emotion = true)
        part(Joint.SPINE_LOWER, G.capsule(0.56f, 0.62f, 0.30f), Y, C.WHITE, emotion = true)
        part(Joint.SPINE_UPPER, G.capsule(0.70f, 0.72f, 0.30f), Y, C.WHITE, emotion = true)
        part(Joint.NECK, G.capsule(0.19f, 0.13f, 0.30f), floatArrayOf(0f, -0.10f, 0f), C.DARK)
        // Head chassis + horizontal speaker.
        part(Joint.HEAD, G.ellipsoid(0.60f, 0.52f, 0.55f), Y, C.WHITE, emotion = true)
        part(Joint.HEAD, G.ellipsoid(0.32f, 0.05f, 0.08f), floatArrayOf(0f, 0.22f, 0.33f), C.DARK)
        // Black shoulder knobs.
        part(Joint.CLAV_L, G.sphere(0.34f), floatArrayOf(0f, 0.05f, 0f), C.DARK)
        part(Joint.CLAV_R, G.sphere(0.34f), floatArrayOf(0f, 0.05f, 0f), C.DARK)

        arm(Joint.CLAV_L, mirrored = false)
        arm(Joint.CLAV_R, mirrored = true)
        leg(Joint.HIP_L)
        leg(Joint.HIP_R)
    }

    private fun arm(clav: Joint, mirrored: Boolean) {
        val G = AvatarGeometry
        val C = AvatarColors
        val U = if (!mirrored) Joint.UPPER_ARM_L else Joint.UPPER_ARM_R
        val E = if (!mirrored) Joint.ELBOW_L else Joint.ELBOW_R
        val F = if (!mirrored) Joint.FOREARM_L else Joint.FOREARM_R
        val W = if (!mirrored) Joint.WRIST_L else Joint.WRIST_R
        val H = if (!mirrored) Joint.HAND_L else Joint.HAND_R

        part(U, G.capsule(0.205f, 0.19f, 0.21f), Y, C.WHITE, emotion = true)
        part(E, G.sphere(0.24f), Y, C.DARK)
        part(F, G.capsule(0.17f, 0.13f, 0.22f), Y, C.WHITE_DIM)
        part(W, G.sphere(0.16f), Y, C.DARK)
        part(H, G.capsule(0.115f, 0.10f, 0.065f), Y, C.WHITE, emotion = true)

        // 5 digits per hand, separate thumb (2 phalanges) + 4 fingers (3 each).
val digits = if (!mirrored) {
            listOf(
                Joint.THUMB_1_L to Joint.THUMB_2_L,
                Joint.INDEX_1_L to Joint.INDEX_3_L,
                Joint.MID_1_L to Joint.MID_3_L,
                Joint.RING_1_L to Joint.RING_3_L,
                Joint.PINKY_1_L to Joint.PINKY_3_L
            )
        } else {
            listOf(
                Joint.THUMB_1_R to Joint.THUMB_2_R,
                Joint.INDEX_1_R to Joint.INDEX_3_R,
                Joint.MID_1_R to Joint.MID_3_R,
                Joint.RING_1_R to Joint.RING_3_R,
                Joint.PINKY_1_R to Joint.PINKY_3_R
            )
        }
        for ((first, last) in digits) {
            var j = first
            while (true) {
                part(j, G.capsule(0.042f, 0.036f, 0.05f), Y, C.WHITE)
                if (j == last) break
                j = Joint.entries[j.ordinal + 1]
            }
        }
    }

    private fun leg(hip: Joint) {
        val G = AvatarGeometry
        val C = AvatarColors
        val mirrored = hip == Joint.HIP_R
        val K = if (!mirrored) Joint.KNEE_L else Joint.KNEE_R
        val A = if (!mirrored) Joint.ANKLE_L else Joint.ANKLE_R

        part(hip, G.capsule(0.24f, 0.22f, 0.30f), floatArrayOf(0f, -0.30f, 0f), C.WHITE, emotion = true)
        part(K, G.sphere(0.28f), Y, C.DARK)
        part(K, G.capsule(0.20f, 0.15f, 0.30f), floatArrayOf(0f, -0.40f, 0f), C.WHITE_DIM)
        part(A, G.sphere(0.16f), Y, C.DARK)
        // Foot: heel volume + separate forefoot volume.
        part(A, G.ellipsoid(0.19f, 0.09f, 0.27f), floatArrayOf(0f, -0.10f, -0.05f), C.WHITE)
        part(A, G.ellipsoid(0.20f, 0.08f, 0.17f), floatArrayOf(0f, -0.08f, 0.13f), C.WHITE_DIM)
    }

    private fun part(
        joint: Joint,
        meshData: MeshData,
        offset: FloatArray,
        color: FloatArray,
        emotion: Boolean = false
    ) {
        val p = Part(joint, offset, floatArrayOf(1f, 1f, 1f), color, emotion)
        p.mesh = Mesh().also { it.build(meshData.vertices, meshData.normals, meshData.indices) }
        parts.add(p)
    }

    // --------------------------------------------------------------- draw

    private val SOLID_ATTRS = intArrayOf(0, 1)   // aPos, aNrm
    private val GLOW_ATTRS = intArrayOf(0)       // aPos

    private fun drawOpaque(pose: AvatarPose, mix: Float) {
        GLES30.glUseProgram(solidProg)
        val uV = loc(solidProg, "uV")
        val uP = loc(solidProg, "uP")
        val uM = loc(solidProg, "uM")
        val uBase = loc(solidProg, "uBase")
        val uEmo = loc(solidProg, "uEmo")
        val uMix = loc(solidProg, "uMix")
        val uEmissive = loc(solidProg, "uEmissive")
        GLES30.glUniformMatrix4fv(uV, 1, false, vMatrix, 0)
        GLES30.glUniformMatrix4fv(uP, 1, false, vPMatrix, 0)
        GLES30.glUniform3f(uEmo, pose.emotionR, pose.emotionG, pose.emotionB)
        GLES30.glUniform1f(uEmissive, 0f)
        GLES30.glDepthMask(true)

        val mvp = FloatArray(16)
        for (p in parts) {
            val model = partModel(p)
            GLES30.glUniformMatrix4fv(uM, 1, false, model, 0)
            GLES30.glUniform3f(uBase, p.color[0], p.color[1], p.color[2])
            GLES30.glUniform1f(uMix, if (p.emotionBlend) mix else 0f)
            p.mesh.upload(SOLID_ATTRS)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
    }

    private fun drawFaceOverlay(pose: AvatarPose) {
        val headWorld = jointWorld[Joint.HEAD.ordinal]

        // Dark recessed screen panel (face of the head).
        val screen = Mesh().also {
            it.build(
                facePanelVerts(), facePanelNormals(), intArrayOf(0, 1, 2, 0, 2, 3)
            )
        }
        GLES30.glUseProgram(solidProg)
        val uM = loc(solidProg, "uM")
        val uBase = loc(solidProg, "uBase")
        val uMix = loc(solidProg, "uMix")
        val uEmissive = loc(solidProg, "uEmissive")
        GLES30.glUniform1f(uMix, 0f)
        GLES30.glUniform1f(uEmissive, 0f)
        GLES30.glUniform3f(uBase, 0.02f, 0.035f, 0.05f)
        screen.upload(SOLID_ATTRS)
        val panelM = faceModel(floatArrayOf(0f, 0f, 0.575f), headWorld)
        GLES30.glUniformMatrix4fv(uM, 1, false, panelM, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, screen.indexCount, GLES30.GL_UNSIGNED_INT, 0)

        // Additive neon face glow: eyes, mouth, thinking dots, listening waves.
        GLES30.glUseProgram(glowProg)
        val gM = loc(glowProg, "uM")
        val gV = loc(glowProg, "uV")
        val gP = loc(glowProg, "uP")
        val gColor = loc(glowProg, "uColor")
        val gAlpha = loc(glowProg, "uAlpha")
        GLES30.glUniformMatrix4fv(gV, 1, false, vMatrix, 0)
        GLES30.glUniformMatrix4fv(gP, 1, false, vPMatrix, 0)
        val glowQuad = Mesh().also {
            it.build(ndcQuad(), ndcNormals(), intArrayOf(0, 1, 2, 0, 2, 3))
        }
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)
        GLES30.glUniform3f(gColor, 0.16f, 0.9f, 1.0f)

        // Eyes (independently scalable, so scaleM helpers).
        val eyeW = 0.17f
        val eyeY = 0.085f
        for (side in listOf(-1f, 1f)) {
            val m = faceModel(floatArrayOf(side * 0.135f, eyeY, 0.60f), headWorld)
            val sc = scaleM(eyeW, 0.075f * smoothEye, 1f)
            val mm = multiply(m, sc)
            GLES30.glUniformMatrix4fv(gM, 1, false, mm, 0)
            GLES30.glUniform1f(gAlpha, 1f)
            drawGlow(glowQuad)
        }
        // Mouth (opens when speaking / surprise).
        val mouthH = 0.04f + 0.11f * smoothMouth
        val mm = faceModel(floatArrayOf(0f, -0.11f, 0.60f), headWorld)
        GLES30.glUniformMatrix4fv(gM, 1, false, multiply(mm, scaleM(0.26f, mouthH, 1f)), 0)
        GLES30.glUniform1f(gAlpha, 1f)
        drawGlow(glowQuad)

        if (pose.face.showThinking) {
            for (i in 0 until 3) {
                val x = -0.085f + i * 0.085f + pose.face.thinkingWave * 0.03f
                val dm = faceModel(floatArrayOf(x, -0.10f, 0.60f), headWorld)
                GLES30.glUniformMatrix4fv(gM, 1, false, multiply(dm, scaleM(0.05f, 0.05f, 1f)), 0)
                GLES30.glUniform1f(gAlpha, 0.85f)
                drawGlow(glowQuad)
            }
        } else if (smoothListening > 0.05f) {
            for (sign in listOf(-1f, 1f)) {
                val lm = faceModel(floatArrayOf(sign * 0.42f, -0.04f, 0.60f), headWorld)
                GLES30.glUniformMatrix4fv(gM, 1, false, multiply(lm, scaleM(0.13f, 0.17f * smoothListening, 1f)), 0)
                GLES30.glUniform1f(gAlpha, 0.7f * smoothListening)
                drawGlow(glowQuad)
            }
        }

        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun drawGlow(m: Mesh) {
        m.upload(GLOW_ATTRS)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun drawShadow() {
        GLES30.glUseProgram(shadowProg)
        val uV = loc(shadowProg, "uV")
        val uP = loc(shadowProg, "uP")
        val uM = loc(shadowProg, "uM")
        val uColor = loc(shadowProg, "uColor")
        val uAlpha = loc(shadowProg, "uAlpha")
        GLES30.glUniformMatrix4fv(uV, 1, false, vMatrix, 0)
        GLES30.glUniformMatrix4fv(uP, 1, false, vPMatrix, 0)
        val disc = Mesh().also { it.build(discVerts(), discNormals(), discIndices()) }
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, 0.03f, 0f)
        Matrix.rotateM(m, 0, -90f, 1f, 0f, 0f) // XY disc → ground XZ
        val s = FloatArray(16)
        Matrix.setIdentityM(s, 0)
        Matrix.scaleM(s, 0, 2.7f, 2.7f, 1f)
        Matrix.multiplyMM(m, 0, m, 0, s, 0)
        GLES30.glUniformMatrix4fv(uM, 1, false, m, 0)
        GLES30.glUniform3f(uColor, 0f, 0f, 0f)
        GLES30.glUniform1f(uAlpha, 0.4f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        disc.upload(GLOW_ATTRS)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, disc.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    // ------------------------------------------------------------- helpers

    private fun loc(prog: Int, name: String): Int = GLES30.glGetUniformLocation(prog, name)

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        Matrix.multiplyMM(o, 0, a, 0, b, 0)
        return o
    }

    private fun scaleM(x: Float, y: Float, z: Float): FloatArray {
        val s = FloatArray(16)
        Matrix.setIdentityM(s, 0)
        Matrix.scaleM(s, 0, x, y, z)
        return s
    }

    // Face panel spans -0.58..0.58 in X, -0.28..0.32 in Y.
    private fun facePanelVerts(): FloatArray = floatArrayOf(
        -0.58f, -0.28f, 0f, 0.58f, -0.28f, 0f, 0.58f, 0.32f, 0f, -0.58f, 0.32f, 0f
    )

    // Unit quad centered at origin (scaled per-feature).
    private fun ndcQuad(): FloatArray = floatArrayOf(
        -0.5f, -0.5f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f
    )

    private fun facePanelNormals(): FloatArray = ndcNormals()
    private fun ndcNormals(): FloatArray = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f
    )

    private fun discVerts(): FloatArray {
        val n = 28
        val v = FloatArray((n + 2) * 3)
        v[0] = 0f; v[1] = 0f; v[2] = 0f
        for (i in 0..n) {
            val a = 2.0 * Math.PI * i / n
            v[(i + 1) * 3] = Math.cos(a).toFloat()
            v[(i + 1) * 3 + 1] = Math.sin(a).toFloat()
        }
        return v
    }

    private fun discNormals(): FloatArray {
        val n = 28
        val a = FloatArray((n + 2) * 3)
        for (i in 0 until (n + 2)) a[i * 3 + 2] = 1f
        return a
    }

    private fun discIndices(): IntArray {
        val n = 28
        val idx = IntArray(n * 3)
        for (i in 0 until n) {
            idx[i * 3] = 0
            idx[i * 3 + 1] = i + 1
            idx[i * 3 + 2] = i + 2
        }
        return idx
    }

    private fun buildShader(vs: String, fs: String): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        GLES30.glShaderSource(v, vs)
        GLES30.glCompileShader(v)
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        GLES30.glShaderSource(f, fs)
        GLES30.glCompileShader(f)
        val prog = GLES30.glCreateProgram()
        GLES30.glBindAttribLocation(prog, 0, "aPos")
        GLES30.glBindAttribLocation(prog, 1, "aNrm")
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return prog
    }

    private companion object {
        val Y = floatArrayOf(0f, 0f, 0f)

        const val SOLID_VS = """
            attribute vec3 aPos;
            attribute vec3 aNrm;
            uniform mat4 uM;
            uniform mat4 uV;
            uniform mat4 uP;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
              vec4 wp = uM * vec4(aPos, 1.0);
              vP = (uV * wp).xyz;
              vec3 worldN = (uM * vec4(aNrm, 0.0)).xyz;
              vN = normalize((uV * vec4(worldN, 0.0)).xyz);
              gl_Position = uP * uV * wp;
            }
        """

        const val SOLID_FS = """
            precision mediump float;
            varying vec3 vN;
            varying vec3 vP;
            uniform vec3 uBase;
            uniform vec3 uEmo;
            uniform float uMix;
            uniform float uEmissive;
            void main() {
              vec3 n = normalize(vN);
              vec3 base = mix(uBase, uEmo, uMix);
              vec3 l = normalize(vec3(0.4, 0.55, 0.75));
              vec3 fill = normalize(vec3(-0.5, -0.2, -0.6));
              vec3 v = normalize(-vP);
              vec3 h = normalize(l + v);
              float diff = max(dot(n, l), 0.0);
              float fillD = max(dot(n, fill), 0.0) * 0.22;
              float spec = pow(max(dot(n, h), 0.0), 42.0) * 0.45;
              float rim = pow(1.0 - max(dot(n, v), 0.0), 2.4) * 0.30;
              vec3 col = base * (0.32 + 0.95 * diff + fillD) + vec3(0.95, 0.98, 1.0) * spec;
              col += vec3(0.25, 0.4, 0.5) * rim;
              col += uEmo * uMix * 0.25;
              col += base * uEmissive * 1.4;
              gl_FragColor = vec4(col, 1.0);
            }
        """

        const val GLOW_VS = """
            attribute vec3 aPos;
            uniform mat4 uM;
            uniform mat4 uV;
            uniform mat4 uP;
            void main() {
              gl_Position = uP * uV * uM * vec4(aPos, 1.0);
            }
        """

        const val GLOW_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
              gl_FragColor = vec4(uColor * 1.25, uAlpha);
            }
        """

        const val SHADOW_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
              gl_FragColor = vec4(uColor, uAlpha);
            }
        """
    }
}

// File-level (nested classes also reach these): CPU→GPU buffer helpers.
private fun toFloatBuffer(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(a); position(0)
    }

private fun toIntBuffer(a: IntArray): IntBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
        put(a); position(0)
    }