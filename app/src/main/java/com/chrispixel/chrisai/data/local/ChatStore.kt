package com.chrispixel.chrisai.data.local

import androidx.room.withTransaction
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.local.db.ConversationEntity
import com.chrispixel.chrisai.data.local.db.MessageEntity
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession
import com.chrispixel.chrisai.data.model.SessionKind

/**
 * Conversations persisted in Room. Schema is versioned so updates can migrate
 * existing data without losing it (see [AppDatabase]).
 */
class ChatStore(private val db: AppDatabase) {

    suspend fun list(): List<ChatSession> {
        val conversations = db.conversationDao().getAll()
        return conversations.map { it.toDomain(messages = loadMessages(it.id)) }
    }

    suspend fun find(id: String): ChatSession? {
        val conversation = db.conversationDao().getById(id) ?: return null
        return conversation.toDomain(messages = loadMessages(conversation.id))
    }

    suspend fun upsert(session: ChatSession) {
        db.withTransaction {
            db.conversationDao().upsert(session.toEntity())
            db.messageDao().deleteBySession(session.id)
            if (session.messages.isNotEmpty()) {
                db.messageDao().insertAll(
                    session.messages.mapIndexed { index, message -> message.toEntity(session.id, index) }
                )
            }
        }
    }

    suspend fun delete(id: String) {
        db.withTransaction { db.conversationDao().deleteById(id) }
    }

    /** Renames a conversation; returns false when the id does not exist. */
    suspend fun rename(id: String, newTitle: String): Boolean {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return false
        val conversation = db.conversationDao().getById(id) ?: return false
        db.conversationDao().upsert(
            conversation.copy(title = trimmed.take(MaxTitleChars), updatedAt = System.currentTimeMillis())
        )
        return true
    }

    /** Plain-text export of a conversation for sharing (history v2.0). */
    suspend fun exportText(id: String): String? {
        val conversation = db.conversationDao().getById(id) ?: return null
        val messages = db.messageDao().getBySession(id)
        val sb = StringBuilder()
        sb.append("Conversación: ").append(conversation.title).append('\n')
        sb.append("Modelo: ").append(conversation.model.ifBlank { "modelo no definido" }).append('\n')
        sb.append("==============================\n")
        for (message in messages) {
            val role = when (message.role) {
                ChatRole.USER.apiValue -> "Chris"
                ChatRole.ASSISTANT.apiValue -> "ChrisAI"
                else -> "Sistema"
            }
            sb.append(role).append(": ").append(message.content.trim()).append("\n\n")
        }
        return sb.toString().trim()
    }

    private companion object {
        const val MaxTitleChars = 60
    }

    private suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        db.messageDao().getBySession(sessionId).map { it.toDomain() }

    private fun ConversationEntity.toDomain(messages: List<ChatMessage>): ChatSession = ChatSession(
        id = id,
        title = title,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = messages,
        kind = SessionKind.fromId(kind)
    )

    private fun ChatSession.toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
        kind = kind.id
    )

    private fun ChatMessage.toEntity(sessionId: String, position: Int): MessageEntity = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.apiValue,
        content = content,
        timestamp = timestamp,
        position = position,
        streamed = streamed,
        failed = failed,
        latencyMs = latencyMs,
        totalMs = totalMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        imagePath = imagePath,
        generatedImagePath = generatedImagePath,
        audioPath = audioPath
    )

    private fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = ChatRole.fromApi(role),
        content = content,
        timestamp = timestamp,
        streamed = streamed,
        failed = failed,
        latencyMs = latencyMs,
        totalMs = totalMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        imagePath = imagePath,
        generatedImagePath = generatedImagePath,
        audioPath = audioPath
    )
}