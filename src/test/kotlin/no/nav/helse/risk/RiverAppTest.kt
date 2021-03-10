package no.nav.helse.risk

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.awaitility.Awaitility
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLTransientException
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@FlowPreview
class RiverAppTest {

    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)

    class Done : RuntimeException()

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    @BeforeEach
    fun skipProductionChecks() {
        Sanity.setSkipSanityChecksForProduction()
    }

    @Test
    fun `app is healthy by default`() {
        val app = lagRiverApp().app
        assertTrue(app.isHealthy())
    }

    @Test
    fun `additional health-check says NOT healthy`() {
        val app = lagRiverApp(
            additionalHealthCheck = { false }
        ).app
        assertFalse(app.isHealthy())
    }


    @Volatile
    var myTestValue = "NOT THIS"

    @Test
    fun `launch additional stuff`() {
        val mockConsumer = MockConsumer<String, JsonObject>()
        val app = lagRiverApp(
            launchAlso = listOf<suspend CoroutineScope.() -> Unit> {
                myTestValue = "BUT THIS"
            },
            predefinedConsumer = mockConsumer,
            producerThrows = null
        ).app
        assertEquals("NOT THIS", myTestValue)
        val job = GlobalScope.launch {
            app.start()
        }

        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                assertEquals("BUT THIS", myTestValue)
            }
        mockConsumer.die()
        job.cancel()
    }

    @Test
    fun `TopicAuthorizationException makes app UN-healthy`() {
        assertGetsUnhealthyWhenThrown(org.apache.kafka.common.errors.TopicAuthorizationException("BAD CREDS"))
    }

    @Test
    fun `SQLTransientException makes app UN-healthy`() {
        assertGetsUnhealthyWhenThrown(SQLTransientException("Better luck next time"))
    }

    fun assertGetsUnhealthyWhenThrown(throwable: Throwable) {
        val mockConsumer = MockConsumer<String, JsonObject>()

        val app = lagRiverApp(
            consumerThrows = null,
            producerThrows = null,
            predefinedConsumer = mockConsumer
        )
        val job = GlobalScope.launch {
            app.app.start()
        }
        assertTrue(app.app.isHealthy())
        mockConsumer.pollFunction = {
            throw throwable
        }
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                assertFalse(app.app.isHealthy())
            }
        job.cancel()
    }


    val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()

    class AppSetup(
        val app: RiverApp,
        val consumerMock: Consumer<String, JsonObject>
    )

    fun lagRiverApp(
        additionalHealthCheck: (() -> Boolean)? = null,
        launchAlso: List<suspend CoroutineScope.() -> Unit> = emptyList(),
        consumerThrows: Exception? = Done(),
        producerThrows: Exception? = IllegalStateException("no more please!"),
        predefinedConsumer: Consumer<String, JsonObject>? = null
    ): AppSetup {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = predefinedConsumer ?: mockk<KafkaConsumer<String, JsonObject>>()
        val lagSvar: (List<JsonObject>, String) -> JsonObject = { _, _ -> defaultSvar }

        val app = RiverApp(
            kafkaClientId = "testRiverApp",
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata")
            ),
            skipEmitIfNotPresent = emptyList(),
            answerer = lagSvar,
            collectorRegistry = CollectorRegistry.defaultRegistry,
            additionalHealthCheck = additionalHealthCheck,
            launchAlso = launchAlso,
            disableWebEndpoints = true
        ).overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = consumer,
                kafkaProducer = producer
            )
        )

        producedMessages.clear()
        if (predefinedConsumer == null) {
            every { consumer.subscribe(listOf(riskRiverTopic)) } just Runs
            val records = defaultInnkommendeMeldinger.map {
                ConsumerRecord(
                    riskRiverTopic, partition, 1,
                    "envedtaksperiodeid", it
                )
            }
            (every { consumer.poll(Duration.ZERO) } returns ConsumerRecords(
                mapOf(
                    riverTopicPartition to records
                )
            )).apply {
                if (consumerThrows != null) {
                    this andThenThrows consumerThrows
                }
            }
        }

        (every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>()).apply {
            if (producerThrows != null) {
                this andThenThrows producerThrows
            }
        }

        return AppSetup(
            app = app,
            consumerMock = consumer
        )
    }

    val fnr = "01017000000"
    val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
    val orgnr = "123456789"
    val behovOpprettet = LocalDateTime.now()

    private val defaultInnkommendeMeldinger = listOf(
        buildJsonObject {
            put("type", "RiskNeed")
            put("iterasjon", 1)
            put("fnr", fnr)
            put("organisasjonsnummer", orgnr)
            put("vedtaksperiodeId", vedtaksperiodeid)
            put("behovOpprettet", behovOpprettet.toString())
            put("foersteFravaersdag", "2020-01-01")
            put("sykepengegrunnlag", 50000.0)
            put("periodeFom", "2020-02-01")
            put("periodeTom", "2020-02-28")
        },
        buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "testdata")
            put("vedtaksperiodeId", vedtaksperiodeid)
            put("data", buildJsonObject {
                put("felt-1", "verdi-1")
                put("c", buildJsonArray { add(1); add(2); add(3) })
            })
        }
    )

    private val defaultSvar = buildJsonObject {
        put("this is", "the answer")
    }


}