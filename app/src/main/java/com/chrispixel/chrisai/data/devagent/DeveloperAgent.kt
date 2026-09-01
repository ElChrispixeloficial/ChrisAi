package com.chrispixel.chrisai.data.devagent

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** A single file or folder exposed by the Developer Mode local agent (SAF). */
data class DevAgentFile(
    val name: String,
    val uri: String,
    val sizeBytes: Long,
    val mimeType: String,
    val isDirectory: Boolean
) {
    val displaySize: String
        get() = if (isDirectory) "" else formatSize(sizeBytes)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var u = 0
    while (value >= 1024 && u < units.size - 1) {
        value /= 1024.0
        u++
    }
    return "%.1f %s".format(value, units[u])
}

/** Safety cap: files larger than this are never read into the chat as text. */
private const val MAX_READ_BYTES = 64_000L

/**
 * v1.1 Developer Mode local agent.
 *
 * Lets the user pick a folder via the Storage Access Framework (SAF). ChrisAI
 * persists read access to that folder and can list its files and attach the
 * readable text of a file into the chat as context for the model.
 */
class DeveloperAgent(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Keeps read access granted by the system across restarts. */
    fun persistFolderAccess(uri: Uri) {
        try {
            // noinspection WrongConstant
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Not every provider supports persistent permission; degrade gracefully.
        }
    }

    /** Drops the persisted read access for a folder (used when clearing the agent). */
    fun releaseFolderAccess(uri: Uri) {
        try {
            // noinspection WrongConstant
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // ignore
        }
    }

    /** Lists the immediate children of a folder picked via SAF. */
    fun listFiles(folderUri: Uri): List<DevAgentFile> {
        val out = ArrayList<DevAgentFile>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, DocumentsContract.getTreeDocumentId(folderUri)
            )
            resolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val flagsCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getString(idCol) else null
                    if (id == null) continue
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else "archivo"
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val flags = if (flagsCol >= 0) cursor.getInt(flagsCol) else 0
                    // FLAG_DIRECTORY == 0x1 (stub may omit the named constant)
                    val isDir = (flags and 1) != 0
                    out.add(
                        DevAgentFile(
                            name = name ?: "archivo",
                            uri = fileUri.toString(),
                            sizeBytes = size,
                            mimeType = mime,
                            isDirectory = isDir
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // ignore — return what we could read
        }
        return out.sortedWith(
            compareByDescending<DevAgentFile> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    /** Whether a file's content can (and should) be read as plain text for the chat. */
    fun isReadableText(file: DevAgentFile): Boolean =
        !file.isDirectory &&
            file.sizeBytes <= MAX_READ_BYTES &&
            (file.mimeType.startsWith("text/") ||
                file.name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS)

    /** Reads up to [MAX_READ_BYTES] of a document's content as UTF-8 text, or null. */
    fun readTextFile(uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val all = input.readBytes()
                String(all.copyOfRange(0, minOf(all.size, MAX_READ_BYTES.toInt())), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "kt",
            "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "sql", "yml", "yaml",
            "toml", "ini", "cfg", "properties", "log", "csv", "tsv", "sh", "bat", "gradle"
        )
    }
}
