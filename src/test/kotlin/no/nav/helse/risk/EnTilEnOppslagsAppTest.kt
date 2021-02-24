package no.nav.helse.risk

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.awaitility.Awaitility
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals

@FlowPreview
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class EnTilEnOppslagsAppTest {
    init {
        Sanity.setSkipSanityChecksForProduction()
    }

    private val JSON = JsonRisk
    val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)

    class Done : RuntimeException()

    val fnr = "01017000000"
    val orgnr = "123456789"
    val behovOpprettet = LocalDateTime.now()

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }
    @BeforeEach
    fun skipProductionChecks() {
        Sanity.setSkipSanityChecksForProduction()
    }


    @Test
    fun `EnTilEnOppslagsApp med default svarer paa RiskNeed(iter=1)`() {
        startOppslagsApp(
            innkommendeMeldinger = listOf(
                buildJsonObject {
                    put("type", "RiskNeed")
                    put("iterasjon", 1)
                    put("fnr", fnr)
                    put("organisasjonsnummer", orgnr)
                    put("vedtaksperiodeId", "p1")
                    put("behovOpprettet", behovOpprettet.toString())
                }
            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                oppslagstjeneste = { riskNeed ->
                    buildJsonObject {
                        put("felt1", "data1")
                    }
                },
                disableWebEndpoints = true
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("p1", this.vedtaksperiodeId)
            assertEquals("data1", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `EnTilEnOppslagsApp med default svarer paa RiskNeed(iter=2)`() {
        startOppslagsApp(
            innkommendeMeldinger = listOf(
                buildJsonObject {
                    put("type", "RiskNeed")
                    put("iterasjon", 2)
                    put("fnr", fnr)
                    put("organisasjonsnummer", orgnr)
                    put("vedtaksperiodeId", "p2")
                    put("behovOpprettet", behovOpprettet.toString())
                }
            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                oppslagstjeneste = { riskNeed ->
                    buildJsonObject {
                        put("felt1", "jauda")
                    }
                },
                disableWebEndpoints = true
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("p2", this.vedtaksperiodeId)
            assertEquals("jauda", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `EnTilEnOppslagsApp med minimum iterasjon=2 svarer ikke paa RiskNeed(iter=1)`() {
        startOppslagsApp(
            innkommendeMeldinger = listOf(
                buildJsonObject {
                    put("type", "RiskNeed")
                    put("iterasjon", 1)
                    put("fnr", fnr)
                    put("organisasjonsnummer", orgnr)
                    put("vedtaksperiodeId", "iter-1")
                    put("behovOpprettet", behovOpprettet.toString())
                },
                buildJsonObject {
                    put("type", "RiskNeed")
                    put("iterasjon", 2)
                    put("fnr", fnr)
                    put("organisasjonsnummer", orgnr)
                    put("vedtaksperiodeId", "iter-2")
                    put("behovOpprettet", behovOpprettet.toString())
                }

            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                interesse = Interesse.riskNeedMedMinimum(2),
                oppslagstjeneste = { riskNeed ->
                    buildJsonObject {
                        put("felt1", riskNeed.vedtaksperiodeId)
                    }
                },
                disableWebEndpoints = true
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("iter-2", this.vedtaksperiodeId)
            assertEquals("iter-2", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
        }
    }


    private fun ventPaaProduserteMeldinger(minimumAntall: Int = 1) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                Assertions.assertTrue(producedMessages.size >= minimumAntall)
            }
    }

    private fun startOppslagsApp(innkommendeMeldinger: List<JsonObject>, app: OppslagsApp) {
        val riskConsumer = mockk<KafkaConsumer<String, JsonObject>>()
        val riskProducer = mockk<KafkaProducer<String, JsonObject>>()
        every { riskConsumer.subscribe(listOf(riskRiverTopic)) } just Runs

        producedMessages.clear()
        every { riskConsumer.subscribe(listOf(riskRiverTopic)) } just Runs
        val records = innkommendeMeldinger.map {
            ConsumerRecord(
                riskRiverTopic, partition, 1,
                "envedtaksperiodeid", it
            )
        }
        every { riskConsumer.poll(Duration.ZERO) } returns ConsumerRecords(
            mapOf(
                riverTopicPartition to records
            )
        ) andThenThrows Done()//ConsumerRecords(mapOf(riverTopicPartition to emptyList()))
        every { riskProducer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>() andThenThrows IllegalStateException(
            "no more please!"
        )

        app.overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = riskConsumer,
                kafkaProducer = riskProducer
            )
        )
        GlobalScope.launch { app.start() }
    }

}