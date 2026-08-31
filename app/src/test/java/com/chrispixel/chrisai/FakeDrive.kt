package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.drive.DriveHttpClient
import com.chrispixel.chrisai.data.drive.DriveHttpResponse
import com.chrispixel.chrisai.data.drive.SyncFile
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory simulation of the Drive REST v3 surface used by [DriveService]:
 * folders/files, parents, media upload, alt=media download, patching parents,
 * deleting and the file listing queries. Deterministic for unit tests.
 */
internal class FakeDrive : DriveHttpClient {

    private class F(
        val id: String,
        var name: String,
        val mime: String,
        var parentId: String?,
        var content: String
    ) {
        val md5: String get() = SyncFile.md5(content)
    }

    private val FOLDER_MIME = "application/vnd.google-apps.folder"
    private val files = mutableListOf<F>()
    private var counter = 0
    var totalCalls = 0
        private set

    private fun nextId(): String = "id-${++counter}"

    override suspend fun request(
        method: String,
        url: String,
        token: String,
        body: String?
    ): DriveHttpResponse {
        totalCalls++
        return try {
            dispatch(method, url, body)
        } catch (e: Exception) {
            DriveHttpResponse(500, e.message.orEmpty())
        }
    }

    private fun dispatch(method: String, url: String, body: String?): DriveHttpResponse {
        val qIndex = url.indexOf('?')
        val path = if (qIndex >= 0) url.substring(0, qIndex) else url
        val query = if (qIndex >= 0) url.substring(qIndex + 1) else ""
        val params = query.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()

        return when {
            path == API_FILES && method == "GET" -> list(params)
            path == API_FILES && method == "POST" -> create(body)
            path.startsWith("$API_FILES/") && method == "GET" && "alt" in params ->
                DriveHttpResponse(200, download(path)?.takeIf { params["alt"] == "media" } ?: "")
            path.startsWith("$API_FILES/") && method == "PATCH" -> patch(path, body)
            path.startsWith("$API_FILES/") && method == "DELETE" -> delete(path)
            path.startsWith(API_UPLOAD) && method in listOf("POST", "PUT") -> upload(url, method, body)
            else -> DriveHttpResponse(404, "unknown $method $url")
        }
    }

    private fun list(params: Map<String, String>): DriveHttpResponse {
        val q = params["q"] ?: ""
        val parents = Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)
        val name = Regex("name='([^']+)'").find(q)?.groupValues?.get(1)
        val folderOnly = "mimeType='$FOLDER_MIME'" in q

        val matched = files.filter { f ->
            val parentOk = when (parents) {
                null -> true
                "root" -> f.parentId == null
                else -> f.parentId == parents
            }
            parentOk && (name == null || f.name == name) && (!folderOnly || f.mime == FOLDER_MIME)
        }

        val fields = params["fields"] ?: ""
        val arr = JSONArray()
        matched.forEach { f ->
            val o = JSONObject()
                .put("id", f.id)
                .put("name", f.name)
                .put("mimeType", f.mime)
            if ("size" in fields) o.put("size", f.content.length)
            if ("md5Checksum" in fields && f.mime != FOLDER_MIME) o.put("md5Checksum", f.md5)
            arr.put(o)
        }
        return DriveHttpResponse(200, JSONObject().put("files", arr).toString())
    }

    private fun create(body: String?): DriveHttpResponse {
        val doc = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DriveHttpResponse(400, "no body")
        val name = doc.optString("name")
        val mime = doc.optString("mimeType", "application/octet-stream")
        val parents = doc.optJSONArray("parents")
        val parent = if (parents != null && parents.length() > 0) parents.getString(0) else null
        val f = F(nextId(), name, mime, parent, "")
        files.add(f)
        return DriveHttpResponse(200, JSONObject().put("id", f.id).toString())
    }

    private fun patch(path: String, body: String?): DriveHttpResponse {
        val id = path.substringAfterLast('/')
        val f = files.firstOrNull { it.id == id } ?: return DriveHttpResponse(404, "missing $id")
        val doc = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (doc != null && doc.has("name")) f.name = doc.optString("name")
        if (doc != null && doc.has("parents")) {
            val parents = doc.optJSONArray("parents")
            f.parentId = if (parents != null && parents.length() > 0) parents.getString(0) else null
        }
        return DriveHttpResponse(200, JSONObject().put("id", f.id).toString())
    }

    private fun delete(path: String): DriveHttpResponse {
        val id = path.substringAfterLast('/')
        files.removeAll { it.id == id }
        return DriveHttpResponse(200, "")
    }

    private fun download(path: String): String? =
        files.firstOrNull { it.id == path.substringAfterLast('/') }?.content

    private fun upload(url: String, method: String, body: String?): DriveHttpResponse {
        if (method == "PUT" || "/files/" in "$url/?".substringBefore("?uploadType")) {
            // update existing file by id
            val id = url.substringAfter("/files/").substringBefore("?")
            val f = files.firstOrNull { it.id == id }
            if (f == null) return DriveHttpResponse(404, "missing $id")
            f.content = body.orEmpty()
            return DriveHttpResponse(200, JSONObject().put("id", f.id).toString())
        }
        // create new media file (parent attached with a later PATCH)
        val f = F(nextId(), "untitled", "application/octet-stream", null, body.orEmpty())
        files.add(f)
        return DriveHttpResponse(200, JSONObject().put("id", f.id).toString())
    }

    // ----------------------------- test helpers (read the fake's state)

    fun hasFile(path: String): Boolean = files.any { it.name == path.substringAfterLast('/') }

    /** Seeds a root-level file exactly as v0.8 backups uploaded Memory.json. */
    fun seedRoot(name: String, content: String) {
        files.add(F(nextId(), name, "application/octet-stream", null, content))
    }

    fun find(path: String): com.chrispixel.chrisai.data.drive.SyncFile? {
        val name = path.substringAfterLast('/')
        val f = files.firstOrNull { it.name == name } ?: return null
        return com.chrispixel.chrisai.data.drive.SyncFile(path, f.content)
    }

    private companion object {
        val API_FILES = "https://www.googleapis.com/drive/v3/files"
        val API_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }
}