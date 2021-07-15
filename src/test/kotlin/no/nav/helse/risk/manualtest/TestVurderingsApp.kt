package no.nav.helse.risk.manualtest

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.FlowPreview
import kotlinx.serialization.json.*
import no.nav.helse.risk.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future

private val partition = 0
private val riverTopicPartition = TopicPartition(riskRiverTopic(), partition)

val fnr = "01017000000"
val vedtaksperiodeid = "33745ddf-1362-443d-8c9f-7667325e8dc6"
val orgnr = "123456789"

@FlowPreview
fun main() {
    Sanity.setSkipSanityChecksForProduction()
    val producer = mockk<KafkaProducer<String, JsonObject>>()
    val consumer = mockk<KafkaConsumer<String, JsonObject>>()

    val behovOpprettet = LocalDateTime.now()
    val rec1 = ConsumerRecord(riskRiverTopic(), partition, 1,
        "envedtaksperiodeid",
        buildJsonObject {
            put("type", "RiskNeed")
            put("iterasjon", 1)
            put("fnr", fnr)
            put("organisasjonsnummer", orgnr)
            put("vedtaksperiodeId", vedtaksperiodeid)
            put("behovOpprettet", behovOpprettet.toString())
        })
    val rec2 = ConsumerRecord(riskRiverTopic(), partition, 1,
        "envedtaksperiodeid",
        buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "testdata")
            put("vedtaksperiodeId", vedtaksperiodeid)
            put("data", buildJsonObject {
                put("felt-1", "verdi-1")
                put("c", buildJsonArray { add(1); add(2); add(3) })
            })
        })

    val app = VurderingsApp(
        kafkaClientId = "testvurderer",
        vurderer = { meldinger ->
            val riskNeed = meldinger.finnRiskNeed()!!
            val data = meldinger.finnOppslagsresultat("testdata")!!.jsonObject
            VurderingBuilder().apply {
                nySjekk(vekt = 5) {
                    resultat("${riskNeed.organisasjonsnummer}/${data["felt-1"]!!.jsonPrimitive.content}", 6)
                }
                leggVedMetadata("ekstraGreier", "12345")
            }.build(5)
        },
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

    val producedMessages = mutableListOf<ProducerRecord<String, JsonObject>>()
    every { producer.send(capture(producedMessages)) } returns mockk<Future<RecordMetadata>>()
    every { consumer.subscribe(listOf(riskRiverTopic())) } just Runs
    every { consumer.poll(Duration.ZERO) } answers {
        Thread.sleep(1000)
        ConsumerRecords(
            mapOf(
                riverTopicPartition to listOf(
                    rec1, rec2
                )
            )
        )
    }

    app.start()
}

