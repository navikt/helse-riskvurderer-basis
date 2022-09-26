package no.nav.helse.risk

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.util.*

private val log = LoggerFactory.getLogger(RiverEnvironment::class.java)

fun riskRiverTopic(): String = "risk.helse-risk-river"

object AppEnvironment {
    fun podname(): String = systemEnvOrDefault("HOSTNAME", "unknownHost")
    fun appname(): String = systemEnvOrDefault("NAIS_APP_NAME", "unknownApp")
    fun imagename(): String = systemEnvOrDefault("NAIS_APP_IMAGE", "unknownImage")
    fun appInstanceInfo() : Map<String, String> = mapOf(
        "podname" to podname(),
        "appname" to appname(),
        "imagename" to imagename()
    )
}

internal class RiverEnvironment(
    internal val kafkaClientId: String
) {
    fun createKafkaEnvironment() : KafkaRiverEnvironment =
        KafkaRiverEnvironment(
            kafkaProducer = KafkaProducer(kafkaProducerConfig()),
            kafkaConsumer = KafkaConsumer(kafkaConsumerConfig())
        )

    private fun kafkaProducerConfig() = Properties().apply {
        log.info("RIVER-PRODUCER: Using Kafka Cloud")
        putAll(commonCloudKafkaConfig())

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$kafkaClientId-producer")
    }

    private fun kafkaConsumerConfig() = Properties().apply {
        log.info("RIVER-CONSUMER: Using Kafka Cloud")
        putAll(commonCloudKafkaConfig())

        put(ConsumerConfig.GROUP_ID_CONFIG, "$kafkaClientId-consumer")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonObjectDeserializer::class.java)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        //put("default.deserialization.exception.handler", LogAndContinueExceptionHandler::class.java)
    }

    private fun commonCloudKafkaConfig() = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, systemEnv("KAFKA_BROKERS"))
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
        put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "jks")
        put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
        put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, systemEnv("KAFKA_TRUSTSTORE_PATH"))
        put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, systemEnv("KAFKA_CREDSTORE_PASSWORD"))
        put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, systemEnv("KAFKA_KEYSTORE_PATH"))
        put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, systemEnv("KAFKA_CREDSTORE_PASSWORD"))
    }
}

private fun systemEnvOrDefault(name: String, default: String) =
    System.getenv(name) ?: default

private fun systemEnv(name: String) = System.getenv(name)
    ?: error("Mangler env var '$name'")
