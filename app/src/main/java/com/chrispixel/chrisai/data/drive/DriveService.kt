package com.chrispixel.chrisai.data.drive

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin HTTP surface for the Drive transport. Implementations decide the real
 * stack: [OkHttpDriveClient] in production (v1.0) and fakes in unit tests.
 */
interface DriveHttpClient {
    suspend fun request(
        method: String,
        url: String,
        token: String,
        body: String? = null
    ): DriveHttpResponse
}

/** Minimal HTTP response used by the transport. */
data class DriveHttpResponse(
    val status: Int,
    val body: String
) {
    val isSuccess: Boolean get() = status in 200..299
}

/** Raised by [DriveService] for non-2xx Drive responses. */
class DriveSyncException(
    message: String,
    val status: Int
) : Exception(message)

/** Production transport backed by OkHttp (shared, lean client). */
class OkHttpDriveClient(private val client: OkHttpClient = OkHttpClient()) : DriveHttpClient {

    override suspend fun request(
        method: String,
        url: String,
        token: String,
        body: String?
    ): DriveHttpResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/octet-stream")
            .method(method, body?.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            DriveHttpResponse(
                status = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }
}

/**
 * Google Drive REST v3 wrapper (v1.0).
 *
 * Owns ONLY the cloud layout and the REST mapping; it knows nothing about
 * memory/conversations/settings. [ensureLayout]/[listAll]/[download]/[put]/
 * [delete] work on logical paths like "Memory/Memory.json", mirroring the tree
 * `ChrisAI/{Conversations,Memory,Settings,Data}`. The access token is supplied
 * per call (never stored) and scopes to drive.file via the account picker.
 */
class DriveService(private val http: DriveHttpClient) {

    private val folderMime = "application/vnd.google-apps.folder"
    private val folderIdCache = HashMap<String, String>()

    companion object {
        const val ROOT_NAME = "ChrisAI"
        const val MAX_LIST_DEPTH = 8
    }

    /** Returns the id of the top-level ChrisAI folder, creating it if needed. */
    suspend fun ensureLayout(token: String): String {
        folderIdCache.clear()
        val existing = searchFolder(token, parentId = null, name = ROOT_NAME)
        val root = existing ?: createFolder(token, ROOT_NAME, parentId = null)
        folderIdCache[ROOT_NAME] = root
        return root
    }

    /** Full recursive listing under the ChrisAI root (relative logical paths). */
    suspend fun listAll(token: String): List<RemoteRef> {
        val root = ensureLayout(token)
        val out = ArrayList<RemoteRef>()
        collect(token, root, prefix = "", depth = 0, out)
        // Legacy v0.8 backups lived at the Drive root (no ChrisAI folder yet);
        // surface those as top-level paths so migrations can pick them up.
        for (child in children(token, null)) {
            if (!child.isFolder) {
                out.add(RemoteRef(path = child.name, fileId = child.id, sizeBytes = child.size, md5 = child.md5))
            }
        }
        return out
    }

    /** Downloads a file's UTF-8 content (raw media), or null when missing. */
    suspend fun download(token: String, path: String): String? {
        val segments = segmentsOf(path) ?: return null
        val parent = ensureFolderPath(token, segments.dropLast(1)) ?: return null
        val fileId = findFileId(token, parent, segments.last()) ?: return null
        return downloadById(token, fileId)
    }

    /** Downloads an already-known remote file by its [RemoteRef] (layout moves). */
    suspend fun downloadRef(token: String, ref: RemoteRef): String? {
        if (ref.fileId.isBlank()) return null
        return try {
            downloadById(token, ref.fileId)
        } catch (_: DriveSyncException) {
            null
        }
    }

    /** Deletes an already-known remote file by its [RemoteRef] (layout moves). */
    suspend fun deleteRef(token: String, ref: RemoteRef): Boolean {
        if (ref.fileId.isBlank()) return false
        return try {
            http.request("DELETE", fileUrl(ref.fileId), token).isSuccess
        } catch (_: DriveSyncException) {
            false
        }
    }

    /** Creates or overwrites the file at [path] with [content]. Returns true on success. */
    suspend fun put(token: String, path: String, content: String): Boolean {
        val segments = segmentsOf(path) ?: return false
        val parent = ensureFolderPath(token, segments.dropLast(1)) ?: return false
        val name = segments.last()
        val existing = findFileId(token, parent, name)
        val fileId = uploadText(token, existing, content)
        if (existing == null) attachNamed(token, fileId, name, parent)
        return fileId.isNotBlank()
    }

    /** Deletes the file at [path]; false when it does not exist. */
    suspend fun delete(token: String, path: String): Boolean {
        val segments = segmentsOf(path) ?: return false
        val parent = ensureFolderPath(token, segments.dropLast(1)) ?: return false
        val fileId = findFileId(token, parent, segments.last()) ?: return false
        val response = http.request("DELETE", fileUrl(fileId), token)
        return response.isSuccess
    }

    // ---------------------------------------------------------------- internals

    private data class ChildRef(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val md5: String
    )

