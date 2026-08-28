package com.chrispixel.chrisai.data.local

import android.database.sqlite.SQLiteConstraintException
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.local.db.MemoryEntity
import com.chrispixel.chrisai.data.model.Memory
import java.util.UUID

/** Result of adding/editing a memory, with v0.6 duplicate detection. */
sealed class MemoryWriteResult {
    object Added : MemoryWriteResult()
    object Updated : MemoryWriteResult()
    object Duplicate : MemoryWriteResult()
    object NotFound : MemoryWriteResult()
}

/**
 * Persistent memories in Room v0.4+: CRUD with IDs/timestamps, relevance
 * search and v0.6 duplicate detection (exact case-insensitive + similar).
 */
class MemoryStore(private val db: AppDatabase) {

    suspend fun list(): List<Memory> = db.memoryDao().getAll().map { it.toDomain() }

    /** True when [text] already exists (case-insensitive exact match). */
    suspend fun exists(text: String): Boolean {
        val needle = text.trim().lowercase()
        return db.memoryDao().getAll().any { it.text.lowercase() == needle }
    }

    /**
     * Memories that are likely duplicating [text] (they share at least
     * MIN_SIMILAR_WORDS significant words, ignoring stop words).
     */
    suspend fun findSimilar(text: String): List<Memory> {
        val terms = significantTerms(text)
        if (terms.size < 2) return emptyList()
        return db.memoryDao()
            .getAll()
            .map { it.toDomain() }
            .filter { memory ->
                val memoryTerms = significantTerms(memory.text)
                memoryTerms.count { it in terms } >= MIN_SIMILAR_WORDS
            }
    }

    /**
     * Adds a new memory. Returns [MemoryWriteResult.Duplicate] when the exact
     * text already exists (case-insensitive); otherwise stores it.
     */
    suspend fun add(text: String): MemoryWriteResult {
        val normalized = text.trim()
        if (normalized.isEmpty()) return MemoryWriteResult.Duplicate
        if (exists(normalized)) return MemoryWriteResult.Duplicate
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(id = UUID.randomUUID().toString(), text = normalized, createdAt = now, updatedAt = now)
        return try {
            db.memoryDao().insert(entity)
            MemoryWriteResult.Added
        } catch (_: SQLiteConstraintException) {
            MemoryWriteResult.Duplicate
        }
    }

    /**
     * Edits an existing memory. Returns [MemoryWriteResult.Duplicate] when the
     * new text collides with another memory, [MemoryWriteResult.NotFound] when
     * the id does not exist.
     */
    suspend fun edit(id: String, newText: String): MemoryWriteResult {
        val normalized = newText.trim()
        if (normalized.isEmpty()) return MemoryWriteResult.Duplicate
        val existing = db.memoryDao().getAll().firstOrNull { it.id == id } ?: return MemoryWriteResult.NotFound
        val collision = db.memoryDao().getAll()
            .any { it.id != id && it.text.lowercase() == normalized.lowercase() }
        if (collision) return MemoryWriteResult.Duplicate
        val now = System.currentTimeMillis()
        db.memoryDao().update(existing.copy(text = normalized, updatedAt = now))
        return MemoryWriteResult.Updated
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

    /** Lowercased significant words (length > 3, stops words removed). */
    private fun significantTerms(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 3 }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private companion object {
        const val MIN_SIMILAR_WORDS = 2
        val STOP_WORDS = setOf(
            "para", "esta", "este", "esto", "como", "cuando", "donde", "porque", "sobre",
            "con", "del", "los", "las", "una", "unos", "unas", "que", "cual", "cuales",
            "muy", "mas", "tambien", "puedes", "quiero", "necesito", "mira", "tiene",
            "tienen", "siempre", "nunca", "todo", "toda", "todos", "todas", "cosa", "cosas"
        )
    }
}