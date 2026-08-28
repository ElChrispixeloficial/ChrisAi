package com.chrispixel.chrisai.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chrispixel.chrisai.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.5 updater: checks the OFFICIAL GitHub Releases of the ChrisAi repo, asks
 * the user before any download, validates the APK (and its checksum when
 * provided) and hands the install to the standard Android installer
 * (FileProvider + ACTION_VIEW). Never root installs, never silent updates.
 */
class UpdaterRepository(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repoUrl = "${BuildConfig.UPDATE_BASE_URL}/repos/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}"

    /** Latest release newer than [currentVersion], or null when up to date. */
    suspend fun checkForUpdates(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val latest = fetchLatestReleaseJson() ?: return@withContext null
        val tag = latest.optString("tag_name").orEmpty()
        if (!SemanticVersion.isNewer(currentVersion, tag)) return@withContext null

        val assets = latest.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var checksumUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val url = asset.optString("browser_download_url").orEmpty()
            val name = asset.optString("name").orEmpty()
            when {
                url.endsWith(".apk", ignoreCase = true) && apkUrl == null -> apkUrl = url
                name.endsWith(".sha256", ignoreCase = true) && checksumUrl == null -> checksumUrl = url
            }
        }
        if (apkUrl == null) return@withContext null

        val checksum = checksumUrl?.let { fetchChecksum(it) }
            ?: extractChecksumFromBody(latest.optString("body").orEmpty())

        ReleaseInfo(
            tagName = tag,
            name = latest.optString("name").orEmpty(),
            body = latest.optString("body").orEmpty(),
            publishedAt = latest.optString("published_at").takeIf { it.isNotBlank() },
            apkUrl = apkUrl,
            checksumSha256 = checksum
        )
    }

    /** Downloads the release APK into cacheDir/updates with progress ∈ [0,1]. */
    suspend fun downloadApk(release: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val apkUrl = release.apkUrl ?: throw IOException("La release no incluye un APK")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, "chrisai_$safeTag.apk")
            if (target.exists()) target.delete()

            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "ChrisAI")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Descarga falló (HTTP ${response.code})")
                val body = response.body ?: throw IOException("Descarga falló: respuesta vacía")
                val total = body.contentLength()
                val input = body.byteStream()
                val output = FileOutputStream(target)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                } finally {
                    output.flush()
                    output.close()
                    input.close()
                }
            }

            validateApk(target)

            release.checksumSha256?.let { expected ->
                val actual = sha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    throw IOException("El checksum del APK no coincide con el de la release")
                }
            }
            target
        }

    /** Standard install: FileProvider URI + ACTION_VIEW (user confirms). */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun fetchLatestReleaseJson(): JSONObject? {
        val request = Request.Builder()
            .url("$repoUrl/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ChrisAI")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return try {
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("GitHub devolvió HTTP ${response.code}")
                else -> JSONObject(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw e
        } finally {
            response.close()
        }
    }

    private fun fetchChecksum(url: String): String? {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "ChrisAI").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string().orEmpty()
                Regex("[0-9a-fA-F]{64}").find(text)?.value
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractChecksumFromBody(body: String): String? {
        if (!body.contains("sha", ignoreCase = true)) return null
        return Regex("[0-9a-fA-F]{64}").find(body)?.value
    }

    private fun validateApk(file: File) {
        if (!file.exists() || file.length() < 1_000_000) {
            file.delete()
            throw IOException("El archivo descargado no es un APK válido")
        }
        val signature = ByteArray(4)
        file.inputStream().use { input ->
            val read = input.read(signature)
            if (read < 4 || signature[0] != 'P'.code.toByte() || signature[1] != 'K'.code.toByte() ||
                signature[2] != 0x03.toByte() || signature[3] != 0x04.toByte()
            ) {
                file.delete()
                throw IOException("El archivo descargado no es un APK válido")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}