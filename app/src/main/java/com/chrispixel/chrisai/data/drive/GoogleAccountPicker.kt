package com.chrispixel.chrisai.data.drive

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.0 "Continuar con Google" without play-services: picks a Google account
 * from the device (AccountManager) and requests an OAuth token scoped to
 * drive.file (only files created by the app), which is the minimal privilege
 * required for the ChrisAI backup tree.
 */
object GoogleAccountPicker {

    /** drive.file scope: read/write only files/folders created by this app. */
    const val SCOPE_DRIVE_FILE = "oauth2:https://www.googleapis.com/auth/drive.file"

    private const val ACCOUNT_TYPE = "com.google"

    /** Runtime GET_ACCOUNTS must be granted to see/authorize accounts. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Emails of the Google accounts readable on the device (empty without permission). */
    fun accounts(context: Context): List<String> {
        if (!hasPermission(context)) return emptyList()
        return try {
            AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE)
                .map { it.name }
                .filter { it.isNotBlank() }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Requests a fresh OAuth token for [email] (drive.file). Null on any failure. */
    suspend fun requestToken(context: Context, email: String): String? = withContext(Dispatchers.IO) {
        try {
            val am = AccountManager.get(context)
            val account = am.getAccountsByType(ACCOUNT_TYPE).firstOrNull { account -> account.name == email }
                ?: return@withContext null
            val future = am.getAuthToken(account, SCOPE_DRIVE_FILE, true, null, null)
            val bundle = future.result
            val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
            token?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Invalidates a token (call after 401/403 so the next request re-authorizes). */
    suspend fun invalidateToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        try {
            AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE, token)
        } catch (_: Exception) {
            // best effort
        }
    }
}