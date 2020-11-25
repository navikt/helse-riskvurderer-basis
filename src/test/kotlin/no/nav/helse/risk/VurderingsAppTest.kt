package no.nav.helse.risk

import io.mockk.*
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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class VurderingsAppTest {

    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)
    private val json = Json(JsonConfiguration.Stable)

    class Done : RuntimeException()

    val fnr = "01017000000"
    val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
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
                VurderingBuilder()
                    .begrunnelse("${riskNeed.organisasjonsnummer}/${data["felt-1"]!!.content}", 6)
                    .leggVedMetadata("ekstraGreier", "12345")
                    .build(5)
            },
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.fromJson(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(6, vurdering.score)
                assertEquals(5, vurdering.vekt)
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals(1, vurdering.begrunnelser.size)
                assertEquals("$orgnr/verdi-1", vurdering.begrunnelser.first())
                assertTrue(vurdering.begrunnelserSomAleneKreverManuellBehandling!!.isEmpty())
                assertEquals("12345", vurdering.metadata!!["ekstraGreier"])
            }
        )
    }

    @Test
    fun `showstopper-flagg blir med i vurderingen`() {
        testMedVurdererOgAssertions(
            vurderer = { meldinger ->
                val riskNeed = meldinger.finnRiskNeed()!!
                val data = meldinger.finnOppslagsresultat("testdata")!!.jsonObject
                VurderingBuilder()
                    .begrunnelseSomKreverManuellBehandling("showstopper")
                    .build(10)

            },
            assertOnProducedMessages = { producedMessages ->
                assertEquals(1, producedMessages.size)
                val vurdering = json.fromJson(Vurderingsmelding.serializer(), producedMessages.first())
                assertEquals(10, vurdering.score)
                assertEquals(10, vurdering.vekt)
                assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
                assertEquals(1, vurdering.begrunnelser.size)
                assertEquals("showstopper", vurdering.begrunnelser.first())
                assertEquals(1, vurdering.begrunnelserSomAleneKreverManuellBehandling!!.size)
                assertEquals("showstopper", vurdering.begrunnelserSomAleneKreverManuellBehandling!!.first())
                assertTrue(vurdering.metadata!!.isEmpty())
            }
        )
    }

    fun testMedVurdererOgAssertions(
        vurderer: (List<JsonObject>) -> Vurdering,
        assertOnProducedMessages: (List<JsonObject>) -> Unit
    ) {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = mockk<KafkaConsumer<String, JsonObject>>()

        val behovOpprettet = LocalDateTime.now()
        val rec1 = ConsumerRecord(riskRiverTopic, partition, 1,
            "envedtaksperiodeid",
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
            })
        val rec2 = ConsumerRecord(riskRiverTopic, partition, 1,
            "envedtaksperiodeid",
            json {
                "type" to "oppslagsresultat"
                "infotype" to "testdata"
                "vedtaksperiodeId" to vedtaksperiodeid
                "data" to json {
                    "felt-1" to "verdi-1"
                    "c" to listOf(1, 2, 3)
                }
            })

        val app = VurderingsApp(
            kafkaClientId = "testvurderer",
            vurderer = vurderer,
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata"))
        ).overrideKafkaEnvironment(KafkaRiverEnvironment(
            kafkaConsumer = consumer,
            kafkaProducer = producer
        ))

        every { consumer.subscribe(listOf(riskRiverTopic)) } just Runs

        every { consumer.poll(Duration.ZERO) } returns ConsumerRecords(mapOf(riverTopicPartition to listOf(
            rec1, rec2)
        )) andThenThrows Done()

        val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
        every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>() andThenThrows IllegalStateException("no more please!")

        GlobalScope.launch {
            app.start()
        }

        await()
            .atMost(Duration.ofSeconds(5))
            .pollDelay(Duration.ofMillis(200))
            .untilAsserted {
                assertTrue(producedMessages.size > 0)
                println(producedMessages.toString())

            }
        assertOnProducedMessages(producedMessages.map { it.value() })
    }


}