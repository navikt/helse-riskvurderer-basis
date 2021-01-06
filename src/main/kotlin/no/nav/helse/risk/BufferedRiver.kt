package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.helse.buffer.WindowBufferEmittable
import no.nav.helse.buffer.WindowBufferEmitter
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

val riskRiverTopic = "helse-risk-river-v1"

internal open class BufferedRiver(private val kafkaProducer: KafkaProducer<String, JsonObject>,
                                  private val kafkaConsumer: KafkaConsumer<String, JsonObject>,
                                  private val interessertI: List<Interesse>,
                                  private val skipEmitIfNotPresent: List<Interesse>,
                                  private val answerer: (List<JsonObject>, String) -> JsonObject?,
                                  collectorRegistry: CollectorRegistry,
                                  windowTimeInSeconds: Long = 5,
                                  emitEarlyWhenAllInterestsPresent: Boolean = true,
                                  private val skipMessagesOlderThanSeconds: Long = -1
) {
    private val log: Logger = LoggerFactory.getLogger(BufferedRiver::class.java)

    private val aggregator = WindowBufferEmitter(
        windowSizeInSeconds = windowTimeInSeconds,
        aggregateAndEmit = ::lagOgSendSvar,
        scheduleExpiryCheck = true,
        schedulerIntervalInSeconds = 5,
        sessionEarlyExpireCondition = if (emitEarlyWhenAllInterestsPresent) this::isCompleteMessageSet else null,
        collectorRegistry = collectorRegistry)


    private fun isCompleteMessageSet(msgs: List<JsonObject>) =
        isCompleteMessageSetAccordingToInterests(msgs, interessertI)

    fun tearDown() {
        kafkaConsumer.close()
    }

    fun isHealthy() = aggregator.isHealty().also {
        if (!it) log.error("WindowBufferEmitter is not healthy!")
    }

    suspend fun start() {
        val mangeTilEn: Boolean = interessertI.size > 1
        kafkaConsumer
            .apply { subscribe(listOf(riskRiverTopic)) }
            .asFlow()
            .filterNotNull()
            .filter { (_, value, _) -> value.erInteressant(interessertI) }
            .filterNotNull()
            .collect { (key, value, timestamp) ->
                if (messageIsDated(timestamp)) {
                    log.info("Skipping incoming message because it is older than {} seconds (timestamp={} while currentTimeMillis={}",
                        skipMessagesOlderThanSeconds, timestamp, System.currentTimeMillis())
                } else {
                    if (mangeTilEn) {
                        aggregator.store(value["vedtaksperiodeId"]!!.jsonPrimitive.content, value, key, timestamp) // NB: KEY bør vare samme som innkommende åkke som (RiskNeed?++?),,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,
                    } else {
                        lagOgSendSvar(WindowBufferEmittable(messages = listOf(value), kafkaKey = key))
                    }
                }
            }
    }

    private fun messageIsDated(timestamp: Long) =
        (skipMessagesOlderThanSeconds > 0) && // NB / TODO: Will we need to consider Zone here?
            (timestamp + (skipMessagesOlderThanSeconds * 1000) < System.currentTimeMillis())

    private fun lagOgSendSvar(emitted: WindowBufferEmittable) {
        val answers = emitted.messages
        val vedtaksperiodeId = answers.finnUnikVedtaksperiodeId()

        val ikkeTilfredsstilt:Interesse? = skipEmitIfNotPresent.find { paakrevdInteresse ->
            !paakrevdInteresse.tilfredsstillesAv(answers)
        }
        if (ikkeTilfredsstilt != null) {
            log.debug("Mangler Interesse=$ikkeTilfredsstilt for vedtaksperiodeId=$vedtaksperiodeId. Ignorerer sesjon.")
        } else {
            answerer(answers, vedtaksperiodeId)?.also { svar ->
                kafkaProducer.send(ProducerRecord(riskRiverTopic, emitted.kafkaKey, svar))
            }
        }
    }
}

internal fun isCompleteMessageSetAccordingToInterests(msgs: List<JsonObject>, interesser: List<Interesse>) : Boolean =
        interesser.fold(true, { acc, interesse ->
            acc && interesse.tilfredsstillesAv(msgs)
        })


@FlowPreview
private fun <K, V> KafkaConsumer<K, V>.asFlow(): Flow<Triple<K, V, Long>> = flow { while (true) emit(poll(Duration.ZERO)) }
    .onEach { if (it.isEmpty) delay(100) }
    .flatMapConcat { it.asFlow() }
    .map { Triple(it.key(), it.value(), it.timestamp()) }