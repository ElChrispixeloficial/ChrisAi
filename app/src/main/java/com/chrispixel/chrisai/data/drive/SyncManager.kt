package com.chrispixel.chrisai.data.drive

/**
 * Local side of the sync: produces the logical files to upload and consumes
 * files downloaded from Drive. Implemented by [LocalSyncStore] on Android and
 * by fakes in unit tests, keeping [SyncManager] pure-JVM testable.
 */
interface CloudFileStore {
    /** All local, syncable files (logical paths under ChrisAI/). */
    suspend fun localFiles(): List<SyncFile>

    /** Applies downloaded/merged files into the local stores. */
    suspend fun applyRemote(files: List<SyncFile>)

    /** Deletes a local file (e.g. a conversation removed from the cloud). */
    suspend fun deleteLocal(path: String)

    /**
     * Paths the user deleted on this device since the last sync; the cloud
     * copy of those paths is removed on the next sync. Default: none.
     */
    suspend fun deletedPaths(): List<String> = emptyList()
}

/**
 * v1.0 real Drive synchronization.
 *
 * Embarks the proven [DriveSyncEngine] planner with an actual [DriveService]
 * transport and a [CloudFileStore]: builds both trees, plans, then applies
 * uploads/downloads/deletes/conflicts. Never overwrites silently: conflicts are
 * resolved by strategy (merging Memory.json when the remote is newer).
 */
class SyncManager(
    private val service: DriveService,
    private val files: CloudFileStore
) {

    suspend fun sync(token: String): SyncResult {
        val result = SyncResultData()
        val localByPath = LinkedHashMap<String, SyncFile>()
        try {
            files.localFiles().forEach { localByPath[it.path] = it }
        } catch (e: Exception) {
            result.errors.add("leer archivos locales: ${e.message.orEmpty()}")
        }

        // Tracks deletions done on this device so a sync removes them remotely
        // instead of silently downloading them back.
        val tombstoned = try {
            files.deletedPaths().toSet()
        } catch (e: Exception) {
            result.errors.add("leer borrados locales: ${e.message.orEmpty()}")
            emptySet()
        }

        // Any remote read failure must not abort the whole sync.
        var remoteRefs: List<RemoteRef> = emptyList()
        try {
            remoteRefs = service.listAll(token)
        } catch (e: Exception) {
            result.errors.add("lista remota: ${e.message.orEmpty()}")
        }

        // v0.8 backups stored Memory.json at the root; migrate the cloud layout
        // into the v1.0 folders so pre-1.0 trees keep syncing forward.
        migrateLegacyRoots(token, remoteRefs, result)
        try {
            remoteRefs = service.listAll(token)
        } catch (e: Exception) {
            result.errors.add("lista remota tras migracion: ${e.message.orEmpty()}")
        }

        val remoteEntries = remoteRefs.associate { ref ->
            val logical = when (ref.path) {
                CloudCodec.MEMORY_FILE -> CloudCodec.memoryPath()
                CloudCodec.MEMORY_TXT -> CloudCodec.memoryTxtPath()
                else -> ref.path
            }
            logical to CloudEntry(logical, ref.md5, sizeBytes = ref.sizeBytes)
        }

        val plan = DriveSyncEngine.plan(
            localByPath.mapValues { (path, file) -> CloudEntry(path, file.fingerprint(), sizeBytes = file.content.length.toLong()) },
            remoteEntries
        )

        val toApply = ArrayList<SyncFile>()

        suspend fun doUpload(path: String) {
            val file = localByPath[path] ?: return
            try {
                if (service.put(token, path, file.content)) result.uploaded.add(path)
            } catch (e: Exception) {
                result.errors.add("subida $path: ${e.message.orEmpty()}")
            }
        }

        suspend fun doDownload(path: String) {
            try {
                val content = service.download(token, path)
                if (content != null) toApply.add(SyncFile(path, content))
            } catch (e: Exception) {
                result.errors.add("descarga $path: ${e.message.orEmpty()}")
            }
        }

        for (action in plan) {
            when (action) {
                is SyncAction.Upload -> doUpload(action.path)
                is SyncAction.Download -> doDownload(action.path)
                is SyncAction.DeleteRemote -> try {
                    if (service.delete(token, action.path)) result.deletedRemotes.add(action.path)
                } catch (e: Exception) {
                    result.errors.add("borrado remoto ${action.path}: ${e.message.orEmpty()}")
                }
                is SyncAction.DeleteLocal -> {
                    try {
                        files.deleteLocal(action.path)
                        result.deletedLocals.add(action.path)
                    } catch (e: Exception) {
                        result.errors.add("borrado local ${action.path}: ${e.message.orEmpty()}")
                    }
                }
                is SyncAction.Pending -> Unit // queued for when connectivity returns
                is SyncAction.Conflict -> when (DriveSyncEngine.resolve(action)) {
                    is SyncAction.Upload -> {
                        result.conflictsKeptLocal.add(action.path)
                        doUpload(action.path)
                    }
                    is SyncAction.Download -> doDownload(action.path)
                    else -> Unit
                }
            }
        }

        // Local deletions must also remove the copy in the cloud.
        for (path in tombstoned.sorted()) {
            if (path in remoteEntries) {
                try {
                    if (service.delete(token, path)) {
                        result.deletedRemotes.add(path)
                        result.deletedLocals.add(path)
                    }
                } catch (e: Exception) {
                    result.errors.add("borrado remoto $path: ${e.message.orEmpty()}")
                }
            }
        }

        if (toApply.isNotEmpty()) {
            try {
                files.applyRemote(toApply)
                result.downloaded.addAll(toApply.map { it.path })
            } catch (e: Exception) {
                result.errors.add("aplicar cambios: ${e.message.orEmpty()}")
            }
        }

        return result.build()
    }

    /**
     * v0.8 uploaded Memory.{json,txt} at the Drive root; move those into the
     * v1.0 folders (download by id, write the foldered copy, drop the root).
     */
    private suspend fun migrateLegacyRoots(token: String, refs: List<RemoteRef>, result: SyncResultData) {
        for (legacy in listOf(CloudCodec.MEMORY_FILE, CloudCodec.MEMORY_TXT)) {
            val root = refs.firstOrNull { it.path == legacy } ?: continue
            val target = if (root.path == CloudCodec.MEMORY_FILE) CloudCodec.memoryPath() else CloudCodec.memoryTxtPath()
            try {
                val content = service.downloadRef(token, root)
                if (content != null && service.put(token, target, content)) {
                    service.deleteRef(token, root)
                }
            } catch (e: Exception) {
                result.errors.add("migracion ${root.path}: ${e.message.orEmpty()}")
            }
        }
    }

    /** Mutable accumulation for [SyncResult]. */
    private class SyncResultData {
        val uploaded = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val deletedRemotes = mutableListOf<String>()
        val deletedLocals = mutableListOf<String>()
        val conflictsKeptLocal = mutableListOf<String>()
        val errors = mutableListOf<String>()

        fun build(): SyncResult = SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedRemotes = deletedRemotes,
            deletedLocals = deletedLocals,
            conflictsKeptLocal = conflictsKeptLocal,
            errors = errors
        )
    }
}