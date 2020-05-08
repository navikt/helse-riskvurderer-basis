package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import no.nav.helse.buffer.WindowBufferEmitter
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.streams.KafkaStreams
import java.time.Duration
import java.util.*

internal class BufferedRiver(private val kafkaProducer: KafkaProducer<String, JsonObject>,
                             private val kafkaConsumerConfig: Properties,
                             private val topicConfig: TopicAndClientIdHolder,
                             private val interessertITypeInfotype: List<Pair<String, String?>>,
                             vurderer: (List<JsonObject>) -> Vurdering,
                             decryptionJWKS: JWKSet?,
                             windowTimeInSeconds: Long = 5,
                             emitEarlyWhenAllInterestsPresent: Boolean = true,
                             private val kafkaConsumer: KafkaConsumer<String, JsonObject> = KafkaConsumer(kafkaConsumerConfig)
) {

    private val vurderingProducer = VurderingProducer(topicConfig, vurderer, decryptionJWKS)
    private val aggregator = WindowBufferEmitter(
        windowSizeInSeconds = windowTimeInSeconds,
        aggregateAndEmit = ::lagOgSendVurdering,
        scheduleExpiryCheck = true,
        schedulerIntervalInSeconds = 5,
        sessionEarlyExpireCondition = if (emitEarlyWhenAllInterestsPresent) this::isCompleteMessageSet else null)


    private fun isCompleteMessageSet(msgs: List<JsonObject>) =
        isCompleteMessageSetAccordingToInterests(msgs, interessertITypeInfotype)

    fun tearDown() {
        kafkaConsumer.close()
    }

    fun state() = KafkaStreams.State.RUNNING

    suspend fun start() {
        val mangeTilEn: Boolean = interessertITypeInfotype.size > 1
        kafkaConsumer
            .apply { subscribe(listOf(topicConfig.riskRiverTopic)) }
            .asFlow()
            .filterNotNull()
            .filter { (_, value, _) -> value.tilfredsstillerInteresse(interessertITypeInfotype) }
            .filterNotNull()
            .collect { (key, value, timestamp) ->
                if (mangeTilEn) {
                    aggregator.store(value["vedtaksperiodeId"]!!.content, value, timestamp)
                } else {
                    lagOgSendVurdering(listOf(value))
                }
            }
    }

    private fun lagOgSendVurdering(answers: List<JsonObject>): Unit {
        val vedtaksperiodeId = extractUniqueVedtaksperiodeId(answers)
        vurderingProducer.lagVurdering(answers, vedtaksperiodeId)?.also { svar ->
            kafkaProducer.send(ProducerRecord(topicConfig.riskRiverTopic, vedtaksperiodeId, svar))
        }
    }
}

internal fun isCompleteMessageSetAccordingToInterests(msgs: List<JsonObject>, interesser: List<Pair<String, String?>>) =
    msgs.size == interesser.size && (
        interesser.fold(true, { acc, interesse ->
            acc && (null != msgs.find { it.tilfredsstillerInteresse(listOf(interesse)) })
        }))

internal fun extractUniqueVedtaksperiodeId(answers: List<JsonObject>) =
    answers.first()[vedtaksperiodeIdKey]!!.content.apply {
        answers.forEach {
            val neste = it[vedtaksperiodeIdKey]!!.content
            if (neste != this) throw IllegalArgumentException("ulik id: $neste != $this")
        }
    }

@FlowPreview
private fun <K, V> KafkaConsumer<K, V>.asFlow(): Flow<Triple<K, V, Long>> = flow { while (true) emit(poll(Duration.ZERO)) }
    .onEach { if (it.isEmpty) delay(100) }
    .flatMapConcat { it.asFlow() }
    .map { Triple(it.key(), it.value(), it.timestamp()) }