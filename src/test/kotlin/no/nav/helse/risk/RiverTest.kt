package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.json.*
import no.nav.common.*
import no.nav.helse.crypto.encryptAsJWE
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

@kotlinx.serialization.UnstableDefault
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiverTest {
    val env = Environment("testapp")
    private val json = Json(JsonConfiguration.Stable)

    private val jwk1 = lagEnJWK("key1")
    private val jwk2 = lagEnJWK("key2")
    private val jwkSet = JWKSet(listOf(jwk1, jwk2))

    @BeforeEach
    fun setup() {
        kafka.start()
        river = River(consumerConfig, env, listOf(
            "RiskNeed" to null,
            "oppslagsresultat" to "orginfo",
            "oppslagsresultat" to "sensitiv1",
            "oppslagsresultat" to "sensitiv2"
        ), this::vurderer, jwkSet)
        await()
            .pollDelay(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(10))
            .until { river.state() == RUNNING }

        testConsumer = KafkaConsumer<String, JsonObject>(testConsumerConfig).also {
            it.subscribe(listOf(env.riskRiverTopic))
        }
    }

    fun vurderer(info: List<JsonObject>): Vurdering {
        return Vurdering(
            score = info.map { it["data"]!!.jsonObject["nummer"]!!.primitive.int }.sum(),
            vekt = 2,
            begrunnelser = listOf("derfor"))
    }

    private fun KafkaProducer<String, JsonObject>.sendJson(jsonstring: String) {
        val value = Json.parse(JsonObject.serializer(), jsonstring)
        val key = value["vedtaksperiodeId"]!!.content
        this.send(ProducerRecord(env.riskRiverTopic, key, value))
    }

    @Test
    fun `relevante meldinger aggregeres og sendes gjennom vurderer-funksjon for aa generere en vurdering`() {
        KafkaProducer<String, JsonObject>(producerConfig).use { producer ->
            producer.sendJson("""{"data" : {"nummer":1}, "vedtaksperiodeId":"periode1", "type": "RiskNeed", "personid":123}""")
            producer.sendJson("""{"data" : {"nummer":3}, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"orginfo", "info":"firma1"}""")
            producer.sendJson("""{"data" : {"nummer":6}, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"noeannet", "info":"annet1"}""")

            producer.sendJson("""{"data" : "${json { "nummer" to 100 }.encryptAsJWE(jwk1)}", "vedtaksperiodeId":"periode1", "infotype":"sensitiv1", "type": "oppslagsresultat", "info":"firma1"}""")
            producer.sendJson("""{"data" : "${json { "nummer" to 1000 }.encryptAsJWE(jwk2)}", "vedtaksperiodeId":"periode1", "infotype":"sensitiv2", "type": "oppslagsresultat", "info":"firma1"}""")

            val payload3 = """{"vedtaksperiodeId":"periode2", "svarPå": "etBehov", "vekt":5, "score": 3}"""
            producer.send(ProducerRecord(env.riskRiverTopic, Json.parse(JsonObject.serializer(), payload3)))
        }

        var vurdering: Vurderingsmelding? = null

        mutableListOf<ConsumerRecord<String, JsonObject>>().also { msgs ->
            await()
                .atMost(Duration.ofSeconds(60))
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
            assertEquals(1 + 3 + 100 + 1000, this.score)
            assertEquals(2, this.vekt)
            assertEquals(listOf("derfor"), this.begrunnelser)
        }
    }

    @AfterEach
    fun tearDown() {
        testConsumer.close()
        river.tearDown()
        kafka.tearDown()
    }

    private lateinit var river: River
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


