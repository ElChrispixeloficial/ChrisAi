package com.chrispixel.chrisai.data.context

import com.chrispixel.chrisai.data.model.ChatMessage
import com.chrispixel.chrisai.data.model.ChatRole
import com.chrispixel.chrisai.data.model.Memory
import com.chrispixel.chrisai.data.tools.ToolResult

/**
 * v0.9 Context Engine: a unified, *bounded* context policy.
 *
 * ChrisAI combines — deliberately and never indiscriminately — the current
 * message, a conversation window, relevant memories, the visible app, the
 * latest screen/camera observation and the outcomes of recent tools. Each
 * block is optional and size-capped so the model only receives what is
 * relevant to the current task.
 */
object ContextEngine {

    const val MAX_WINDOW_MESSAGES = 10
    const val MAX_TOOL_RESULTS = 6
    const val MAX_VISION_CHARS = 1800
    const val MAX_MEMORY_CHARS = 1200

    data class Input(
        val currentUserText: String,
        val currentSessionMessages: List<ChatMessage>,
        val relevantMemories: List<Memory>,
        val foregroundAppLabel: String? = null,
        val lastVisionAnalysis: String? = null,
        val studyActive: Boolean = false
    )

    data class Bundle(
        val windowText: String,
        val memoryBlock: String?,
        val appBlock: String?,
        val visionBlock: String?,
        val studyBlock: String?
    )

    /** Produces the bounded context packet for the current turn. */
    fun assemble(input: Input): Bundle {
        val user = input.currentUserText.trim()
        val window = input.currentSessionMessages.takeLast(MAX_WINDOW_MESSAGES)
            .filter { it.role != ChatRole.SYSTEM }

        val windowText = buildString {
            append("CONTEXTO DE CONVERSACIÓN (ventana ${window.size}):\n")
            if (window.isEmpty()) {
                append("(conversación nueva)")
            } else {
                window.forEach { m ->
                    val label = if (m.role == ChatRole.USER) "usuario" else "asistente"
                    val content = m.content.trim().take(1200)
                    append("- ").append(label).append(": ").append(content).append('\n')
                }
            }
        }.trim()

        val memoryBlock = input.relevantMemories.takeIf { it.isNotEmpty() }?.let { memories ->
            buildString {
                append("RECUERDOS RELEVANTES:\n")
                memories.forEach { m -> append("- ").append(m.text).append('\n') }
            }.trim().take(MAX_MEMORY_CHARS)
        }

        val appBlock = input.foregroundAppLabel?.takeIf { it.isNotBlank() }?.let { app ->
            "APLICACIÓN VISIBLE AHORA: $app"
        }

        val visionBlock = input.lastVisionAnalysis
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
                buildString {
                    append("ÚLTIMA OBSERVACIÓN DE PANTALLA/CÁMARA:\n")
                    append(description.take(MAX_VISION_CHARS))
                }
            }

        val studyBlock = if (input.studyActive) {
            StudyPrompt.block
        } else null

        return Bundle(
            windowText = windowText,
            memoryBlock = memoryBlock,
            appBlock = appBlock,
            visionBlock = visionBlock,
            studyBlock = studyBlock
        )
    }

    /**
     * Renders the bundle as system blocks for the payload: each non-null block
     * becomes one clearly-named system message (cheap, and keeps the ordering
     * deterministic for tests).
     */
    fun toSystemBlocks(bundle: Bundle): List<Pair<String, String>> = buildList {
        bundle.memoryBlock?.let { add("MEMORIA" to it) }
        bundle.appBlock?.let { add("CONTEXTO DE APP" to it) }
        bundle.visionBlock?.let { add("VISIÓN (observación actual)" to it) }
        bundle.studyBlock?.let { add("MODO ESTUDIO" to it) }
    }

    /** Bounded renderer for recent tool outcomes (cross-app context). */
    fun toolResultsBlock(results: List<ToolResult>): String? {
        if (results.isEmpty()) return null
        return buildString {
            append("RESULTADOS DE ACCIONES RECIENTES (memoria de ejecución):\n")
            results.take(MAX_TOOL_RESULTS).forEachIndexed { index, r ->
                append(index + 1).append(". ").append(r.toolId).append(": ")
                append(r.status.name).append(" — ").append(r.message.take(300)).append('\n')
                r.data.entries.take(4).forEach { (k, v) ->
                    append("   ").append(k).append("=").append(v.take(120)).append('\n')
                }
            }
        }.trim().take(2000)
    }
}

/** v0.9 Knowledge/Study mode: a fixed pedagogical contract for the model. */
object StudyPrompt {
    const val block: String =
        "[MODO ESTUDIO ACTIVO]\n" +
            "El usuario está aprendiendo. Comportamiento obligatorio:\n" +
            "- Explica el material como si fuera la primera vez que lo ve.\n" +
            "- Cuando pida una pista, da UNA pista guiada, nunca la respuesta directa.\n" +
            "- Usa ejemplos sencillos y compara con conceptos cotidianos.\n" +
            "- Cuando haya una imagen de pantalla/libro, explícala paso a paso señalando las partes.\n" +
            "- Es una herramienta educativa: fomenta que llegue a la respuesta por sí mismo.\n" +
            "- No soltar un examen final ni largas listas sin relación; mantén el ritmo del usuario."
}