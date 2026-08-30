package com.chrispixel.chrisai.data.actions

/**
 * v0.9 Action Planner: sequences compound requests into ordered, safe actions.
 *
 * "Abre YouTube y busca Minecraft" becomes [OpenApp(YouTube), SearchInApp(YouTube, Minecraft)].
 * When any step cannot be resolved deterministically the whole request is
 * handed to the model instead of guessing.
 */
sealed class PlanResult {
    data class Plan(
        val steps: List<FastAction>,
        val expectsSummary: Boolean
    ) : PlanResult()

    /** Falls back to the model (the reliable path for tricky wording). */
    object Ambiguous : PlanResult()

    object NotAPlan : PlanResult()
}

object ActionPlanner {

    private val summaryRequest = Regex(
        """(dime qué encontraste|qué encontraste|dime qué has encontrado|cuéntame|resumen|explícame qué|qué viste)"""
    )

    /** Splits a request into clauses on coordinating separators. */
    fun splitClauses(text: String): List<String> {
        val lower = text.lowercase()
        val separators = listOf(" y después ", ", y después ", " y luego ", ", y luego ",
            " y a continuación ", ", después ", " y además ", " y ")
        var remaining = text.trim()
        val clauses = mutableListOf<String>()
        var cursor = text.length - remaining.length
        // We need positions, so iterate over the original with offsets.
        var rest = text
        var clausesOfRest = mutableListOf<String>()
        var tail = text
        var found = true
        while (found) {
            found = false
            for (sep in separators) {
                val idx = tail.indexOf(sep, ignoreCase = true)
                if (idx > 0 && idx < tail.length - sep.length) {
                    val left = tail.substring(0, idx).trim()
                    if (left.isNotBlank()) clausesOfRest.add(left)
                    tail = tail.substring(idx + sep.length).trim()
                    found = true
                    break
                }
            }
        }
        if (tail.isNotBlank()) clausesOfRest.add(tail.trim())
        return clausesOfRest.filter { it.isNotBlank() }
    }

    /** Builds an ordered plan, or defers to the model on ambiguity. */
    fun plan(text: String): PlanResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return PlanResult.NotAPlan

        val lowers = trimmed.lowercase()
        val expectsSummary = summaryRequest.containsMatchIn(lowers)

        // Sequence connectors mean "do several things" — decompose instead of
        // letting a single lazy clause capture the whole sentence (e.g. an
        // alarm hidden after "abre YouTube y después…").
        val hasConnectors = listOf(
            " y después", " y luego", " y a continuación", ", después",
            " y además", " y "
        ).any { lowers.contains(it) }

        // Fast path: the whole sentence is one determinable action.
        if (!hasConnectors) {
            when (val parsed = FastActions.parse(trimmed)) {
                is FastActionParse.Matched ->
                    return PlanResult.Plan(listOf(parsed.action), expectsSummary = false)
                is FastActionParse.Ambiguous -> return PlanResult.Ambiguous
                else -> Unit
            }
        }

        val clauses = splitClauses(trimmed)
        if (clauses.size < 2) return PlanResult.NotAPlan

        val steps = mutableListOf<FastAction>()
        var ambiguous = false
        for (clause in clauses) {
            val c = clause.lowercase().trim()
            // Connective/summary tails are not actions.
            if (summaryRequest.containsMatchIn(c) || c.startsWith("y also")) {
                continue
            }
            when (val parsed = FastActions.parse(clause)) {
                is FastActionParse.Matched -> steps.add(parsed.action)
                is FastActionParse.Ambiguous -> ambiguous = true
                is FastActionParse.NotFastAction -> {
                    // Verb-like clauses must resolve; filler like "por favor" is skipped.
                    val verbish = Regex(
                        """^(abre|abrir|busca|lanza|inicia|pon|quita|cancela|detén|pinta|dibuja|muéstrame|cuéntame|díme|cierra|envía|comparte|llama|graba|envíame|muestrame)"""
                    ).containsMatchIn(c)
                    if (verbish) {
                        // It could be part of a summary tail ("abre YouTube y dime qué viste").
                        ambiguous = true
                    }
                }
            }
        }

        // Contextual merge: a bare "busca X" right after "abre Y" inherits the app.
        if (steps.size >= 2 && !ambiguous) {
            val openIdx = steps.indexOfFirst { it is FastAction.OpenApp }
            if (openIdx >= 0 && openIdx + 1 < steps.size) {
                val next = steps[openIdx + 1]
                if (next is FastAction.SearchInApp && next.appLabel == null) {
                    steps[openIdx + 1] =
                        next.copy(appLabel = (steps[openIdx] as FastAction.OpenApp).query)
                }
            }
        }

        if (ambiguous) return PlanResult.Ambiguous
        if (steps.isEmpty()) return PlanResult.NotAPlan
        return PlanResult.Plan(steps, expectsSummary)
    }
}

/**
 * Bounded cross-action memory: remembers the last executed action sequences
 * (numbered) so the user can refer back ("¿y cuál era el segundo?"). Entries
 * are capped and expire by count — never stored indefinitely.
 */
class ActionContextStore(private val maxSequences: Int = 3) {

    data class Step(val index: Int, val label: String, val detail: String = "")

    private val sequences = ArrayDeque<List<Step>>()

    fun push(sequence: List<Step>) {
        if (sequence.isEmpty()) return
        sequences.addFirst(sequence)
        while (sequences.size > maxSequences) sequences.removeLast()
    }

    /** Resolves "el segundo"/"el primero"/"el último" against the last run. */
    fun resolve(reference: String): Step? {
        val latest = sequences.firstOrNull() ?: return null
        val ordinal = when (reference) {
            "1" -> 1
            "2" -> 2
            "3" -> 3
            "4" -> 4
            "ultimo" -> latest.size
            else -> return null
        }
        return latest.getOrNull(ordinal - 1)
    }

    fun latestSteps(): List<Step> = sequences.firstOrNull().orEmpty()

    fun clear() = sequences.clear()
}