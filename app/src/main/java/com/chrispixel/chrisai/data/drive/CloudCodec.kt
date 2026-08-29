package com.chrispixel.chrisai.data.drive

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON (de)serialization for the cloud snapshot (v0.8).
 *
 * Drive layout:
 *   ChrisAI/
 *     Memory.json        (structured memories + version)
 *     Memory.txt         (human-readable export, optional)
 *     Conversations/
 *       conversation_<id>.json
 *
 * Room remains the local source of truth; these JSON files are the remote sync
 * representation. Pure Kotlin + org.json so it is unit-testable on the JVM.
 */
object CloudCodec {

    const val LAYOUT_VERSION = 1
    const val MEMORY_FILE = "Memory.json"
    const val MEMORY_TXT = "Memory.txt"
    const val CONVERSATIONS_DIR = "Conversations"

    fun conversationFileName(id: String): String = "conversation_${sanitize(id)}.json"

    fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    // ---------------------------------------------------------------- memory

    /** Memory.json document. */
    fun encodeMemory(memory: List<CloudMemoryItem>): String {
        val arr = JSONArray()
        memory.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("content", m.content)
                    .put("category", m.category)
                    .put("importance", m.importance)
                    .put("tags", JSONArray(m.tags))
                    .put("createdAt", m.createdAtMillis)
                    .put("updatedAt", m.updatedAtMillis)
            )
        }
        return JSONObject()
            .put("version", LAYOUT_VERSION)
            .put("memories", arr)
            .toString(2)
    }

    fun decodeMemory(json: String): List<CloudMemoryItem> {
        return try {
            val doc = JSONObject(json)
            val arr = doc.optJSONArray("memories") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tags = o.optJSONArray("tags")
            CloudMemoryItem(
                id = o.optString("id"),
                content = o.optString("content"),
                category = o.optString("category", "otro"),
                importance = o.optInt("importance", 3).coerceIn(1, 5),
                tags = if (tags == null) emptyList() else (0 until tags.length()).mapNotNull { tags.optString(it) },
                createdAtMillis = o.optLong("createdAt", 0L),
                updatedAtMillis = o.optLong("updatedAt", 0L)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
    }

    /** Human-readable dump for [Memory.txt]. */
    fun encodeMemoryTxt(memory: List<CloudMemoryItem>): String {
        if (memory.isEmpty()) return "ChrisAI no tiene recuerdos guardados todavía.\n"
        val sb = StringBuilder("ChrisAI — recuerdos\n=====================\n\n")
        memory.sortedByDescending { it.updatedAtMillis }.forEachIndexed { index, m ->
            sb.append("${index + 1}. ")
                .append(m.content)
                .append("\n")
            if (m.tags.isNotEmpty()) sb.append("   Etiquetas: ").append(m.tags.joinToString(", ")).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------- conversations

    fun encodeConversation(conv: CloudConversation): String {
        val messages = JSONArray()
        conv.messages.forEach { m ->
            messages.put(
                JSONObject()
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("timestamp", m.timestampMillis)
            )
        }
        return JSONObject()
            .put("version", LAYOUT_VERSION)
            .put("id", conv.id)
            .put("title", conv.title)
            .put("createdAt", conv.createdAtMillis)
            .put("updatedAt", conv.updatedAtMillis)
            .put("messages", messages)
            .toString(2)
    }

    fun decodeConversation(json: String): CloudConversation = try {
        val doc = JSONObject(json)
        val arr = doc.optJSONArray("messages")
        CloudConversation(
            id = doc.optString("id"),
            title = doc.optString("title"),
            createdAtMillis = doc.optLong("createdAt", 0L),
            updatedAtMillis = doc.optLong("updatedAt", 0L),
            messages = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CloudMessage(
                    role = o.optString("role"),
                    content = o.optString("content"),
                    timestampMillis = o.optLong("timestamp", 0L)
                )
            }
        )
    } catch (e: Exception) {
        CloudConversation(id = "", title = "")
    }

    /** Merge of two memory lists (conflict MERGE): newest per id wins, dedup by id. */
    fun mergeMemory(local: List<CloudMemoryItem>, remote: List<CloudMemoryItem>): List<CloudMemoryItem> {
        val byId = HashMap<String, CloudMemoryItem>(local.size + remote.size)
        (local + remote).forEach { item -> byId[item.id] = item }
        return byId.values.sortedByDescending { it.updatedAtMillis }
    }
}