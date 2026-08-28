package com.chrispixel.chrisai.data.local

import android.database.sqlite.SQLiteConstraintException
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.local.db.MemoryEntity
import com.chrispixel.chrisai.data.model.Memory
import java.util.UUID

/**
 * Persistent memories in Room v0.4: CRUD with IDs/timestamps and relevance
 * search so only pertinent memories are injected into the prompt.
 */
class MemoryStore(private val db: AppDatabase) {

    suspend fun list(): List<Memory> = db.memoryDao().getAll().map { it.toDomain() }

    /** Adds a new memory; returns false if it already exists (case-insensitive). */
    suspend fun add(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(id = UUID.randomUUID().toString(), text = normalized, createdAt = now, updatedAt = now)
        return try {
            db.memoryDao().insert(entity) >= 0
        } catch (_: SQLiteConstraintException) {
            false
        }
    }

    /** Removes memories containing [text]. Returns how many were removed. */
    suspend fun removeContaining(text: String): Int {
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return 0
        val toDelete = db.memoryDao().getAll().filter { needle in it.text.lowercase() }
        toDelete.forEach { db.memoryDao().deleteById(it.id) }
        return toDelete.size
    }

    suspend fun removeById(id: String) = db.memoryDao().deleteById(id)

    suspend fun removeAll() = db.memoryDao().deleteAll()

    /** Memories whose text contains at least one of [terms] (selective retrieval). */
    suspend fun search(terms: List<String>): List<Memory> {
        val normalizedTerms = terms.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        val all = db.memoryDao().getAll().map { it.toDomain() }
        if (normalizedTerms.isEmpty()) return all
        return all.filter { memory ->
            val text = memory.text.lowercase()
            normalizedTerms.any { it in text }
        }
    }

    /** Builds the memory context block using only the relevant [memories]. */
    fun asContext(memories: List<Memory>): String {
        if (memories.isEmpty()) {
            return "No existen recuerdos permanentes relevantes para esta conversación."
        }
        val entries = memories.joinToString("\n") { "- ${it.text}" }
        return """
            |Estos son recuerdos permanentes de Chris relevantes para la conversación:
            |
            |$entries
            |
            |Utilízalos únicamente si aportan a responder.
            """.trimMargin()
    }

    private fun MemoryEntity.toDomain(): Memory = Memory(
        id = id,
        text = text,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}