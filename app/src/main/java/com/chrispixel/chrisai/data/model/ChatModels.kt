package com.chrispixel.chrisai.data.model

import java.util.UUID

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT;

    val apiValue: String
        get() = when (this) {
            SYSTEM -> "system"
            USER -> "user"
            ASSISTANT -> "assistant"
        }

    companion object {
        fun fromApi(value: String): ChatRole = when (value) {
            "system" -> SYSTEM
            "assistant" -> ASSISTANT
            else -> USER
        }
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val streamed: Boolean = false,
    val failed: Boolean = false,
    val latencyMs: Long? = null,
    val totalMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    // v0.8.1: local path of a user-attached image (null for plain text).
    val imagePath: String? = null,
    // v1.1: local path of an assistant-GENERATED image (null otherwise).
    val generatedImagePath: String? = null,
    // v1.1: local path of an audio file (synthesized or attached; null otherwise).
    val audioPath: String? = null
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Nueva conversación",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    // v1.0: session context type (General/Estudio/Programación/ChrisAI/Acompañante).
    val kind: SessionKind = SessionKind.DEFAULT
)

data class AiModel(
    val id: String,
    val name: String,
    val contextLength: Long = 0,
    val promptPrice: String = ""
)