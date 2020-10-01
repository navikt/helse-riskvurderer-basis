package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import no.nav.common.KafkaEnvironment
import no.nav.helse.crypto.lagEnJWK
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class RiverEnTilEnTest {
    val env = RiverEnvironment("testapp")
    private val json = Json(JsonConfiguration.Stable)

    @BeforeEach
    fun setup() {
        kafka.start()
        testConsumer = KafkaConsumer<String, JsonObject>(testConsumerConfig).also {
            it.subscribe(listOf(riskRiverTopic))
        }
    }

    private var bufferedRiver: BufferedRiver? = null
    private val jwkSet = JWKSet(lagEnJWK())

    private val interesser = listOf(
        "oppslagsresultat" to "orginfo"
    ).tilInteresser()

    private fun initBufferedRiver() {
        bufferedRiver = BufferedRiver(KafkaProducer<String, JsonObject>(producerConfig),
            KafkaConsumer<String, JsonObject>(consumerConfig), interesser,
            VurderingProducer("testapp", this::vurderer, jwkSet)::lagVurdering,
            CollectorRegistry.defaultRegistry)
        GlobalScope.launch {
            bufferedRiver!!.start()
        }
    }

    fun vurderer(infoListe: List<JsonObject>): Vurdering {
        require(infoListe.size == 1)
        val info = infoListe.first()
        return Vurdering(
            score = info["nummer"]!!.primitive.int,
            vekt = 2,
            begrunnelser = listOf("derfor"))
    }

    private fun KafkaProducer<String, JsonObject>.sendJson(jsonstring: String) {
        val value = Json.parse(JsonObject.serializer(), jsonstring)
        val key = value["vedtaksperiodeId"]!!.content
        this.send(ProducerRecord(riskRiverTopic, key, value))
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
            producer.send(ProducerRecord(riskRiverTopic, json.parse(JsonObject.serializer(), payload3)))
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
                        .filter { it.value()["type"]?.content == "vurdering" }
                    )

                    assertTrue(msgs.size > 0)
                    vurdering = json.fromJson(Vurderingsmelding.serializer(), msgs.first().value())
                }
        }
        vurdering.apply {
            assertNotNull(this)
            assertEquals("vurdering", this!!.type)
            assertEquals("testapp", this.infotype)
            assertEquals(3, this.score)
            assertEquals(2, this.vekt)
            assertEquals(listOf("derfor"), this.begrunnelser)
        }
    }

    @AfterEach
    fun tearDown() {
        testConsumer.close()
        bufferedRiver?.tearDown()
        bufferedRiver = null
        kafka.tearDown()
    }

    private lateinit var testConsumer: KafkaConsumer<String, JsonObject>

    private val kafka = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicNames = listOf(riskRiverTopic),
        topicInfos = listOf(KafkaEnvironment.TopicInfo(riskRiverTopic)),
        withSchemaRegistry = false,
        withSecurity = false
    )

    private val kafkaPropsToOverride = Properties().also {
        it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        it[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }
    private val consumerConfig = env.kafkaConsumerConfig(
        ServiceUser("", ""), kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        it[ConsumerConfig.GROUP_ID_CONFIG] = "tulleconsumer"
    }
    private val testConsumerConfig = env.kafkaConsumerConfig(
        ServiceUser("", ""), kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        it[ConsumerConfig.GROUP_ID_CONFIG] = "testconsumer"
    }
    private val producerConfig = kafkaProducerConfig(
        ServiceUser("", ""), kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ProducerConfig.CLIENT_ID_CONFIG] = "tulleproducer"
    }

    fun kafkaProducerConfig(serviceUser: ServiceUser, brokers: String? = null) = Properties().apply {
        putAll(env.commonKafkaConfig(serviceUser, brokers))

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$env.kafkaClientId-producer")
    }

}


