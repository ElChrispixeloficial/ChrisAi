package com.chrispixel.chrisai.data.tools.android

import com.chrispixel.chrisai.data.tools.ToolCall
import com.chrispixel.chrisai.data.tools.ToolCallBlock
import com.chrispixel.chrisai.data.tools.ToolExecutionReport
import com.chrispixel.chrisai.data.tools.ToolResult
import com.chrispixel.chrisai.data.tools.ToolResultStatus
import com.chrispixel.chrisai.data.tools.ToolRegistry

/** A live UI/UX event produced while executing a tool block. */
data class ToolEvent(
    val toolId: String,
    val status: ToolResultStatus,
    val message: String
)

/**
 * Executes a parsed [ToolCallBlock] against a [ToolRegistry], emitting
 * transient events (RUNNING → final) so the ViewModel can show discrete,
 * unobtrusive indicators like "⚙️ Abriendo YouTube…" and "✓ YouTube abierto".
 */
class ToolManager(
    private val registry: ToolRegistry
) {

    /** Concurrent executions are refused: the engine never runs tools in parallel. */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Executes all calls sequentially, emitting live [ToolEvent]s through
     * [onEvent] (RUNNING before each tool, final status after).
     * Returns the report for the model's second pass, or null when busy.
     */
    suspend fun execute(
        block: ToolCallBlock,
        onEvent: (ToolEvent) -> Unit = {}
    ): ToolExecutionReport? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            val calls = block.calls
            val results = ArrayList<ToolResult>(calls.size)
            for (call in calls) {
                onEvent(ToolEvent(call.id, ToolResultStatus.RUNNING, call.id))
                val result = registry.execute(call)
                results.add(result)
                onEvent(ToolEvent(call.id, result.status, result.message))
            }
            return ToolExecutionReport(calls, results)
        } finally {
            busy.set(false)
        }
    }

    fun registry(): ToolRegistry = registry
    fun schemas(): String = registry.describe()
}