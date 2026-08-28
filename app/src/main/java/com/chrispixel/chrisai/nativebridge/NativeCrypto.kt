package com.chrispixel.chrisai.nativebridge

import android.util.Base64

/**
 * Encrypts/decrypts the OpenRouter API key at rest using the native ChaCha20
 * module. The secret key never leaves the .so, so the value stored in
 * SharedPreferences is a nonce + ciphertext blob.
 */
object NativeCrypto {

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val data = plain.toByteArray(Charsets.UTF_8)
        val blob = NativeBridge.encrypt(data) ?: return nativeUnavailableFallback(plain)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        val data = NativeBridge.decrypt(blob) ?: return ""
        return String(data, Charsets.UTF_8)
    }

    private fun nativeUnavailableFallback(plain: String): String =
        "plain:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}