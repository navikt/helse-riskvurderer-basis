package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import no.nav.common.*
import no.nav.helse.crypto.lagEnJWK
import org.apache.kafka.clients.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.config.*
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.KafkaStreams.State.*
import org.awaitility.Awaitility.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.*
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class RiverEnTilEnTest {
    val env = Environment("testapp")
    private val json = Json(JsonConfiguration.Stable)

    @BeforeEach
    fun setup() {
        kafka.start()
        testConsumer = KafkaConsumer<String, JsonObject>(testConsumerConfig).also {
            it.subscribe(listOf(env.riskRiverTopic))
        }
    }

    private var streamRiver: StreamRiver? = null
    private var bufferedRiver: BufferedRiver? = null
    private val jwkSet = JWKSet(lagEnJWK())

    private val interesser = listOf(
        "oppslagsresultat" to "orginfo"
    )

    private fun initStreamRiver() {
        streamRiver = StreamRiver(consumerConfig, env, interesser, this::vurderer, jwkSet)
        await()
            .pollDelay(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(10))
            .until { streamRiver!!.state() == RUNNING }
    }

    private fun initBufferedRiver() {
        bufferedRiver = BufferedRiver(KafkaProducer<String, JsonObject>(producerConfig),
            consumerConfig, env, interesser, this::vurderer, jwkSet)
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
        this.send(ProducerRecord(env.riskRiverTopic, key, value))
    }

    @Test
    fun `stream based river`() {
        initStreamRiver()
        `relevant enkelt-melding fanges opp og sendes gjennom vurdererfunksjon for aa generere vurderingsmelding`()
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
            producer.send(ProducerRecord(env.riskRiverTopic, json.parse(JsonObject.serializer(), payload3)))
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
        streamRiver?.tearDown()
        streamRiver = null
        bufferedRiver?.tearDown()
        bufferedRiver = null
        kafka.tearDown()
    }

    private lateinit var testConsumer: KafkaConsumer<String, JsonObject>

    private val kafka = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicNames = listOf(env.riskRiverTopic),
        topicInfos = listOf(KafkaEnvironment.TopicInfo(env.riskRiverTopic)),
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

