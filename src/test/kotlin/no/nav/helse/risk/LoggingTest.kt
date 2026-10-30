package no.nav.helse.risk

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.logstash.logback.encoder.LogstashEncoder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LoggingTest {
    @Test
    fun `logstash encoder skriver gyldig JSON med Jackson`() {
        val context = LoggerContext().apply {
            mdcAdapter = LogbackMDCAdapter()
        }
        val encoder = LogstashEncoder().apply {
            this.context = context
        }
        try {
            encoder.start()
            val event = LoggingEvent(
                javaClass.name,
                context.getLogger("test"),
                Level.INFO,
                "Jackson {}",
                null,
                arrayOf("logging")
            )

            val json = Json.parseToJsonElement(encoder.encode(event).toString(Charsets.UTF_8)).jsonObject

            assertEquals("Jackson logging", json.getValue("message").jsonPrimitive.content)
            assertEquals("INFO", json.getValue("level").jsonPrimitive.content)
        } finally {
            encoder.stop()
            context.stop()
        }
    }
}
