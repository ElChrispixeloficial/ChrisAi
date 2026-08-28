package com.chrispixel.chrisai.data.local

import androidx.room.withTransaction
import com.chrispixel.chrisai.data.local.db.AppDatabase
import com.chrispixel.chrisai.data.local.db.ConversationEntity
import com.chrispixel.chrisai.data.local.db.MessageEntity
import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.ChatSession

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

    private suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        db.messageDao().getBySession(sessionId).map { it.toDomain() }

    private fun ConversationEntity.toDomain(messages: List<ChatMessage>): ChatSession = ChatSession(
        id = id,
        title = title,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = messages
    )

    private fun ChatSession.toEntity(): ConversationEntity = ConversationEntity(
        id = id,
        title = title,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt
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
        completionTokens = completionTokens
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
        completionTokens = completionTokens
    )
}