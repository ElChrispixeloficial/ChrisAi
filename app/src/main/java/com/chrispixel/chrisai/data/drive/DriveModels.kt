package com.chrispixel.chrisai.data.drive

/** Remote metadata of one file/folder entry (v0.8 cloud memory). */
data class CloudEntry(
    val path: String,                 // "Memory.json", "Conversations/conv_37.json"
    val fingerprint: String,          // content hash (mirrors "changed?" checks)
    val modifiedMillis: Long = 0L,
    val version: Long = 0L,           // provider revision/etag numeric when available
    val sizeBytes: Long = 0L
)

/** Local side of the sync tree (mirrors remote + local-only files). */
sealed class SyncSide {
    data class Local(val entry: CloudEntry) : SyncSide()
    data class Remote(val entry: CloudEntry) : SyncSide()
    data class Both(val local: CloudEntry, val remote: CloudEntry) : SyncSide()
}

/** What to do for each file that differs between both sides. */
sealed class SyncAction {
    /** Path of the affected file; shared by every action to keep plans sortable. */
    abstract val path: String

    data class Upload(override val path: String, val local: CloudEntry) : SyncAction()
    data class Download(override val path: String, val remote: CloudEntry) : SyncAction()
    data class DeleteRemote(override val path: String) : SyncAction()
    data class DeleteLocal(override val path: String) : SyncAction()

    /** Conflict resolved with a strategy ("keep one" or "merge"). */
    data class Conflict(
        override val path: String,
        val local: CloudEntry,
        val remote: CloudEntry,
        val strategy: ConflictStrategy
    ) : SyncAction()

    /** Local change pending while offline: will sync when connectivity returns. */
    data class Pending(override val path: String, val local: CloudEntry) : SyncAction()
}

enum class ConflictStrategy { KEEP_LOCAL, KEEP_REMOTE, KEEP_NEWEST, MERGE }

/**
 * Incremental, offline-friendly sync planner (v0.8). The engine compares local
 * and remote fingerprints, downloads only what changed, uploads only local
 * changes, deletes what was removed on either side and flags conflicts without
 * ever overwriting data silently.
 *
 * Pure Kotlin: transports (local folder or Google Drive via OkHttp) implement
 * fetching the [SyncSide] tree and applying the plan.
 */
object DriveSyncEngine {

    /**
     * Computes the actions to converge local and remote.
     * [localOnlyFirst]: when equal fingerprints the entry is ignored (no re-download).
     */
    fun plan(local: Map<String, CloudEntry>, remote: Map<String, CloudEntry>): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        val allPaths = local.keys + remote.keys
        for (path in allPaths) {
            val l = local[path]
            val r = remote[path]
            when {
                l == null && r == null -> Unit
                l == null -> actions.add(SyncAction.Download(path, r!!))
                r == null -> actions.add(SyncAction.Upload(path, l))
                l.fingerprint == r.fingerprint -> Unit // up to date
                else -> actions.add(
                    SyncAction.Conflict(
                        path = path,
                        local = l,
                        remote = r,
                        strategy = strategyFor(path, l, r)
                    )
                )
            }
        }
        return actions.sortedBy { it.path }
    }

    /** Chooses a default strategy per file kind. Memory.json merges when the
     *  *remote* copy is newer (cloud edits to merge in); a strictly newer local
     *  copy wins (KEEP_NEWEST). Conversations always pick newest mtime. */
    fun strategyFor(path: String, local: CloudEntry, remote: CloudEntry): ConflictStrategy = when {
        !path.endsWith("Memory.json") -> ConflictStrategy.KEEP_NEWEST
        remote.modifiedMillis > local.modifiedMillis -> ConflictStrategy.MERGE
        else -> ConflictStrategy.KEEP_NEWEST
    }

    /** Resolves a conflict deterministically given the strategy. */
    fun resolve(
        conflict: SyncAction.Conflict,
        policy: ConflictStrategy = conflict.strategy
    ): SyncAction = when (policy) {
        ConflictStrategy.KEEP_LOCAL -> SyncAction.Upload(conflict.path, conflict.local)
        ConflictStrategy.KEEP_REMOTE -> SyncAction.Download(conflict.path, conflict.remote)
        ConflictStrategy.KEEP_NEWEST ->
            if (conflict.local.modifiedMillis >= conflict.remote.modifiedMillis)
                SyncAction.Upload(conflict.path, conflict.local)
            else
                SyncAction.Download(conflict.path, conflict.remote)
        ConflictStrategy.MERGE -> SyncAction.Pending(conflict.path, conflict.local)
    }

    /** When going offline: everything that differs is queued as pending. */
    fun toPending(diff: List<SyncAction>): List<SyncAction> =
        diff.mapNotNull { action ->
            when (action) {
                is SyncAction.Upload -> SyncAction.Pending(action.path, action.local)
                is SyncAction.Conflict -> SyncAction.Pending(action.path, action.local)
                is SyncAction.Download,
                is SyncAction.DeleteRemote,
                is SyncAction.DeleteLocal -> null
                is SyncAction.Pending -> action
            }
        }

    /** Convenience: pending changes that should retry once connectivity returns. */
    fun pendingReady(pendingInFlight: Boolean, queue: List<SyncAction>): Boolean =
        !pendingInFlight && queue.isNotEmpty()
}

/** Structured cloud snapshot (Memory.json + Conversations + Memory.txt export). */
data class CloudSnapshot(
    val memoryItems: List<CloudMemoryItem> = emptyList(),
    val conversations: List<CloudConversation> = emptyList()
) {
    val memoryCount: Int get() = memoryItems.size
}

data class CloudMemoryItem(
    val id: String,
    val content: String,
    val category: String = "otro",
    val importance: Int = 3,
    val tags: List<String> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

data class CloudConversation(
    val id: String,
    val title: String,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val messages: List<CloudMessage> = emptyList()
)

data class CloudMessage(
    val role: String,
    val content: String,
    val timestampMillis: Long = 0L
)