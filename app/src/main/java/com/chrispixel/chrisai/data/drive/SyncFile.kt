package com.chrispixel.chrisai.data.drive

/**
 * One logical, syncable file under the ChrisAI/ tree (v1.0).
 *
 * Paths follow the cloud layout (see [CloudCodec]): memories, conversations,
 * settings and device data all live under parallel folder segments, which keeps
 * the plan engine and transports free of persistence details.
 */
data class SyncFile(
    val path: String,
    val content: String
) {
    /** Content hash used as the sync fingerprint (MD5, mirroring Drive). */
    fun fingerprint(): String = md5(content)

    companion object {
        fun md5(text: String): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Result of one sync pass (see [SyncManager]). */
data class SyncResult(
    val uploaded: List<String>,
    val downloaded: List<String>,
    val deletedRemotes: List<String>,
    val deletedLocals: List<String>,
    val conflictsKeptLocal: List<String>,
    val errors: List<String>
) {
    val changed: Boolean get() = uploaded.isNotEmpty() ||
        downloaded.isNotEmpty() ||
        deletedRemotes.isNotEmpty() ||
        deletedLocals.isNotEmpty() ||
        conflictsKeptLocal.isNotEmpty()
}

/** Shows when the remote copy loses (conflict), so the UI can inform the user. */
data class SyncConflictNote(val path: String)