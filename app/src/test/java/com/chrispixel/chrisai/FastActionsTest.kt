package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.actions.FastAction
import com.chrispixel.chrisai.data.actions.FastActionParse
import com.chrispixel.chrisai.data.actions.FastActions
import com.chrispixel.chrisai.data.actions.summaryLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastActionsTest {

    private fun matched(text: String): FastAction {
        val parsed = FastActions.parse(text)
        assertTrue("expected Matched, got $parsed", parsed is FastActionParse.Matched)
        return (parsed as FastActionParse.Matched).action
    }

    @Test
    fun `open app matched`() {
        assertEquals(FastAction.OpenApp("youtube"), matched("Abre YouTube"))
        assertEquals(FastAction.OpenApp("whatsapp"), matched("abre la app whatsapp"))
        assertEquals(FastAction.OpenSettings, matched("ábreme ajustes"))
    }

    @Test
    fun `search in app matched`() {
        assertEquals(
            FastAction.SearchInApp(appLabel = "youtube", searchQuery = "minecraft"),
            matched("abre youtube y busca minecraft")
        )
        assertEquals(
            FastAction.SearchInApp(appLabel = "netflix", searchQuery = "dune"),
            matched("busca dune en netflix")
        )
        assertEquals(
            FastAction.SearchInApp(appLabel = null, searchQuery = "recetas"),
            matched("busca recetas")
        )
    }

    @Test
    fun `alarm times parse`() {
        val alarm = matched("pon una alarma a las 7:30")
            .let { it as FastAction.SetAlarm }
        assertEquals(7, alarm.hour)
        assertEquals(30, alarm.minute)

        val pm = matched("pon alarma a las 2 pm") as FastAction.SetAlarm
        assertEquals(14, pm.hour)
    }

    @Test
    fun `timer in minutes and hours`() {
        val t = matched("pon un temporizador de 25 minutos") as FastAction.SetTimer
        assertEquals(25, t.minutes)
        val h = matched("temporizador de 2 horas") as FastAction.SetTimer
        assertEquals(120, h.minutes)
    }

    @Test
    fun `time battery device matched`() {
        assertEquals(FastAction.WhatTime, matched("¿qué hora es?"))
        assertEquals(FastAction.Battery, matched("dime el nivel de batería"))
        assertEquals(FastAction.DeviceInfo, matched("qué dispositivo tengo"))
    }

    @Test
    fun `explain screen and end call`() {
        assertEquals(FastAction.ExplainScreen, matched("explícame esto"))
        assertEquals(FastAction.EndCall("cuelga la llamada"), matched("cuelga la llamada"))
    }

    @Test
    fun `context reference only when asking which`() {
        assertEquals(
            FastAction.AskContext("2"),
            matched("¿y cuál era el segundo?")
        )
        assertEquals(
            FastAction.AskContext("ultimo"),
            matched("¿cuál fue el último paso?")
        )
        // Not a question about the ordinal → not a fast action (keeps pipe open).
        val parsed = FastActions.parse("el segundo paso")
        assertTrue(parsed is FastActionParse.NotFastAction)
    }

    @Test
    fun `summary labels are honest and short`() {
        assertEquals("Abrir youtube", FastAction.OpenApp("youtube").summaryLabel())
        assertEquals(
            "Buscar «minecraft» en youtube",
            FastAction.SearchInApp("youtube", "minecraft").summaryLabel()
        )
        assertEquals("Alarma 07:30", FastAction.SetAlarm(7, 30).summaryLabel())
        assertEquals("Temporizador 25 min", FastAction.SetTimer(25).summaryLabel())
    }
}