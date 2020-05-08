package no.nav.helse.buffer

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals

class WindowBufferEmitterTest {

    @Test
    fun `split in sessions based on windowGap`() {
        val opinion1a = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "vekt":2, "score": 4, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion1b = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "vekt":10, "score": 10, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion2 = """{"vedtaksperiodeId":"periode2", "type": "vurdering", "vekt":3, "score": 5, "begrunnelser": ["reason 3"]}"""
        val o1a = Json.parse(JsonObject.serializer(), opinion1a)
        val o1b = Json.parse(JsonObject.serializer(), opinion1b)
        val o2 = Json.parse(JsonObject.serializer(), opinion2)

        val sessions = mutableListOf<List<JsonObject>>()
        fun lagOgSendVurdering(msgs: List<JsonObject>) {
            sessions += msgs
            println("laOgSend: $msgs")
        }

        val clock = mockk<Clock>()

        every { clock.millis() } returns 10000

        val window = WindowBufferEmitter(20, ::lagOgSendVurdering, clock, false)
        window.store("periode1", o1a, 1000)
        window.store("periode1", o1b, 12000)
        window.store("periode1", o1b, 18000)

        window.store("periode2", o2, 7000)
        window.store("periode2", o2, 17000)

        every { clock.millis() } returns 36999
        window.runExpiryCheck()
        assertEquals(0, sessions.size)

        window.store("periode1", o1a, 38001)

        every { clock.millis() } returns 60000
        window.runExpiryCheck()
        assertEquals(3, sessions.size)
        sessions[0].apply {
            assertEquals(3, this.size)
            for (i in 0..2) {
                assertEquals("periode1", this[i]["vedtaksperiodeId"]?.content)
            }
        }
        sessions[1].apply {
            assertEquals(1, this.size)
            assertEquals("periode1", this.first()["vedtaksperiodeId"]?.content)
        }
        sessions[2].apply {
            assertEquals(2, this.size)
            for (i in 0..1) {
                assertEquals("periode2", this[i]["vedtaksperiodeId"]?.content)
            }
        }
    }


    @Test
    fun `early expiry`() {
        val opinion1a = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "infotype":"A", "vekt":2, "score": 4, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion1b = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "infotype":"B", "vekt":10, "score": 10, "begrunnelser": ["reason 1", "reason 2"]}"""
        val o1a = Json.parse(JsonObject.serializer(), opinion1a)
        val o1b = Json.parse(JsonObject.serializer(), opinion1b)

        val sessions = mutableListOf<List<JsonObject>>()
        fun lagOgSendVurdering(msgs: List<JsonObject>) {
            sessions += msgs
            println("laOgSend: $msgs")
        }

        val clock = mockk<Clock>()

        every { clock.millis() } returns 1000
        var current = clock.millis()

        val windowSizeInSecs = 20L
        val windowSizeInMillis = windowSizeInSecs * 1000

        val window = WindowBufferEmitter(
            windowSizeInSeconds = windowSizeInSecs,
            aggregateAndEmit = ::lagOgSendVurdering,
            clock = clock,
            scheduleExpiryCheck = false,
            schedulerIntervalInSeconds = -1,
            sessionEarlyExpireCondition = {
                it.size == 2
                    && (it.find { msg -> msg["infotype"]?.content == "A" } != null)
                    && (it.find { msg -> msg["infotype"]?.content == "B" } != null)
            })
        window.store("periode1", o1a, 1000)
        window.store("periode1", o1b, 1000)
        assertEquals(1, sessions.size, "should have been emitted early because of earlyExpireCondition")
        sessions[0].apply {
            assertEquals(2, this.size)
            assertTrue(this.contains(o1a))
            assertTrue(this.contains(o1b))
        }
        sessions.clear()
        every { clock.millis() } returns current + 2000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(0, sessions.size, "should not be emitted again")
        every { clock.millis() } returns current + windowSizeInMillis + 1000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(0, sessions.size, "should not be emitted again, even after window-expiry")

        window.store("periode1", o1a, clock.millis())
        every { clock.millis() } returns current + windowSizeInMillis + 1000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(1, sessions.size)
        sessions[0].apply {
            assertEquals(1, this.size, "should be emitted after windowExpiry, even if earlyExpiryCondition is not met")
            assertTrue(this.contains(o1a))
        }

    }


}
