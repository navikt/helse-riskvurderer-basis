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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals

class VurderingsAppIkkePaakrevdHvisTest {
    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)
    private val json = JsonRisk

    val fnr = "01017000000"
    val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
    val orgnr = "123456789"

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    val trengerIkkeEkstrainfo = "trenger ikke ekstrainfo"

    @Test
    fun `mangler ekstrainfo-oppslag`() {
        testMedInnkommendeOgAssertions(
            innkommendeMeldinger = listOf(
                riskNeed,
                testdataMed(felt1 = "verdi-1")
            ),
            assertOnProducedMessages = { producedMessages ->
                println(producedMessages)
                assertEquals(0, producedMessages.size)
            }
        )
    }

    @Test
    fun `trenger ikke ekstrainfo-oppslag`() {
        testMedInnkommendeOgAssertions(
            innkommendeMeldinger = listOf(
                riskNeed,
                testdataMed(felt1 = trengerIkkeEkstrainfo)
            ),
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals("$orgnr/$trengerIkkeEkstrainfo", vurdering.begrunnelser.first())
                assertEquals(mapOf("ekstrainfo" to "{}"), vurdering.metadata)
            }
        )
    }

    @Test
    fun `har ekstrainfo-oppslag`() {
        testMedInnkommendeOgAssertions(
            innkommendeMeldinger = listOf(
                riskNeed,
                testdataMed(felt1 = "whatever"),
                buildJsonObject {
                    put("type", "oppslagsresultat")
                    put("infotype", "ekstrainfo")
                    put("vedtaksperiodeId", vedtaksperiodeid)
                    put("data", buildJsonObject {
                        put("hei", "på deg")
                    })
                }
            ),
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals("$orgnr/whatever", vurdering.begrunnelser.first())
                assertEquals(mapOf("ekstrainfo" to "{\"hei\":\"på deg\"}"), vurdering.metadata)
            }
        )
    }

    @Test
    fun `trenger ikke ekstrainfo-oppslag men kommer med allikevel siden den kommer foer testdata`() {
        testMedInnkommendeOgAssertions(
            innkommendeMeldinger = listOf(
                riskNeed,
                buildJsonObject {
                    put("type", "oppslagsresultat")
                    put("infotype", "ekstrainfo")
                    put("vedtaksperiodeId", vedtaksperiodeid)
                    put("data", buildJsonObject {
                        put("er", "her allikevel")
                    })
                },
                testdataMed(felt1 = trengerIkkeEkstrainfo)
            ),
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals("$orgnr/$trengerIkkeEkstrainfo", vurdering.begrunnelser.first())
                assertEquals(mapOf("ekstrainfo" to "{\"er\":\"her allikevel\"}"), vurdering.metadata)
            }
        )
    }

    @Test
    fun `trenger ikke ekstrainfo-oppslag og blir heller ikke med siden den kommer etter testdata`() {
        testMedInnkommendeOgAssertions(
            innkommendeMeldinger = listOf(
                riskNeed,
                testdataMed(felt1 = trengerIkkeEkstrainfo),
                buildJsonObject {
                    put("type", "oppslagsresultat")
                    put("infotype", "ekstrainfo")
                    put("vedtaksperiodeId", vedtaksperiodeid)
                    put("data", buildJsonObject {
                        put("er", "her allikevel")
                    })
                }
            ),
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals("$orgnr/$trengerIkkeEkstrainfo", vurdering.begrunnelser.first())
                assertEquals(mapOf("ekstrainfo" to "{}"), vurdering.metadata)
            }
        )
    }


    fun testdataMed(felt1: String) = buildJsonObject {
        put("type", "oppslagsresultat")
        put("infotype", "testdata")
        put("vedtaksperiodeId", vedtaksperiodeid)
        put("data", buildJsonObject {
            put("felt-1", felt1)
            put("c", buildJsonArray { add(1); add(2); add(3) })
        })
    }

    val behovOpprettet = LocalDateTime.now()
    val riskNeed = buildJsonObject {
        put("type", "RiskNeed")
        put("iterasjon", 1)
        put("fnr", fnr)
        put("organisasjonsnummer", orgnr)
        put("vedtaksperiodeId", vedtaksperiodeid)
        put("behovOpprettet", behovOpprettet.toString())
    }

    fun testMedInnkommendeOgAssertions(
        innkommendeMeldinger: List<JsonObject>,
        assertOnProducedMessages: (List<JsonObject>) -> Unit
    ) {
        testMedVurdererOgAssertions(
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata"),
                Interesse.oppslagsresultat("ekstrainfo", ikkePaakrevdHvis = { meldinger ->
                    meldinger.finnOppslagsresultat("testdata")!!
                        .jsonObject["felt-1"]!!.jsonPrimitive.content == trengerIkkeEkstrainfo
                })
            ),
            vurderer = { meldinger ->
                val riskNeed = meldinger.finnRiskNeed()!!
                val data = meldinger.finnOppslagsresultat("testdata")!!.jsonObject
                val ekstrainfo: JsonObject = if (data["felt-1"]!!.jsonPrimitive.content != trengerIkkeEkstrainfo) {
                    meldinger.finnOppslagsresultat("ekstrainfo")!!.jsonObject
                } else {
                    meldinger.finnOppslagsresultat("ekstrainfo")?.jsonObject ?: buildJsonObject { }
                }
                VurderingBuilder()
                    .begrunnelse("${riskNeed.organisasjonsnummer}/${data["felt-1"]!!.jsonPrimitive.content}", 6)
                    .leggVedMetadata("ekstrainfo", ekstrainfo.toString())
                    .build(5)
            },
            innkommendeMeldinger = innkommendeMeldinger,
            assertOnProducedMessages = assertOnProducedMessages
        )
    }

    fun testMedVurdererOgAssertions(
        interessertI: List<Interesse>,
        vurderer: (List<JsonObject>) -> Vurdering,
        innkommendeMeldinger: List<JsonObject>,
        assertOnProducedMessages: (List<JsonObject>) -> Unit
    ) {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = mockk<KafkaConsumer<String, JsonObject>>()

        val app = VurderingsApp(
            kafkaClientId = "testvurderer",
            vurderer = vurderer,
            interessertI = interessertI,
            windowTimeInSeconds = 3
        ).overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = consumer,
                kafkaProducer = producer
            )
        )

        every { consumer.subscribe(listOf(riskRiverTopic)) } just Runs

        every { consumer.poll(Duration.ZERO) } returns ConsumerRecords(
            mapOf(
                riverTopicPartition to innkommendeMeldinger.map {
                    ConsumerRecord(riskRiverTopic, partition, 1, "envedtaksperiodeid", it)
                }
            )
        ) andThenThrows VurderingsAppTest.Done()

        val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
        every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>()

        GlobalScope.launch {
            app.start()
        }

        Thread.sleep(4000)

        assertOnProducedMessages(producedMessages.map { it.value() })
    }
}
