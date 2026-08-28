package com.chrispixel.chrisai.nativebridge

import android.graphics.Bitmap

/**
 * Bridge to the native `chriscore` module:
 *  - ChaCha20 encrypt/decrypt (key material lives only inside the .so).
 *  - SSE delta extraction for the streaming hot path.
 *  - Native procedural rendering (aurora backdrop).
 */
object NativeBridge {

    private val loaded: Boolean = try {
        System.loadLibrary("chriscore")
        true
    } catch (e: Throwable) {
        false
    }

    val available: Boolean get() = loaded

    /** Returns a non-null result only when the delta is non-empty. */
    fun extractContentDelta(line: String): String? {
        if (!loaded || line.isEmpty()) return null
        return try {
            val bytes = nativeExtractDelta(line) ?: return null
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    fun encrypt(data: ByteArray): ByteArray? {
        if (!loaded) return null
        return try {
            nativeEncrypt(data)
        } catch (e: Throwable) {
            null
        }
    }

    fun decrypt(data: ByteArray): ByteArray? {
        if (!loaded) return null
        return try {
            nativeDecrypt(data)
        } catch (e: Throwable) {
            null
        }
    }

    fun fillAurora(bitmap: Bitmap, width: Int, height: Int, seed: Long) {
        if (!loaded) return
        try {
            nativeFillAurora(bitmap, width, height, seed)
        } catch (e: Throwable) {
            // Non fatal; the UI simply keeps the plain background.
        }
    }

    private external fun nativeEncrypt(input: ByteArray): ByteArray?
    private external fun nativeDecrypt(input: ByteArray): ByteArray?
    private external fun nativeExtractDelta(line: String): ByteArray?
    private external fun nativeFillAurora(bitmap: Bitmap, width: Int, height: Int, seed: Long)
}