    private suspend fun collect(
        token: String,
        folderId: String,
        prefix: String,
        depth: Int,
        out: MutableList<RemoteRef>
    ) {
        if (depth > MAX_LIST_DEPTH) return
        val childrenList = children(token, folderId)
        for (child in childrenList) {
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isFolder) {
                folderIdCache[relative] = child.id
                collect(token, child.id, relative, depth + 1, out)
            } else {
                out.add(RemoteRef(path = relative, fileId = child.id, sizeBytes = child.size, md5 = child.md5))
            }
        }
    }

    private suspend fun children(token: String, folderId: String?): List<ChildRef> {
        val q = if (folderId == null) "'root' in parents and trashed=false" else "'$folderId' in parents and trashed=false"
        val url = filesUrl(q = q, fields = "files(id,name,mimeType,size,md5Checksum)")
        val response = http.request("GET", url, token)
        if (!response.isSuccess) throw DriveSyncException("loading children of $folderId", response.status)
        val files = JSONObject(response.body).optJSONArray("files") ?: return emptyList()
        val out = ArrayList<ChildRef>()
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            out.add(
                ChildRef(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    isFolder = o.optString("mimeType") == folderMime,
                    size = o.optLong("size", 0L),
                    md5 = o.optString("md5Checksum", "")
                )
            )
        }
        return out
    }

    private suspend fun searchFolder(token: String, parentId: String?, name: String): String? {
        val parentClause = if (parentId == null) "'root' in parents" else "'$parentId' in parents"
        val q = "$parentClause and name='$name' and mimeType='$folderMime' and trashed=false"
        val response = http.request("GET", filesUrl(q = q, fields = "files(id)"), token)
        if (!response.isSuccess) throw DriveSyncException("searching folder $name", response.status)
        val files = JSONObject(response.body).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() } else null
    }

    private suspend fun createFolder(token: String, name: String, parentId: String?): String {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", folderMime)
            .put("parents", JSONArray(listOfNotNull(parentId)))
            .toString()
        val response = http.request("POST", filesApi(), token, body)
        if (!response.isSuccess) throw DriveSyncException("creating folder $name", response.status)
        return JSONObject(response.body).optString("id")
    }

    /** Ensures all folder segments of [segments] exist; returns the last folder id. */
    private suspend fun ensureFolderPath(token: String, segments: List<String>): String? {
        var parent = folderIdCache[ROOT_NAME]
            ?: searchFolder(token, parentId = null, name = ROOT_NAME)
            ?: createFolder(token, ROOT_NAME, parentId = null)
        folderIdCache[ROOT_NAME] = parent
        for (name in segments) {
            val cacheKey = "$parent/$name"
            val id = folderIdCache[cacheKey]
                ?: searchFolder(token, parentId = parent, name = name)
                ?: createFolder(token, name, parentId = parent)
            folderIdCache[cacheKey] = id
            parent = id
        }
        return parent
    }

    private suspend fun findFileId(token: String, folderId: String, name: String): String? =
        children(token, folderId).firstOrNull { !it.isFolder && it.name == name }?.id

    private suspend fun downloadById(token: String, fileId: String): String {
        val response = http.request("GET", "${filesApi()}/$fileId?alt=media", token)
        if (!response.isSuccess) throw DriveSyncException("downloading $fileId", response.status)
        return response.body
    }

    private suspend fun uploadText(token: String, fileId: String?, content: String): String {
        val url = if (fileId != null) {
            "${uploadApi()}/$fileId?uploadType=media"
        } else {
            "${uploadApi()}?uploadType=media"
        }
        val response = http.request(if (fileId != null) "PUT" else "POST", url, token, content)
        if (!response.isSuccess) throw DriveSyncException("uploading $fileId", response.status)
        return JSONObject(response.body).optString("id", fileId.orEmpty())
    }

    private suspend fun attachNamed(token: String, fileId: String, name: String, parentId: String) {
        val body = JSONObject()
            .put("name", name)
            .put("parents", JSONArray(listOf(parentId)))
            .toString()
        val response = http.request("PATCH", fileUrl(fileId), token, body)
        if (!response.isSuccess) throw DriveSyncException("naming $fileId", response.status)
    }

    private fun segmentsOf(path: String): List<String>? {
        val segments = path.split('/').filter(String::isNotBlank)
        return segments.takeIf { it.size >= 2 }
    }

    private fun filesApi(): String = "https://www.googleapis.com/drive/v3/files"

    private fun uploadApi(): String = "https://www.googleapis.com/upload/drive/v3/files"

    private fun fileUrl(fileId: String): String = "${filesApi()}/$fileId"

    private fun filesUrl(q: String?, fields: String): String {
        val params = mutableListOf("fields=${URLEncoder.encode(fields, "UTF-8")}")
        if (q != null) params.add("q=${URLEncoder.encode(q, "UTF-8")}")
        return "${filesApi()}?${params.joinToString("&")}"
    }
}

/** Drive file reference returned by [DriveService.listAll]. */
data class RemoteRef(
    val path: String,
    val fileId: String,
    val sizeBytes: Long,
    val md5: String = ""
)