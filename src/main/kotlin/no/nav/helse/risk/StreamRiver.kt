package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.state.Stores
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*


internal class StreamRiver(kafkaConsumerConfig: Properties,
                           private val topicConfig: TopicAndClientIdHolder,
                           private val interessertITypeInfotype: List<Pair<String, String?>>,
                           vurderer: (List<JsonObject>) -> Vurdering,
                           decryptionJWKS: JWKSet?,
                           private val windowTimeInSeconds: Long = 5
) {

    private val vurderingProducer = VurderingProducer(topicConfig, vurderer, decryptionJWKS)
    private val stream: KafkaStreams

    init {
        stream = KafkaStreams(topology(), kafkaConsumerConfig)
        stream.addShutdownHook()
        stream.start()
    }

    fun state() = stream.state()
    fun tearDown() = stream.close()

    companion object {
        private val log = LoggerFactory.getLogger(StreamRiver::class.java)
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
                .map { key, value -> KeyValue(key.key(), vurderingProducer.lagVurdering(value, key.key())) }
        } else {
            stream = stream.map { key, value ->
                KeyValue(key, vurderingProducer.lagVurdering(listOf(value), key))
            }
        }
        stream
            .filter { key, value -> value != null }
            .to(topicConfig.riskRiverTopic, Produced.with(keySerde, valueSerde))

        return builder.build()
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

