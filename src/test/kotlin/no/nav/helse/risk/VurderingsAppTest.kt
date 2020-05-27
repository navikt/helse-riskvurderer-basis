package no.nav.helse.risk

import io.mockk.*
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
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VurderingsAppTest {

    private val partition = 0
    private val riverTopicPartition = TopicPartition(riskRiverTopic, partition)
    private val json = Json(JsonConfiguration.Stable)

    class Done : RuntimeException()

    @Test
    fun `VurderingsApp gjør vurdering basert på to meldinger`() {
        val producer = mockk<KafkaProducer<String, JsonObject>>()
        val consumer = mockk<KafkaConsumer<String, JsonObject>>()

        val fnr = "01017000000"
        val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
        val orgnr = "123456789"

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

        fun vurderer(meldinger: List<JsonObject>): Vurdering {
            val riskNeed = meldinger.finnRiskNeed()!!
            val data = meldinger.finnOppslagsresultat("testdata")!!.jsonObject
            return Vurdering(
                score = 6,
                vekt = 5,
                begrunnelser = listOf("${riskNeed.organisasjonsnummer}/${data["felt-1"]!!.content}"))
        }

        val app = VurderingsApp(
            kafkaClientId = "testvurderer",
            vurderer = ::vurderer,
            interessertI = listOf(
                Interesse.riskNeed(1),
                Interesse.oppslagsresultat("testdata"))
        ).ovverrideKafkaEnvironment(KafkaRiverEnvironment(
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
        assertEquals(1, producedMessages.size)
        val vurdering = json.fromJson(Vurderingsmelding.serializer(), producedMessages.first().value())
        assertEquals(vedtaksperiodeid, vurdering.vedtaksperiodeId)
        assertEquals(1, vurdering.begrunnelser.size)
        assertEquals("$orgnr/verdi-1", vurdering.begrunnelser.first())

    }


}