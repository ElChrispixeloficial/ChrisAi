package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.actions.ActionContextStore
import com.chrispixel.chrisai.data.actions.ActionPlanner
import com.chrispixel.chrisai.data.actions.FastAction
import com.chrispixel.chrisai.data.actions.PlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerTest {

    @Test
    fun `fast path single action`() {
        val plan = ActionPlanner.plan("Abre YouTube") as PlanResult.Plan
        assertEquals(listOf(FastAction.OpenApp("youtube")), plan.steps)
    }

    @Test
    fun `compound search becomes two ordered steps`() {
        val plan = ActionPlanner.plan("Abre YouTube y busca Minecraft") as PlanResult.Plan
        assertEquals(2, plan.steps.size)
        assertEquals(FastAction.OpenApp("youtube"), plan.steps[0])
        assertEquals(FastAction.SearchInApp("youtube", "minecraft"), plan.steps[1])
    }

    @Test
    fun `alarm after open becomes two ordered steps`() {
        val plan = ActionPlanner.plan("Abre YouTube y después pon una alarma a las 7:30") as PlanResult.Plan
        assertEquals(2, plan.steps.size)
        assertEquals(FastAction.OpenApp("youtube"), plan.steps[0])
        assertEquals(FastAction.SetAlarm(7, 30), plan.steps[1])
    }

    @Test
    fun `bare search inherits opened app`() {
        val plan = ActionPlanner.plan("abre youtube y después busca minecraft") as PlanResult.Plan
        assertEquals(2, plan.steps.size)
        assertEquals(FastAction.OpenApp("youtube"), plan.steps[0])
        assertEquals(FastAction.SearchInApp("youtube", "minecraft"), plan.steps[1])
    }

    @Test
    fun `summary request marks expectsSummary`() {
        val plan = ActionPlanner.plan("abre youtube y dime qué encontraste") as PlanResult.Plan
        assertTrue(plan.expectsSummary)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun `untestable clause falls back to model`() {
        val plan = ActionPlanner.plan("abre youtube y luego pinta un cuadro rojo")
        assertTrue(plan is PlanResult.Ambiguous)
    }

    @Test
    fun `blank or plain text is not a plan`() {
        assertTrue(ActionPlanner.plan("   ") is PlanResult.NotAPlan)
        assertTrue(ActionPlanner.plan("¿cómo se hace un café con leche?") is PlanResult.NotAPlan)
    }

    @Test
    fun `clauses split on coordinators`() {
        val clauses = ActionPlanner.splitClauses("Abre YouTube y busca Minecraft, luego pon una alarma")
        assertTrue(clauses.size >= 2)
    }

    @Test
    fun `context store resolves latest sequence`() {
        val store = ActionContextStore()
        store.push(listOf(
            ActionContextStore.Step(1, "Abrir youtube"),
            ActionContextStore.Step(2, "Buscar «minecraft» en youtube")
        ))
        assertEquals("Abrir youtube", store.resolve("1")?.label)
        assertEquals("Buscar «minecraft» en youtube", store.resolve("2")?.label)
        assertEquals("Buscar «minecraft» en youtube", store.resolve("ultimo")?.label)
        assertNull(store.resolve("9"))
    }

    @Test
    fun `context store respects bounded memory`() {
        val store = ActionContextStore(maxSequences = 1)
        store.push(listOf(ActionContextStore.Step(1, "Abrir youtube")))
        store.push(listOf(ActionContextStore.Step(1, "Abrir netflix")))
        // Old sequence expired by count.
        assertEquals("Abrir netflix", store.resolve("1")?.label)
        assertEquals(listOf(ActionContextStore.Step(1, "Abrir netflix")), store.latestSteps())
        store.clear()
        assertTrue(store.latestSteps().isEmpty())
    }
}