package com.chrispixel.chrisai

import com.chrispixel.chrisai.data.provider.AiCapability
import com.chrispixel.chrisai.data.provider.AiProvider
import com.chrispixel.chrisai.data.provider.ProviderCallException
import com.chrispixel.chrisai.data.provider.ProviderEngine
import com.chrispixel.chrisai.data.provider.ProviderErrorType
import com.chrispixel.chrisai.data.provider.ProviderReply
import com.chrispixel.chrisai.data.provider.ProviderRequest
import com.chrispixel.chrisai.data.provider.VisionClassifier
import com.chrispixel.chrisai.data.vision.VisionSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Minimal configurable fake provider. */
private class FakeProvider(
    override val id: String,
    override val baseModel: String = id,
    private val caps: Set<AiCapability> = setOf(AiCapability.TEXT, AiCapability.STREAMING),
    private val replyText: String = id,
    private val failure: ProviderCallException? = null
) : AiProvider {
    var streamCalls = 0

    override fun capabilities(): Set<AiCapability> = caps

    override suspend fun stream(request: ProviderRequest, onDelta: (String) -> Unit): ProviderReply {
        streamCalls++
        failure?.let { throw it }
        onDelta(replyText)
        return ProviderReply(text = replyText, totalMs = 1)
    }

    override suspend fun cancel() = Unit
}

private val visionNo = VisionClassifier { _ -> VisionSupport.NOT_SUPPORTED }

class ProviderEngineTest {

    private fun call(
        engine: ProviderEngine,
        needsVision: Boolean = false
    ): ProviderReply = runBlocking {
        engine.streamChat(emptyList(), "m", null, {}, needsVision = needsVision)
    }

    @Test
    fun `primary handles text requests`() {
        val primary = FakeProvider("ro")
        val engine = ProviderEngine(primary, FakeProvider("gem"), "gem", visionNo)
        val reply = call(engine)
        assertEquals("ro", reply.text)
        assertEquals(1, primary.streamCalls)
    }

    @Test
    fun `recoverable error retries with backoff then falls back exactly once`() {
        val primary = FakeProvider("ro", failure = ProviderCallException(ProviderErrorType.RETRYABLE, 429, "rate"))
        val fallback = FakeProvider("gem")
        val engine = ProviderEngine(primary, fallback, "gem", visionNo)
        val reply = call(engine)
        assertEquals("gem", reply.text)
        // 1 initial attempt + 2 bounded retries, then a single fallback call.
        assertEquals(1 + ProviderEngine.DEFAULT_MAX_PRIMARY_RETRIES, primary.streamCalls)
        assertEquals(1, fallback.streamCalls)
    }

    @Test
    fun `transient primary recovers and never falls back`() {
        var first = true
        val primary = object : AiProvider {
            override val id = "ro"
            override val baseModel = "ro"
            override fun capabilities() = setOf(AiCapability.TEXT, AiCapability.STREAMING)
            var calls = 0
            override suspend fun stream(request: ProviderRequest, onDelta: (String) -> Unit): ProviderReply {
                calls++
                if (first) {
                    first = false
                    throw ProviderCallException(ProviderErrorType.RETRYABLE, 503, "overload")
                }
                onDelta("ro")
                return ProviderReply(text = "ro", totalMs = 1)
            }
            override suspend fun cancel() = Unit
        }
        val fallback = FakeProvider("gem")
        val engine = ProviderEngine(primary, fallback, "gem", visionNo)
        val reply = call(engine)
        assertEquals("ro", reply.text)
        assertEquals(2, primary.calls)
        assertEquals(0, fallback.streamCalls)
    }

    @Test
    fun `fatal error never retries nor falls back`() {
        val primary = FakeProvider("ro", failure = ProviderCallException(ProviderErrorType.FATAL, 401, "bad key"))
        val fallback = FakeProvider("gem")
        val engine = ProviderEngine(primary, fallback, "gem", visionNo)
        try {
            call(engine)
            fail("expected ProviderCallException")
        } catch (_: ProviderCallException) {
            // expected
        }
        assertEquals(1, primary.streamCalls)
        assertEquals(0, fallback.streamCalls)
    }

    @Test
    fun `no fallback revision when transient error and none configured`() {
        val primary = FakeProvider("ro", failure = ProviderCallException(ProviderErrorType.RETRYABLE, 503, "overload"))
        val engine = ProviderEngine(primary, null, "", visionNo)
        try {
            call(engine)
            fail("expected ProviderCallException")
        } catch (_: ProviderCallException) {
            // expected
        }
        assertEquals(1 + ProviderEngine.DEFAULT_MAX_PRIMARY_RETRIES, primary.streamCalls)
    }

    @Test
    fun `vision gaps route directly to fallback`() {
        val primary = FakeProvider("ro")
        val fallback = FakeProvider("gem", caps = setOf(AiCapability.VISION, AiCapability.TEXT, AiCapability.STREAMING))
        val engine = ProviderEngine(primary, fallback, "gem", visionNo)
        val reply = call(engine, needsVision = true)
        assertEquals("gem", reply.text)
        assertEquals(0, primary.streamCalls)
        assertEquals(1, fallback.streamCalls)
    }

    @Test
    fun `vision need does not fallback into a blind provider`() {
        val primary = FakeProvider("ro", failure = ProviderCallException(ProviderErrorType.RETRYABLE, 429, "rate"))
        val fallback = FakeProvider("gem", caps = setOf(AiCapability.TEXT, AiCapability.STREAMING))
        val engine = ProviderEngine(primary, fallback, "gem", visionNo)
        try {
            call(engine, needsVision = true)
            fail("expected ProviderCallException")
        } catch (_: ProviderCallException) {
            // expected
        }
        assertEquals(1 + ProviderEngine.DEFAULT_MAX_PRIMARY_RETRIES, primary.streamCalls)
        assertEquals(0, fallback.streamCalls)
    }

    @Test
    fun `fallback availability reflected`() {
        val primary = FakeProvider("ro")
        assertFalse(ProviderEngine(primary, FakeProvider("gem"), "", visionNo).fallbackVisionCapable)
        assertTrue(
            ProviderEngine(
                primary,
                FakeProvider("gem", caps = setOf(AiCapability.VISION, AiCapability.TEXT)),
                "gem",
                visionNo
            ).fallbackVisionCapable
        )
    }
}