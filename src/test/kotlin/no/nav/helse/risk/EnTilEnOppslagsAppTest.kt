package no.nav.helse.risk

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.prometheus.client.CollectorRegistry
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

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class EnTilEnOppslagsAppTest {

    private val JSON = Json(JsonConfiguration.Stable)
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

    @Test
    fun `EnTilEnOppslagsApp med default svarer paa RiskNeed(iter=1)`() {
        startApp(
            innkommendeMeldinger = listOf(
                json {
                    "type" to "RiskNeed"
                    "iterasjon" to 1
                    "fnr" to fnr
                    "organisasjonsnummer" to orgnr
                    "vedtaksperiodeId" to "p1"
                    "behovOpprettet" to behovOpprettet.toString()
                }
            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                oppslagstjeneste = { riskNeed ->
                    json {
                        "felt1" to "data1"
                    }
                }
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.fromJson(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("p1", this.vedtaksperiodeId)
            assertEquals("data1", this.data.jsonObject["felt1"]!!.content)
        }
    }

    @Test
    fun `EnTilEnOppslagsApp med default svarer paa RiskNeed(iter=2)`() {
        startApp(
            innkommendeMeldinger = listOf(
                json {
                    "type" to "RiskNeed"
                    "iterasjon" to 2
                    "fnr" to fnr
                    "organisasjonsnummer" to orgnr
                    "vedtaksperiodeId" to "p2"
                    "behovOpprettet" to behovOpprettet.toString()
                }
            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                oppslagstjeneste = { riskNeed ->
                    json {
                        "felt1" to "jauda"
                    }
                }
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.fromJson(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("p2", this.vedtaksperiodeId)
            assertEquals("jauda", this.data.jsonObject["felt1"]!!.content)
        }
    }

    @Test
    fun `EnTilEnOppslagsApp med minimum iterasjon=2 svarer ikke paa RiskNeed(iter=1)`() {
        startApp(
            innkommendeMeldinger = listOf(
                json {
                    "type" to "RiskNeed"
                    "iterasjon" to 1
                    "fnr" to fnr
                    "organisasjonsnummer" to orgnr
                    "vedtaksperiodeId" to "iter-1"
                    "behovOpprettet" to behovOpprettet.toString()
                },
                json {
                    "type" to "RiskNeed"
                    "iterasjon" to 2
                    "fnr" to fnr
                    "organisasjonsnummer" to orgnr
                    "vedtaksperiodeId" to "iter-2"
                    "behovOpprettet" to behovOpprettet.toString()
                }

            ),
            app = EnTilEnOppslagsApp(
                kafkaClientId = "whatever",
                interesse = Interesse.riskNeedMedMinimum(2),
                oppslagstjeneste = { riskNeed ->
                    json {
                        "felt1" to riskNeed.vedtaksperiodeId
                    }
                }
            )
        )
        ventPaaProduserteMeldinger()
        val answers = producedMessages.map { it.value() }
        JSON.fromJson(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("iter-2", this.vedtaksperiodeId)
            assertEquals("iter-2", this.data.jsonObject["felt1"]!!.content)
        }
    }


    private fun ventPaaProduserteMeldinger(minimumAntall: Int = 1) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                Assertions.assertTrue(producedMessages.size >= minimumAntall)
            }
    }


    private fun startApp(innkommendeMeldinger: List<JsonObject>, app:OppslagsApp) {
        val riskConsumer = mockk<KafkaConsumer<String, JsonObject>>()
        val riskProducer = mockk<KafkaProducer<String, JsonObject>>()
        every { riskConsumer.subscribe(listOf(riskRiverTopic)) } just Runs

        producedMessages.clear()
        every { riskConsumer.subscribe(listOf(riskRiverTopic)) } just Runs
        val records = innkommendeMeldinger.map {
            ConsumerRecord(riskRiverTopic, partition, 1,
                "envedtaksperiodeid", it)
        }
        every { riskConsumer.poll(Duration.ZERO) } returns ConsumerRecords(mapOf(riverTopicPartition to records
        )) andThenThrows Done()//ConsumerRecords(mapOf(riverTopicPartition to emptyList()))
        every { riskProducer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>() andThenThrows IllegalStateException("no more please!")

        app.overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = riskConsumer,
                kafkaProducer = riskProducer)
        )
        GlobalScope.launch { app.start() }
    }

}