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
import kotlin.test.assertTrue

@FlowPreview
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OppslagsAppTest {

    private val JSON = JsonRisk
    val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic(), partition)

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
    fun `Default OppslagsApp - 3 scenarier`() {
        val periodeTilMeldinger = mutableMapOf<String, List<JsonObject>>()

        val innkommendeMeldinger = listOf(
            /* "111" skal ignoreres siden:
                default OppslagsApp har ignoreIfNotPresent = interessertI.filter { it.type == typeRiskNeed }
                og vår oppslagsapp krever: Interesse.riskNeedMedMinimum(2)
            */
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 1)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("riskNeedId", "RISK-NEED-ID-001")
                put("vedtaksperiodeId", "111")
                put("behovOpprettet", behovOpprettet.toString())
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "kobling")
                put("riskNeedId", "RISK-NEED-ID-001")
                put("vedtaksperiodeId", "111")
                put("behovOpprettet", behovOpprettet.toString())
                put("data", buildJsonObject {
                    put("key", "data_value")
                })
            },

            // "222" emittes "early" siden den er komplett:
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 2)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("riskNeedId", "RISK-NEED-ID-002")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "kobling")
                put("vedtaksperiodeId", "222")
                put("riskNeedId", "RISK-NEED-ID-002")
                put("behovOpprettet", behovOpprettet.toString())
                put("data", buildJsonObject {
                    put("key", "data_value")
                })
            },

            // "333" er IKKE komplett men har påkrevd RiskNeed(2) og emittes av schedulern etter 5 sek.
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 2)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("vedtaksperiodeId", "333")
                put("riskNeedId", "RISK-NEED-ID-003")
                //put("riskNeedId", "RISK-NEED-ID-003")
                put("behovOpprettet", behovOpprettet.toString())
            },

            buildJsonObject {
                put("something", "else")
            }
        )

        startOppslagsApp(
            innkommendeMeldinger = innkommendeMeldinger,
            app = OppslagsApp(
                kafkaClientId = "whatever",
                infotype = "oppslag2",
                interessertI = listOf(
                    Interesse.riskNeedMedMinimum(2),
                    Interesse.oppslagsresultat("kobling")
                ),
                oppslagstjeneste = { meldinger ->
                    val riskNeed = meldinger.finnRiskNeed()!!
                    periodeTilMeldinger[riskNeed.vedtaksperiodeId] = meldinger
                    println(meldinger.toString())
                    val kobling = meldinger.finnOppslagsresultat("kobling")
                    buildJsonObject {
                        put("felt1", riskNeed.vedtaksperiodeId)
                        put("har_kobling", (kobling != null))
                    }
                },
                windowTimeInSeconds = 1,
                disableWebEndpoints = true,
            )
        )
        Awaitility.await()
            .atMost(Duration.ofSeconds(4))
            .pollDelay(Duration.ofMillis(100))
            .untilAsserted {
                Assertions.assertTrue(periodeTilMeldinger.size >= 1)
            }
        assertEquals(1, periodeTilMeldinger.size)

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollDelay(Duration.ofMillis(100))
            .untilAsserted {
                Assertions.assertTrue(periodeTilMeldinger.size >= 2)
            }
        //Thread.sleep(6000) // Because BufferedRiver says schedulerIntervalInSeconds = 5
        assertEquals(2, periodeTilMeldinger.size)

        periodeTilMeldinger["222"].apply {
            assertEquals(2, this!!.size)
            val innkommende222 =
                innkommendeMeldinger.filter { it["vedtaksperiodeId"]?.jsonPrimitive?.contentOrNull == "222" }
            assertEquals(innkommende222.size, this.size)
            innkommende222.forEach { innkommende ->
                assertTrue(this.contains(innkommende))
            }
        }

        periodeTilMeldinger["333"].apply {
            assertEquals(1, this!!.size)
            val innkommende333 =
                innkommendeMeldinger.filter { it["vedtaksperiodeId"]?.jsonPrimitive?.contentOrNull == "333" }
            assertEquals(innkommende333.size, this.size)
            innkommende333.forEach { innkommende ->
                assertTrue(this.contains(innkommende))
            }
        }

        val answers = producedMessages.map { it.value() }

        assertEquals(2, answers.size)

        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("222", this.vedtaksperiodeId)
            assertEquals("RISK-NEED-ID-002", this.riskNeedId, "skal arve RiskNeedId fra RiskNeed-meldinga")
            assertEquals("222", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
            assertEquals(true, this.data.jsonObject["har_kobling"]!!.jsonPrimitive.boolean)
        }

        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers[1]).apply {
            assertEquals("333", this.vedtaksperiodeId)
            assertEquals("RISK-NEED-ID-003", this.riskNeedId, "skal arve RiskNeedId fra RiskNeed-meldinga")
            assertEquals("333", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
            assertEquals(false, this.data.jsonObject["har_kobling"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `OppslagsApp med aggregering på riskNeedId - 3 scenarier`() {
        val periodeTilMeldinger = mutableMapOf<String, List<JsonObject>>()

        val innkommendeMeldinger = listOf(
            /* "RISK-NEED-ID-001" skal ignoreres siden:
                default OppslagsApp har ignoreIfNotPresent = interessertI.filter { it.type == typeRiskNeed }
                og vår oppslagsapp krever: Interesse.riskNeedMedMinimum(2)
            */
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 1)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("riskNeedId", "RISK-NEED-ID-001")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "kobling")
                put("riskNeedId", "RISK-NEED-ID-001")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
                put("data", buildJsonObject {
                    put("key", "data_value")
                })
            },

            // "RISK-NEED-ID-002" emittes "early" siden den er komplett:
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "kobling")
                put("riskNeedId", "RISK-NEED-ID-002-EARLIER-ATTEMPT")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
                put("data", buildJsonObject {
                    put("key", "WRONG_DATA_VALUE_BECAUSE_riskNeedId_DOES_NOT_MATCH")
                })
            },
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 2)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("riskNeedId", "RISK-NEED-ID-002")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
            },
            buildJsonObject {
                put("type", "oppslagsresultat")
                put("infotype", "kobling")
                put("riskNeedId", "RISK-NEED-ID-002")
                put("vedtaksperiodeId", "222")
                put("behovOpprettet", behovOpprettet.toString())
                put("data", buildJsonObject {
                    put("key", "data_value")
                })
            },

            // "NEED-ID-003" er IKKE komplett men har påkrevd RiskNeed(2) og emittes av schedulern etter 5 sek.
            buildJsonObject {
                put("type", "RiskNeed")
                put("iterasjon", 2)
                put("fnr", fnr)
                put("organisasjonsnummer", orgnr)
                put("vedtaksperiodeId", "222")
                put("riskNeedId", "RISK-NEED-ID-003")
                put("behovOpprettet", behovOpprettet.toString())
            },

            buildJsonObject {
                put("something", "else")
            }
        )

        fun RiskNeed.periodeOgId() : String = this.vedtaksperiodeId + "/" + this.riskNeedId

        startOppslagsApp(
            innkommendeMeldinger = innkommendeMeldinger,
            app = OppslagsApp(
                kafkaClientId = "whatever",
                infotype = "oppslag2",
                interessertI = listOf(
                    Interesse.riskNeedMedMinimum(2),
                    Interesse.oppslagsresultat("kobling")
                ),
                oppslagstjeneste = { meldinger ->
                    val riskNeed = meldinger.finnRiskNeed()!!
                    periodeTilMeldinger[riskNeed.periodeOgId()] = meldinger
                    println(meldinger.toString())
                    val kobling = meldinger.finnOppslagsresultat("kobling")
                    buildJsonObject {
                        put("felt1", riskNeed.vedtaksperiodeId)
                        put("har_kobling", (kobling != null))
                        put("kobling-key", kobling?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull)
                    }
                },
                windowTimeInSeconds = 1,
                disableWebEndpoints = true,
                sessionAggregationFieldName = riskNeedIdKey,
            )
        )
        Awaitility.await()
            .atMost(Duration.ofSeconds(4))
            .pollDelay(Duration.ofMillis(100))
            .untilAsserted {
                Assertions.assertTrue(periodeTilMeldinger.size >= 1)
            }
        assertEquals(1, periodeTilMeldinger.size)

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollDelay(Duration.ofMillis(100))
            .untilAsserted {
                Assertions.assertTrue(periodeTilMeldinger.size >= 2)
            }
        //Thread.sleep(6000) // Because BufferedRiver says schedulerIntervalInSeconds = 5
        assertEquals(2, periodeTilMeldinger.size)

        periodeTilMeldinger["222/RISK-NEED-ID-002"].apply {
            assertEquals(2, this!!.size)
            val innkommende002 =
                innkommendeMeldinger.filter { it["riskNeedId"]?.jsonPrimitive?.contentOrNull == "RISK-NEED-ID-002" }
            assertEquals(innkommende002.size, this.size)
            innkommende002.forEach { innkommende ->
                assertTrue(this.contains(innkommende))
            }
        }

        periodeTilMeldinger["222/RISK-NEED-ID-003"].apply {
            assertEquals(1, this!!.size)
            val innkommende003 =
                innkommendeMeldinger.filter { it["riskNeedId"]?.jsonPrimitive?.contentOrNull == "RISK-NEED-ID-003" }
            assertEquals(innkommende003.size, this.size)
            innkommende003.forEach { innkommende ->
                assertTrue(this.contains(innkommende))
            }
        }

        val answers = producedMessages.map { it.value() }

        assertEquals(2, answers.size)

        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers.first()).apply {
            assertEquals("222", this.vedtaksperiodeId)
            assertEquals("RISK-NEED-ID-002", this.riskNeedId, "skal arve RiskNeedId fra RiskNeed-meldinga")
            assertEquals("222", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
            assertEquals(true, this.data.jsonObject["har_kobling"]!!.jsonPrimitive.boolean)
            assertEquals("data_value", this.data.jsonObject["kobling-key"]!!.jsonPrimitive.content)
        }

        JSON.decodeFromJsonElement(Oppslagsmelding.serializer(), answers[1]).apply {
            assertEquals("222", this.vedtaksperiodeId)
            assertEquals("RISK-NEED-ID-003", this.riskNeedId, "skal arve RiskNeedId fra RiskNeed-meldinga")
            assertEquals("222", this.data.jsonObject["felt1"]!!.jsonPrimitive.content)
            assertEquals(false, this.data.jsonObject["har_kobling"]!!.jsonPrimitive.boolean)
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

    private fun startOppslagsApp(innkommendeMeldinger: List<JsonObject>, app: OppslagsApp) {
        val riskConsumer = mockk<KafkaConsumer<String, JsonObject>>()
        val riskProducer = mockk<KafkaProducer<String, JsonObject>>()
        every { riskConsumer.subscribe(listOf(riskRiverTopic())) } just Runs

        producedMessages.clear()
        every { riskConsumer.subscribe(listOf(riskRiverTopic())) } just Runs
        val records = innkommendeMeldinger.map {
            ConsumerRecord(
                riskRiverTopic(), partition, 1,
                "envedtaksperiodeid", it
            )
        }
        every { riskConsumer.poll(Duration.ZERO) } returns ConsumerRecords(
            mapOf(
                riverTopicPartition to records
            )
        ) andThenThrows EnTilEnOppslagsAppTest.Done()

        every { riskProducer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>() //andThenThrows IllegalStateException("no more please!")

        app.overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = riskConsumer,
                kafkaProducer = riskProducer
            )
        )
        GlobalScope.launch { app.start() }
    }

}