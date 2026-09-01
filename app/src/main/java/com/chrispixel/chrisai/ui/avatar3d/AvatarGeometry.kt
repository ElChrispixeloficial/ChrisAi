package com.chrispixel.chrisai.ui.avatar3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.1 real-3D avatar geometry.
 *
 * All bodies are smooth, rounded primitives (ellipsoids, tapered capsules,
 * tori for the black articulation rings): the android reads as one continuous
 * rounded silhouette instead of a cube/marionette look. Normals are analytic,
 * so lighting is genuinely volumetric.
 *
 * Coordinate layout: +Y up, +Z towards the viewer's "front" of the head.
 */
internal class MeshData(val vertices: FloatArray, val normals: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = vertices.size / 3
}

internal object AvatarGeometry {

    /** Sphere scaled per axis; normals exact for an ellipsoid. */
    fun ellipsoid(rx: Float, ry: Float, rz: Float, radial: Int = 20, vertical: Int = 12): MeshData {
        val rows = vertical + 1
        val verts = FloatArray(rows * radial * 3)
        val norms = FloatArray(rows * radial * 3)
        val idx = IntArray(vertical * radial * 6)
        var vi = 0
        var ii = 0
        for (row in 0..vertical) {
            val phi = PI * row.toDouble() / vertical
            val y = cos(phi).toFloat()
            val sr = sin(phi).toFloat()
            for (col in 0 until radial) {
                val the = 2.0 * PI * col.toDouble() / radial
                val x = (sr * cos(the)).toFloat()
                val z = (sr * sin(the)).toFloat()
                verts[vi] = x * rx
                verts[vi + 1] = y * ry
                verts[vi + 2] = z * rz
                val inv = 1f / sqrt((x / rx) * (x / rx) + (y / ry) * (y / ry) + (z / rz) * (z / rz))
                norms[vi] = x / (rx * rx) * inv
                norms[vi + 1] = y / (ry * ry) * inv
                norms[vi + 2] = z / (rz * rz) * inv
                vi += 3
            }
        }
        for (row in 0 until vertical) {
            for (col in 0 until radial) {
                val a = row * radial + col
                val b = a + radial
                val a2 = row * radial + ((col + 1) % radial)
                val b2 = (row + 1) * radial + ((col + 1) % radial)
                idx[ii++] = a; idx[ii++] = b; idx[ii++] = b2
                idx[ii++] = a; idx[ii++] = b2; idx[ii++] = a2
            }
        }
        return MeshData(verts, norms, idx)
    }

    fun sphere(r: Float): MeshData = ellipsoid(r, r, r)

    /**
     * Tapered capsule along Y with hemispherical rounded ends.
     * [r0] south (bottom) radius, [r1] north (top) radius. Total length is
     * roughly 2*(halfLen) plus the cap bulges at each end.
     */
    fun capsule(r0: Float, r1: Float, halfLen: Float, radial: Int = 16, vertical: Int = 10): MeshData {
        val rows = vertical + 1
        val verts = FloatArray(rows * radial * 3)
        val norms = FloatArray(rows * radial * 3)
        val idx = IntArray(vertical * radial * 6)
        var vi = 0
        var ii = 0
        for (row in 0..vertical) {
            val phi = PI * row.toDouble() / vertical // 0 south tip .. PI north tip
            val cosP = cos(phi).toFloat()
            val sinP = sin(phi).toFloat()
            val taper = (1f - cosP) * 0.5f // 0 at south, 1 at north
            val capR = r0 + (r1 - r0) * taper
            val ringR = capR * sinP
            val capCenter = if (cosP >= 0f) halfLen else -halfLen
            val y = capCenter + capR * cosP
            for (col in 0 until radial) {
                val the = 2.0 * PI * col.toDouble() / radial
                val x = ringR * cos(the).toFloat()
                val z = ringR * sin(the).toFloat()
                verts[vi] = x; verts[vi + 1] = y; verts[vi + 2] = z
                norms[vi] = sinP * cos(the).toFloat()
                norms[vi + 1] = -cosP
                norms[vi + 2] = sinP * sin(the).toFloat()
                vi += 3
            }
        }
        for (row in 0 until vertical) {
            for (col in 0 until radial) {
                val a = row * radial + col
                val b = a + radial
                val a2 = row * radial + ((col + 1) % radial)
                val b2 = (row + 1) * radial + ((col + 1) % radial)
                idx[ii++] = a; idx[ii++] = b; idx[ii++] = b2
                idx[ii++] = a; idx[ii++] = b2; idx[ii++] = a2
            }
        }
        return MeshData(verts, norms, idx)
    }

    /** Torus lying in the XZ plane (ring around Y) — limb articulation bands. */
    fun torusY(ringRadius: Float, tubeRadius: Float, ringSegments: Int = 18, tubeSegments: Int = 7): MeshData {
        val verts = FloatArray(ringSegments * tubeSegments * 3)
        val norms = FloatArray(ringSegments * tubeSegments * 3)
        val idx = IntArray(ringSegments * tubeSegments * 6)
        var vi = 0
        var ii = 0
        for (i in 0 until ringSegments) {
            val a = 2.0 * PI * i.toDouble() / ringSegments
            val cx = cos(a).toFloat() * ringRadius
            val cz = sin(a).toFloat() * ringRadius
            for (j in 0 until tubeSegments) {
                val b = 2.0 * PI * j.toDouble() / tubeSegments
                val nx = cos(a).toFloat() * cos(b).toFloat()
                val nz = sin(a).toFloat() * cos(b).toFloat()
                val ny = sin(b).toFloat()
                verts[vi] = cx + nx * tubeRadius
                verts[vi + 1] = ny * tubeRadius
                verts[vi + 2] = cz + nz * tubeRadius
                norms[vi] = nx; norms[vi + 1] = ny; norms[vi + 2] = nz
                vi += 3
            }
        }
        for (i in 0 until ringSegments) {
            val i2 = (i + 1) % ringSegments
            for (j in 0 until tubeSegments) {
                val j2 = (j + 1) % tubeSegments
                val a = i * tubeSegments + j
                val b = i * tubeSegments + j2
                val c = i2 * tubeSegments + j2
                val d = i2 * tubeSegments + j
                idx[ii++] = a; idx[ii++] = b; idx[ii++] = c
                idx[ii++] = a; idx[ii++] = c; idx[ii++] = d
            }
        }
        return MeshData(verts, norms, idx)
    }

    /** Flat quad on the XY plane facing +Z — emissive face screen content. */
    fun flatQuad(w: Float, h: Float): MeshData {
        val hw = w / 2f
        val hh = h / 2f
        return MeshData(
            floatArrayOf(-hw, -hh, 0f, hw, -hh, 0f, hw, hh, 0f, -hw, hh, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
            intArrayOf(0, 1, 2, 0, 2, 3)
        )
    }
}