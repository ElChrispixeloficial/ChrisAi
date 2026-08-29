package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.intent.Intent
import com.chrispixel.chrisai.data.intent.IntentEngine
import com.chrispixel.chrisai.data.intent.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentEngineTest {

    @Test
    fun `plain questions are conversation`() {
        assertEquals(Intent.Conversation, IntentEngine.detect("¿Cómo estás?"))
        assertEquals(Intent.Conversation, IntentEngine.detect("Cuéntame un chiste"))
    }

    @Test
    fun `memory save is detected with variants`() {
        assertTrue(IntentEngine.detect("recuerda que mi proyecto se llama ChrisTools") is Intent.SaveMemory)
        assertTrue(IntentEngine.detect("Memoriza que la cita es el lunes") is Intent.SaveMemory)
        assertTrue(IntentEngine.detect("Guarda esto en tu memoria") is Intent.SaveMemory)
        assertTrue(IntentEngine.detect("apunta que el build tarda 6 minutos") is Intent.SaveMemory)
    }

    @Test
    fun `memory forget is detected`() {
        val f = IntentEngine.detect("olvida el consejo de la contraseña")
        assertTrue(f is Intent.ForgetMemory)
        assertEquals("el consejo de la contraseña", (f as Intent.ForgetMemory).subject)
        assertTrue(IntentEngine.detect("olvida todo") is Intent.ForgetMemory)
        assertTrue(IntentEngine.detect("borra tu memoria") is Intent.ForgetMemory)
    }

    @Test
    fun `save conversation is not a memory`() {
        assertTrue(IntentEngine.detect("guarda la conversación que tuvimos") is Intent.SaveConversation)
    }

    @Test
    fun `open app is detected`() {
        val a = Intent.OpenApp("")
        assertEquals("super bear adventure", (IntentEngine.detect("abre super bear adventure") as Intent.OpenApp).appName)
        assertTrue(IntentEngine.detect("abre Spotify") is Intent.OpenApp)
    }

    @Test
    fun `search apps is detected`() {
        val s = IntentEngine.detect("Busca una aplicación de música gratuita")
        assertTrue(s is Intent.SearchApps)
        assertTrue((s as Intent.SearchApps).query.contains("música"))
    }

    @Test
    fun `timers are detected`() {
        val t = IntentEngine.detect("pon un temporizador de 5 minutos")
        assertTrue(t is Intent.CreateTimer)
        assertEquals(5, (t as Intent.CreateTimer).minutes)

        val s = IntentEngine.detect("recuérdame en 90 segundos")
        assertTrue(s is Intent.CreateTimer)
        assertEquals(90, (s as Intent.CreateTimer).seconds)
    }

    @Test
    fun `alarms are detected`() {
        val a = IntentEngine.detect("pon una alarma a las 7:30 para llamar al médico")
        assertTrue(a is Intent.CreateAlarm)
        a as Intent.CreateAlarm
        assertEquals(7, a.hours)
        assertEquals(30, a.minutes)
        assertNotNull(a.label)

        val b = IntentEngine.detect("despiértame a las 7")
        assertTrue(b is Intent.CreateAlarm)
        assertEquals(7, (b as Intent.CreateAlarm).hours)
        assertEquals(0, b.minutes)
    }

    @Test
    fun `vision now is detected and scan screen explicitly`() {
        assertTrue(IntentEngine.detect("mira esto") is Intent.VisionNow)
        assertTrue(IntentEngine.detect("analiza esta imagen") is Intent.VisionNow)
        assertTrue(IntentEngine.detect("¿Qué aparece aquí?") is Intent.VisionNow)
        assertTrue(IntentEngine.detect("analiza la pantalla") is Intent.VisionScreen)
        assertTrue(IntentEngine.detect("¿Qué estoy viendo?") is Intent.VisionScreen)
        assertTrue(IntentEngine.detect("¿Qué tengo que tocar?") is Intent.VisionScreen)
    }

    @Test
    fun `bare mira does not trigger vision`() {
        assertEquals(Intent.Conversation, IntentEngine.detect("mira, eso está muy bien"))
    }

    @Test
    fun `web search trumps app search`() {
        val w = IntentEngine.detect("busca el precio del iPhone 15 en internet")
        assertTrue(w is Intent.SearchWeb)
    }

    @Test
    fun `mode selection is detected`() {
        val m = IntentEngine.detect("activa el modo estudio")
        assertTrue(m is Intent.SetMode)
        assertEquals(SessionMode.STUDY, (m as Intent.SetMode).mode)
        assertTrue(IntentEngine.detect("estoy programando y me sale un error") is Intent.SetMode)
    }

    @Test
    fun `cancel timer alarm is detected`() {
        assertTrue(IntentEngine.detect("cancela el temporizador") is Intent.CancelTimerOrAlarm)
    }

    @Test
    fun `session summary is detected`() {
        assertTrue(IntentEngine.detect("¿Me haces un resumen de esta sesión?") is Intent.SessionSummary)
    }

    @Test
    fun `urls are detected`() {
        val u = IntentEngine.detect("abre https://github.com/ElChrispixeloficial/ChrisAi")
        assertTrue(u is Intent.OpenUrl)
        assertTrue((u as Intent.OpenUrl).url.startsWith("https://github.com"))
    }
}