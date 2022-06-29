package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import no.nav.common.KafkaEnvironment
import no.nav.helse.crypto.encryptAsJWE
import no.nav.helse.crypto.lagEnJWK
import no.nav.helse.crypto.toJWKHolder
import no.nav.helse.crypto.toJWKSetHolder
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
internal class RiverTest {
    init {
        Sanity.setSkipSanityChecksForProduction()
    }

    val env = RiverEnvironment("testapp")
    val kafkaConfig = TestKafkaConfig("testapp")
    private val json = JsonRisk

    private val jwk1 = lagEnJWK("key1").toJWKHolder()
    private val jwk2 = lagEnJWK("key2").toJWKHolder()
    private val jwkSet = JWKSet(listOf(jwk1.jwk(), jwk2.jwk())).toJWKSetHolder()

    private var bufferedRiver: BufferedRiver? = null

    private val interesser = listOf(
        "RiskNeed" to null,
        "oppslagsresultat" to "orginfo",
        "oppslagsresultat" to "sensitiv1",
        "oppslagsresultat" to "sensitiv2"
    ).tilInteresser()

    @BeforeEach
    fun clearStuff() {
        CollectorRegistry.defaultRegistry.clear()
    }

    private fun initBufferedRiver() {
        bufferedRiver = BufferedRiver(KafkaProducer<String, JsonObject>(producerConfig),
            KafkaConsumer<String, JsonObject>(consumerConfig), interesser, emptyList(),
            VurderingProducer("testapp", this::vurderer, jwkSet)::lagVurdering,
            CollectorRegistry.defaultRegistry,
            sessionAggregationFieldName = RiverApp.SESSION_AGGREGATION_FIELD_NAME_DEFAULT)
        GlobalScope.launch {
            bufferedRiver!!.start()
        }
    }

    @BeforeEach
    fun setup() {
        kafka.start()
        testConsumer = KafkaConsumer<String, JsonObject>(testConsumerConfig).also {
            it.subscribe(listOf(riskRiverTopic()))
        }
    }

    fun vurderer(info: List<JsonObject>): Vurdering {
        return VurderingBuilder().apply {
            nySjekk(vekt = 5) {
                resultat("derfor", info.map { it["data"]!!.jsonObject["nummer"]!!.jsonPrimitive.int }.sum())
            }
        }.build(2)
    }

    private fun KafkaProducer<String, JsonObject>.sendJson(jsonstring: String) {
        val value = json.decodeFromString(JsonObject.serializer(), jsonstring)
        val key = value["vedtaksperiodeId"]!!.jsonPrimitive.content
        this.send(ProducerRecord(riskRiverTopic(), key, value))
    }

    @Test
    fun `buffered river`() {
        initBufferedRiver()
        `relevante meldinger aggregeres og sendes gjennom vurderer-funksjon for aa generere en vurdering`()
    }

    private fun `relevante meldinger aggregeres og sendes gjennom vurderer-funksjon for aa generere en vurdering`() {
        KafkaProducer<String, JsonObject>(producerConfig).use { producer ->
            producer.sendJson("""
                {"data" : {"nummer":1}, 
                 "vedtaksperiodeId":"periode1",
                 "riskNeedId":"1111-2222-RISK-NEED",
                 "type": "RiskNeed", 
                 "fnr":"123",
                 "organisasjonsnummer":"999",
                 "behovOpprettet":"${LocalDateTime.now()}",
                 "iterasjon": 1
                 }
                """.trimIndent())
            producer.sendJson("""{"data" : {"nummer":2}, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"orginfo", "info":"firma1"}""")
            producer.sendJson("""{"data" : {"nummer":6}, "vedtaksperiodeId":"periode1", "type": "oppslagsresultat", "infotype":"noeannet", "info":"annet1"}""")

            producer.sendJson("""{"data" : "${buildJsonObject { put("nummer", 3) }.encryptAsJWE(jwk1)}", "vedtaksperiodeId":"periode1", "infotype":"sensitiv1", "type": "oppslagsresultat", "info":"firma1"}""")
            producer.sendJson("""{"data" : "${buildJsonObject { put("nummer", 1) }.encryptAsJWE(jwk2)}", "vedtaksperiodeId":"periode1", "infotype":"sensitiv2", "type": "oppslagsresultat", "info":"firma1"}""")

            val payload3 = """{"vedtaksperiodeId":"periode2", "svarPå": "etBehov", "vekt":5, "score": 3}"""
            producer.send(ProducerRecord(riskRiverTopic(), json.decodeFromString(JsonObject.serializer(), payload3)))
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
        topicNames = listOf(riskRiverTopic()),
        topicInfos = listOf(KafkaEnvironment.TopicInfo(riskRiverTopic())),
        withSchemaRegistry = false,
        withSecurity = false
    )

    private val kafkaPropsToOverride = Properties().also {
        it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        it[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }
    private val consumerConfig = kafkaConfig.kafkaConsumerConfig(
        ServiceUser("", ""), kafka.brokersURL
    ).also {
        it.putAll(kafkaPropsToOverride)
        it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        it[ConsumerConfig.GROUP_ID_CONFIG] = "tulleconsumer"
    }
    private val testConsumerConfig = kafkaConfig.kafkaConsumerConfig(
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

    fun kafkaProducerConfig(serviceUser: ServiceUser, brokers: String) = Properties().apply {
        putAll(kafkaConfig.commonKafkaConfig(serviceUser, brokers))

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$env.kafkaClientId-producer")
    }

}

class TestKafkaConfig(val kafkaClientId: String) {
    fun kafkaProducerConfig(serviceUser: ServiceUser, brokers: String) = Properties().apply {
        putAll(commonKafkaConfig(serviceUser, brokers))

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$kafkaClientId-producer")
    }

    fun kafkaConsumerConfig(serviceUser: ServiceUser, brokers: String) = Properties().apply {
        putAll(commonKafkaConfig(serviceUser, brokers))

        put(ConsumerConfig.GROUP_ID_CONFIG, "$kafkaClientId-consumer")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonObjectDeserializer::class.java)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        //put("default.deserialization.exception.handler", LogAndContinueExceptionHandler::class.java)
    }

    fun commonKafkaConfig(serviceUser: ServiceUser, brokers: String) = Properties().apply {
        put("application.id", kafkaClientId)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required " +
            "username=\"${serviceUser.username}\" password=\"${serviceUser.password}\";")
    }
}


