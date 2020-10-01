package no.nav.helse.risk

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class RiverAppTest {

    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)

    class Done : RuntimeException()

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    @Test
    fun `app is healthy by default`() {
        val app = lagRiverApp()
        assertTrue(app.isHealthy())
    }

    @Test
    fun `additional health-check says NOT healthy`() {
        val app = lagRiverApp(
            additionalHealthCheck = { false }
        )
        assertFalse(app.isHealthy())
    }

    @Test
    fun `launch additional stuff`() {
        var myValue: String = "NOT THIS"
        val app = lagRiverApp(
            launchAlso = listOf<suspend CoroutineScope.() -> Unit> {
                myValue = "BUT THIS"
            }
        )
        assertEquals("NOT THIS", myValue)
        val job = GlobalScope.launch {
            app.start()
        }
        Thread.sleep(1000)
        assertEquals("BUT THIS", myValue)
        job.cancel()
    }

    val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()

    fun lagRiverApp(
        additionalHealthCheck: (() -> Boolean)? = null,
        launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList()
    ): RiverApp {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = mockk<KafkaConsumer<String, JsonObject>>()
        fun lagSvar(meldinger: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
            return defaultSvar
        }

        val app = RiverApp(
            kafkaClientId = "testRiverApp",
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata")),
            answerer = ::lagSvar,
            collectorRegistry = CollectorRegistry.defaultRegistry,
            additionalHealthCheck = additionalHealthCheck,
            launchAlso = launchAlso
        ).overrideKafkaEnvironment(KafkaRiverEnvironment(
            kafkaConsumer = consumer,
            kafkaProducer = producer
        ))

        producedMessages.clear()
        every { consumer.subscribe(listOf(riskRiverTopic)) } just Runs
        val records = defaultInnkommendeMeldinger.map {
            ConsumerRecord(riskRiverTopic, partition, 1,
                "envedtaksperiodeid", it)
        }
        every { consumer.poll(Duration.ZERO) } returns ConsumerRecords(mapOf(riverTopicPartition to records
        )) andThenThrows Done()
        every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>() andThenThrows IllegalStateException("no more please!")

        return app
    }

    val fnr = "01017000000"
    val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
    val orgnr = "123456789"
    val behovOpprettet = LocalDateTime.now()

    private val defaultInnkommendeMeldinger = listOf(
        json {
            "type" to "RiskNeed"
            "iterasjon" to 1
            "fnr" to fnr
            "organisasjonsnummer" to orgnr
            "vedtaksperiodeId" to vedtaksperiodeid
            "behovOpprettet" to behovOpprettet.toString()
            "foersteFravaersdag" to "2020-01-01"
            "sykepengegrunnlag" to 50000.0
            "periodeFom" to "2020-02-01"
            "periodeTom" to "2020-02-28"
        },
        json {
            "type" to "oppslagsresultat"
            "infotype" to "testdata"
            "vedtaksperiodeId" to vedtaksperiodeid
            "data" to json {
                "felt-1" to "verdi-1"
                "c" to listOf(1, 2, 3)
            }
        }
    )

    private val defaultSvar = json {
        "this is" to "the answer"
    }


}