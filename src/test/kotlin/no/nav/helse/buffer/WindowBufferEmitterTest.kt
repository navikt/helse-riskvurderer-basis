package no.nav.helse.buffer

import io.mockk.every
import io.mockk.mockk
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.StringWriter
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class WindowBufferEmitterTest {

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    @Test
    fun `split in sessions based on windowGap`() {
        val opinion1a = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "vekt":2, "score": 4, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion1b = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "vekt":10, "score": 10, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion2 = """{"vedtaksperiodeId":"periode2", "type": "vurdering", "vekt":3, "score": 5, "begrunnelser": ["reason 3"]}"""
        val o1a = Json.parse(JsonObject.serializer(), opinion1a)
        val o1b = Json.parse(JsonObject.serializer(), opinion1b)
        val o2 = Json.parse(JsonObject.serializer(), opinion2)

        val sessions = mutableListOf<List<JsonObject>>()
        val expectedKafkaKey = "whateverkey"

        fun lagOgSendVurdering(emitted: WindowBufferEmittable) {
            assertEquals(expectedKafkaKey, emitted.kafkaKey)
            sessions += emitted.messages
            println("lagOgSend: ${emitted.messages} key=${emitted.kafkaKey}")
        }

        val clock = mockk<Clock>()

        every { clock.millis() } returns 10000

        val window = WindowBufferEmitter(20, ::lagOgSendVurdering, CollectorRegistry.defaultRegistry, clock, false)
        window.store("periode1", o1a, expectedKafkaKey, 1000)
        window.store("periode1", o1b, expectedKafkaKey, 12000)
        window.store("periode1", o1b, expectedKafkaKey, 18000)

        assertEquals(1, window.activeKeys)

        window.store("periode2", o2, expectedKafkaKey, 7000)
        window.store("periode2", o2, expectedKafkaKey, 17000)

        assertEquals(2, window.activeKeys)

        every { clock.millis() } returns 36999
        window.runExpiryCheck()
        assertEquals(0, sessions.size)
        assertEquals(2, window.activeKeys)

        window.store("periode1", o1a, expectedKafkaKey, 38001)
        assertEquals(2, window.activeKeys)

        every { clock.millis() } returns 60000
        window.runExpiryCheck()
        assertEquals(3, sessions.size)
        assertEquals(0, window.activeKeys)

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

        val metrics = metricsString()
        println(metrics)
        assertTrue(metrics.contains("buffered_session_emitted{state=\"unconditional\",} 3.0"))
    }


    @Test
    fun `early expiry`() {
        val opinion1a = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "infotype":"A", "vekt":2, "score": 4, "begrunnelser": ["reason 1", "reason 2"]}"""
        val opinion1b = """{"vedtaksperiodeId":"periode1", "type": "vurdering", "infotype":"B", "vekt":10, "score": 10, "begrunnelser": ["reason 1", "reason 2"]}"""
        val o1a = Json.parse(JsonObject.serializer(), opinion1a)
        val o1b = Json.parse(JsonObject.serializer(), opinion1b)

        val emittedSessions = mutableListOf<List<JsonObject>>()
        val expectedKafkaKey = "einAnnaNykjel"
        fun lagOgSendVurdering(emitted: WindowBufferEmittable) {
            assertEquals(expectedKafkaKey, emitted.kafkaKey)
            emittedSessions += emitted.messages
            println("lagOgSend: ${emitted.messages} key=${emitted.kafkaKey}")
        }

        val clock = mockk<Clock>()

        every { clock.millis() } returns 1000
        var current = clock.millis()

        val windowSizeInSecs = 20L
        val windowSizeInMillis = windowSizeInSecs * 1000

        val window = WindowBufferEmitter(
            windowSizeInSeconds = windowSizeInSecs,
            aggregateAndEmit = ::lagOgSendVurdering,
            collectorRegistry = CollectorRegistry.defaultRegistry,
            clock = clock,
            scheduleExpiryCheck = false,
            schedulerIntervalInSeconds = -1,
            sessionEarlyExpireCondition = {
                it.size == 2
                    && (it.find { msg -> msg["infotype"]?.content == "A" } != null)
                    && (it.find { msg -> msg["infotype"]?.content == "B" } != null)
            })
        assertEquals(0, window.activeKeys)
        window.store("periode1", o1a, expectedKafkaKey, 3000)
        assertEquals(1, window.activeKeys)
        window.store("periode1", o1b, expectedKafkaKey, 10000)
        assertEquals(0, window.activeKeys)
        assertEquals(1, emittedSessions.size, "should have been emitted early because of earlyExpireCondition")
        emittedSessions[0].apply {
            assertEquals(2, this.size)
            assertTrue(this.contains(o1a))
            assertTrue(this.contains(o1b))
        }
        emittedSessions.clear()
        every { clock.millis() } returns current + 2000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(0, emittedSessions.size, "should not be emitted again")
        every { clock.millis() } returns current + windowSizeInMillis + 1000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(0, emittedSessions.size, "should not be emitted again, even after window-expiry")

        window.store("periode1", o1a, expectedKafkaKey, clock.millis())
        assertEquals(1, window.activeKeys)
        every { clock.millis() } returns current + windowSizeInMillis + 1000
        current = clock.millis()

        window.runExpiryCheck()
        assertEquals(0, window.activeKeys)
        assertEquals(1, emittedSessions.size)
        emittedSessions[0].apply {
            assertEquals(1, this.size, "should be emitted after windowExpiry, even if earlyExpiryCondition is not met")
            assertTrue(this.contains(o1a))
        }

        val metrics = metricsString()
        println(metrics)
        assertTrue(metrics.contains("buffered_session_emitted{state=\"incomplete\",} 1.0"))
        assertTrue(metrics.contains("buffered_session_emitted{state=\"complete\",} 1.0"))
        assertTrue(metrics.contains("buffered_session_emitted_after_secs_summary_count 1.0"))
        assertTrue(metrics.contains("buffered_session_emitted_after_secs_summary_sum 7.0")) // 10000 - 3000
        assertTrue(metrics.contains("buffered_session_emitted_time_left_secs_summary_count 1.0"))
        assertTrue(metrics.contains("buffered_session_emitted_time_left_secs_summary_sum 13.0")) // 20 - 7
    }

    @Test
    fun `sessionId equals and hashCode should make sense`() {
        assertEquals(WindowBufferEmitterSessionId("s1", "k1"), WindowBufferEmitterSessionId("s1", "k1"))
        assertEquals(WindowBufferEmitterSessionId("s1", "k1").hashCode(), WindowBufferEmitterSessionId("s1", "k1").hashCode())

        assertNotEquals(WindowBufferEmitterSessionId("s1", "k1"), WindowBufferEmitterSessionId("s1", "k2"))
        assertNotEquals(WindowBufferEmitterSessionId("s1", "k1").hashCode(), WindowBufferEmitterSessionId("s1", "k2").hashCode())

        assertNotEquals(WindowBufferEmitterSessionId("s1", "k1"), WindowBufferEmitterSessionId("s2", "k1"))
        assertNotEquals(WindowBufferEmitterSessionId("s1", "k1").hashCode(), WindowBufferEmitterSessionId("s2", "k1").hashCode())
    }

    private fun metricsString() : String {
        val writer = StringWriter()
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.filteredMetricFamilySamples((emptySet())))
        return writer.toString()
    }
}
