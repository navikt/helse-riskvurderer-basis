package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import no.nav.helse.crypto.lagEnJWK
import no.nav.helse.crypto.toJWKSetHolder
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class RiverEnTilEnTest {
    init {
        Sanity.setSkipSanityChecksForProduction()
    }

    val env = RiverEnvironment("testapp")
    val kafkaConfig = TestKafkaConfig("testapp")
    private val json = JsonRisk

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    @BeforeEach
    fun setup() {
        //kafka.start()
        testConsumer = KafkaConsumer<String, JsonObject>(testConsumerConfig).also {
            it.subscribe(listOf(riskRiverTopic()))
        }
    }

    private var bufferedRiver: BufferedRiver? = null
    private val jwkSet = JWKSet(lagEnJWK()).toJWKSetHolder()

    private val interesser = listOf(
        "oppslagsresultat" to "orginfo"
    ).tilInteresser()

    private fun initBufferedRiver() {
        bufferedRiver = BufferedRiver(
            KafkaProducer(producerConfig),
            KafkaConsumer(consumerConfig), interesser, emptyList(),
            VurderingProducer("testapp", this::vurderer, jwkSet)::lagVurdering,
            CollectorRegistry.defaultRegistry,
            sessionAggregationFieldName = RiverApp.SESSION_AGGREGATION_FIELD_NAME_DEFAULT,
        )
        GlobalScope.launch {
            bufferedRiver!!.start()
        }
    }

    fun vurderer(infoListe: List<JsonObject>): Vurdering {
        require(infoListe.size == 1)
        val info = infoListe.first()
        return VurderingBuilder().apply {
            leggVedMetadata("hei", "sann")
            nySjekk(vekt = 5) {
                resultat("derfor", info["nummer"]!!.jsonPrimitive.int)
            }
        }.build(2)
    }

    private fun KafkaProducer<String, JsonObject>.sendJson(jsonstring: String) {
        val value = Json.decodeFromString(JsonObject.serializer(), jsonstring)
        val key = value["vedtaksperiodeId"]!!.jsonPrimitive.content
        this.send(ProducerRecord(riskRiverTopic(), key, value))
    }

    @Test
    fun `buffered river`() {
        initBufferedRiver()
        `relevant enkelt-melding fanges opp og sendes gjennom vurdererfunksjon for aa generere vurderingsmelding`()
    }

    fun `relevant enkelt-melding fanges opp og sendes gjennom vurdererfunksjon for aa generere vurderingsmelding`() {
        KafkaProducer<String, JsonObject>(producerConfig).use { producer ->
            producer.sendJson("""{"nummer":1, "vedtaksperiodeId":"periode1", "type": "RiskNeed", "personid":123}""")
            producer.sendJson("""{"nummer":3, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"orginfo", "info":"firma1"}""")
            producer.sendJson("""{"nummer":6, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"noeannet", "info":"annet1"}""")
            val payload3 = """{"vedtaksperiodeId":"periode2", "svarPå": "etBehov", "vekt":5, "score": 3}"""
            producer.send(ProducerRecord(riskRiverTopic(), json.decodeFromString(JsonObject.serializer(), payload3)))
        }

        var vurdering: Vurderingsmelding? = null

        mutableListOf<ConsumerRecord<String, JsonObject>>().also { msgs ->
            await()
                .atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(200))
                .untilAsserted {
                    msgs.addAll(testConsumer
                        .poll(Duration.ofMillis(100))
                        .toList()
                        .filter { it.value()["type"]?.jsonPrimitive?.content == "vurdering" }
                    )

                    assertTrue(msgs.size > 0)
                    vurdering = json.decodeFromJsonElement(Vurderingsmelding.serializer(), msgs.first().value())
                }
        }
        vurdering.apply {
            assertNotNull(this)
            assertEquals("vurdering", this!!.type)
            assertEquals("testapp", this.infotype)
            assertEquals(listOf("derfor"), this.begrunnelser())
            assertEquals("sann", this.metadata!!["hei"])
        }
    }

    @AfterEach
    fun tearDown() {
        testConsumer.close()
        //bufferedRiver?.tearDown()
        bufferedRiver = null
        //kafka.tearDown()
        kafkaContainer.stop()
    }

    private lateinit var testConsumer: KafkaConsumer<String, JsonObject>

    val kafkaContainer =
        KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.4.3")
        ).waitingFor(HostPortWaitStrategy())
            .apply {
                start()
                val adminClient =
                    AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to this.bootstrapServers))
                val newTopics = listOf(NewTopic(riskRiverTopic(), 1, 1.toShort()))
                adminClient.createTopics(newTopics)
            }

    private val kafkaPropsToOverride = Properties().also {
        it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        it[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }
    private val consumerConfig = kafkaConfig.kafkaConsumerConfig(
        kafkaContainer.bootstrapServers //kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        it[ConsumerConfig.GROUP_ID_CONFIG] = "tulleconsumer"
    }
    private val testConsumerConfig = kafkaConfig.kafkaConsumerConfig(
        kafkaContainer.bootstrapServers //kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        it[ConsumerConfig.GROUP_ID_CONFIG] = "testconsumer"
    }
    private val producerConfig = kafkaProducerConfig(
        kafkaContainer.bootstrapServers //kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ProducerConfig.CLIENT_ID_CONFIG] = "tulleproducer"
    }

    fun kafkaProducerConfig(brokers: String) = Properties().apply {
        putAll(kafkaConfig.commonKafkaConfig(brokers))

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "${env.kafkaClientId}-producer")
    }

}


