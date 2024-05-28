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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

val baseMetaData = mapOf(
    "podname" to "unknownHost",
    "appname" to "unknownApp",
    "imagename" to "unknownImage",
)

@FlowPreview
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class VurderingsAppTest {
    init {
        Sanity.setSkipSanityChecksForProduction()
    }

    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic(), partition)
    private val json = JsonRisk

    class Done : RuntimeException()

    val fnr = "01017000000"
    val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
    val riskNeedId = "0000-9999-8888-7777"
    val orgnr = "123456789"

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    @Test
    fun `VurderingsApp gjør vurdering basert på to meldinger`() {
        testMedVurdererOgAssertions(
            vurderer = { meldinger ->
                val riskNeed = meldinger.finnRiskNeed()!!
                val data = meldinger.finnOppslagsresultat("testdata")!!.jsonObject
                VurderingBuilder().apply {
                    nySjekk(vekt = 10) { resultat("${riskNeed.organisasjonsnummer}/${data["felt-1"]!!.jsonPrimitive.content}", 6) }
                    passertSjekk(vekt = 10, "Ellers greit")
                    leggVedMetadata("ekstraGreier", "12345")
                }.build()
            },
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals(1, vurdering.begrunnelser().size)
                assertEquals("$orgnr/verdi-1", vurdering.begrunnelser().first())
                assertEquals(1, vurdering.passerteSjekker().size)
                assertEquals("Ellers greit", vurdering.passerteSjekker().first())
                assertTrue(vurdering.begrunnelserSomAleneKreverManuellBehandling().isEmpty())
                assertEquals(riskNeedId, vurdering.riskNeedId)
                Assertions.assertEquals(
                    listOf(
                        Sjekkresultat(
                            id = "1",
                            tekst = "$orgnr/verdi-1",
                            begrunnelse = "$orgnr/verdi-1",
                            score = 6,
                            vekt = 10,
                            kreverManuellBehandling = false
                        ),
                        Sjekkresultat(
                            id = "2",
                            tekst = "Ellers greit",
                            begrunnelse = "Ellers greit",
                            score = 0,
                            vekt = 10,
                            kreverManuellBehandling = false
                        ),
                    ), vurdering.sjekkresultater
                )
                assertEquals("12345", vurdering.metadata!!["ekstraGreier"])
            }
        )
    }

    @Test
    fun `showstopper-flagg blir med i vurderingen`() {
        testMedVurdererOgAssertions(
            vurderer = { meldinger ->
                meldinger.finnRiskNeed()!!
                meldinger.finnOppslagsresultat("testdata")!!.jsonObject
                VurderingBuilder().apply {
                    nySjekk(vekt = 10) { kreverManuellBehandling("showstopper") }
                }.build(10)
            },
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals(1, vurdering.begrunnelser().size)
                assertEquals("showstopper", vurdering.begrunnelser().first())
                assertEquals(1, vurdering.begrunnelserSomAleneKreverManuellBehandling().size)
                assertEquals("showstopper", vurdering.begrunnelserSomAleneKreverManuellBehandling().first())
                vurdering.sjekkresultater.apply {
                    assertEquals(1, size)
                    first().apply {
                        assertEquals("showstopper", tekst)
                        assertEquals(10, score)
                        assertEquals(10, vekt)
                        assertTrue(kreverManuellBehandling)
                    }
                }
                assertEquals(baseMetaData, vurdering.metadata)
            }
        )
    }

    @Test
    fun `grunnlag blir ikke med i analyse`() {
        testMedVurdererOgAssertions(
            ekstraVedtaksperiodeIder = listOf("analyse:123"),
            vurderer = { meldinger ->
                meldinger.finnRiskNeed()!!
                meldinger.finnOppslagsresultat("testdata")!!.jsonObject
                VurderingBuilder().apply {
                    nySjekk(vekt = 10) {
                        resultat(
                            tekst = "treff",
                            score = 6,
                            kreverManuellBehandling = false,
                            grunnlag = SjekkresultatGrunnlag(
                                versjon = 10,
                                data = buildJsonObject {
                                    put("ligger_til_grunn", "noe_greier")
                                }
                            )
                        )
                    }
                }.build(10)
            },
            assertOnProducedMessages = { producedMessages ->
                producedMessages.map { json.decodeFromJsonElement(Vurderingsmelding.serializer(), it) }.apply {
                    println(this)
                    assertEquals(2, size)
                    find { it.vedtaksperiodeId == vedtaksperiodeid }!!.apply {
                        assertEquals(1, sjekkresultater.size)
                        assertEquals(buildJsonObject {
                            put("ligger_til_grunn", "noe_greier")
                        }, sjekkresultater.first().grunnlag!!.data)
                    }
                    find { it.vedtaksperiodeId == "analyse:123" }!!.apply {
                        assertEquals(1, sjekkresultater.size)
                        assertNull(sjekkresultater.first().grunnlag, "grunnlag skal ikke legges ved i analyse")
                    }
                }
            }
        )
    }

    fun testMedVurdererOgAssertions(
        vurderer: (List<JsonObject>) -> Vurdering,
        assertOnProducedMessages: (List<JsonObject>) -> Unit,
        ekstraVedtaksperiodeIder: List<String> = emptyList()
    ) {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = mockk<KafkaConsumer<String, JsonObject>>()

        val behovOpprettet = LocalDateTime.now()


        val vedtaksperiodeIder = listOf(vedtaksperiodeid) + ekstraVedtaksperiodeIder

        val recs = vedtaksperiodeIder.flatMap { ønsketVedtaksperiodeId ->
            listOf(
                ConsumerRecord(riskRiverTopic(), partition, 1,
                    "envedtaksperiodeid",
                    buildJsonObject {
                        put("type", "RiskNeed")
                        put("iterasjon", 1)
                        put("fnr", fnr)
                        put("riskNeedId", riskNeedId)
                        put("organisasjonsnummer", orgnr)
                        put("vedtaksperiodeId", ønsketVedtaksperiodeId)
                        put("behovOpprettet", behovOpprettet.toString())
                    }),
                ConsumerRecord(riskRiverTopic(), partition, 1,
                    "envedtaksperiodeid",
                    buildJsonObject {
                        put("type", "oppslagsresultat")
                        put("infotype", "testdata")
                        put("riskNeedId", riskNeedId)
                        put("vedtaksperiodeId", ønsketVedtaksperiodeId)
                        put("data", buildJsonObject {
                            put("felt-1", "verdi-1")
                            put("c", buildJsonArray { add(1); add(2); add(3) })
                        })
                    })
            )
        }

        val app = VurderingsApp(
            kafkaClientId = "testvurderer",
            vurderer = vurderer,
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata")
            ),
            disableWebEndpoints = true
        ).overrideKafkaEnvironment(
            KafkaRiverEnvironment(
                kafkaConsumer = consumer,
                kafkaProducer = producer
            )
        )

        every { consumer.subscribe(listOf(riskRiverTopic())) } just Runs

        every { consumer.poll(Duration.ZERO) } returns ConsumerRecords(
            mapOf(
                riverTopicPartition to recs
            )
        ) andThenThrows Done()

        val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
        every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>()

        GlobalScope.launch {
            app.start()
        }

        await()
            .atMost(Duration.ofSeconds(10))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                assertTrue(producedMessages.size >= vedtaksperiodeIder.size)
                println(producedMessages.toString())

            }
        assertOnProducedMessages(producedMessages.map { it.value() })
    }


}