package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.*
import org.apache.kafka.common.utils.*
import org.apache.kafka.streams.*
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.state.*
import org.slf4j.*
import java.time.*
import java.util.*

interface TopicAndClientIdHolder {
    val riskRiverTopic: String
    val kafkaClientId: String
}

private val json = Json(JsonConfiguration.Stable)

const val vedtaksperiodeIdKey = "vedtaksperiodeId"
const val typeKey = "type"
const val vurderingType = "vurdering"
const val infotypeKey = "infotype"

@Serializable
data class Vurderingsmelding(
    val type: String = vurderingType,
    val infotype: String,
    val vedtaksperiodeId: String,
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

class River(kafkaConsumerConfig: Properties,
            private val topicConfig: TopicAndClientIdHolder,
            private val interessertITypeInfotype: List<Pair<String, String?>>,
            private val vurderer: (List<JsonObject>) -> Vurdering,
            private val windowTimeInSeconds: Long = 5) {

    private val stream: KafkaStreams

    init {
        stream = KafkaStreams(topology(), kafkaConsumerConfig)
        stream.addShutdownHook()
        stream.start()
    }

    fun state() = stream.state()
    fun tearDown() = stream.close()

    companion object {
        private val log = LoggerFactory.getLogger(River::class.java)
    }

    private fun topology(): Topology {
        val mangeTilEn: Boolean = interessertITypeInfotype.size > 1
        val builder = StreamsBuilder()

        val keySerde = Serdes.String()
        val valueSerde = Serdes.serdeFrom(JsonObjectSerializer(), JsonObjectDeserializer())

        var stream = builder.stream<String, JsonObject>(topicConfig.riskRiverTopic,
                Consumed.with(keySerde, valueSerde) //.withOffsetResetPolicy(Topology.AutoOffsetReset.EARLIEST)
            )
            .filter { _, value -> value != null }
            .filter { _, value -> value.tilfredsstillerInteresse(interessertITypeInfotype) }
        if (mangeTilEn) {
            val listValueSerde: Serde<List<JsonObject>> = Serdes.serdeFrom(JsonObjectListSerializer(), JsonObjectListDeserializer())
            val storeSupplier = Stores.inMemorySessionStore("riverstore", Duration.ofSeconds(windowTimeInSeconds * 2))
            val stateStore = Materialized.`as`<String, List<JsonObject>>(storeSupplier)
            //val stateStore = Materialized.`as`<String, List<JsonObject>, SessionStore<Bytes, ByteArray>>("riverstore")
                .withKeySerde(keySerde)
                .withValueSerde(listValueSerde)
            stream = stream.groupBy({ _, value -> value[vedtaksperiodeIdKey]?.content }, Grouped.with(keySerde, valueSerde))
                .windowedBy(SessionWindows.with(Duration.ofSeconds(windowTimeInSeconds)))
                .aggregate(
                    { emptyList() },
                    { _: String, value: JsonObject, agg: List<JsonObject> -> agg + value },
                    { _: String, firstSession: List<JsonObject>, secondSession: List<JsonObject> -> firstSession + secondSession },
                    stateStore
                )
                .toStream()
                .map { key, value -> KeyValue(key.key(), lagVurdering(value, key.key())) }
        } else {
            stream = stream.map { key, value ->
                KeyValue(key, lagVurdering(listOf(value), key))
            }
        }
        stream
            .filter { key, value -> value != null }
            .to(topicConfig.riskRiverTopic, Produced.with(keySerde, valueSerde))

        return builder.build()
    }

    private fun lagVurdering(answers: List<JsonObject>, vedtaksperiodeId: String): JsonObject? {
        return try {
            val vurdering = vurderer(answers)
            log.info("Lager vurdering for vedtaksperiodeId=$vedtaksperiodeId")
            json.toJson(Vurderingsmelding.serializer(), Vurderingsmelding(
                infotype = topicConfig.kafkaClientId,
                vedtaksperiodeId = vedtaksperiodeId,
                score = vurdering.score,
                vekt = vurdering.vekt,
                begrunnelser = vurdering.begrunnelser
            )).jsonObject
        } catch (ex:Exception) {
            log.error("Feil under vurdering", ex)
            null
        }

    }

    private fun KafkaStreams.addShutdownHook() {
        setStateListener { newState, oldState ->
            log.info("From state={} to state={}", oldState, newState)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            stream.close()
            close()
        })
    }
}

internal fun JsonObject.tilfredsstillerInteresse(interesser: List<Pair<String, String?>>): Boolean {
    interesser.forEach {
        if (it.first == this[typeKey]?.content &&
            (it.second == null || (it.second == this[infotypeKey]?.content)))
            return true
    }
    return false
}
