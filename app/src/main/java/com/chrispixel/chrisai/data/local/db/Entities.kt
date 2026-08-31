package com.chrispixel.chrisai.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chrispixel.chrisai.data.model.SessionKind

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    // v1.0: session context kind (see SessionKind). Room v3 default keeps old rows safe.
    val kind: String = SessionKind.DEFAULT.id
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val position: Int,
    val streamed: Boolean,
    val failed: Boolean,
    val latencyMs: Long?,
    val totalMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    // v0.8.1: absolute path of the attached image file (null for text-only).
    val imagePath: String?
)

@Entity(tableName = "memories", indices = [Index(value = ["text"], unique = true)])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long
)