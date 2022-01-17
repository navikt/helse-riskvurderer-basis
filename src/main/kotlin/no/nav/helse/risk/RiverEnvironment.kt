package no.nav.helse.risk

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

data class ServiceUser(
    val username: String,
    val password: String
) {
    override fun toString() = "ServiceUser:$username"
}

private val log = LoggerFactory.getLogger(RiverEnvironment::class.java)

private val isKafkaCloud:Boolean = systemEnvOrDefault("KAFKA_CLOUD_RIVER", "false") == "true"

fun riskRiverTopic(): String = if (isKafkaCloud) "risk.helse-risk-river" else "helse-risk-river-v1"

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
    private val kafkaClientId: String
) {
    val vaultBase = "/var/run/secrets/nais.io/kafkauser"
    val vaultBasePath: Path = Paths.get(vaultBase)

    private fun readServiceUserCredentials() = ServiceUser(
        username = Files.readString(vaultBasePath.resolve("username")),
        password = Files.readString(vaultBasePath.resolve("password"))
    )

    fun createKafkaEnvironment() : KafkaRiverEnvironment =
        if (isKafkaCloud) {
            KafkaRiverEnvironment(
                kafkaProducer = KafkaProducer(kafkaProducerConfig()),
                kafkaConsumer = KafkaConsumer(kafkaConsumerConfig())
            )
        } else {
            readServiceUserCredentials().let { kafkaUser ->
                return KafkaRiverEnvironment(
                    kafkaProducer = KafkaProducer(kafkaProducerConfig(kafkaUser)),
                    kafkaConsumer = KafkaConsumer(kafkaConsumerConfig(kafkaUser))
                )
            }
        }

    private fun kafkaProducerConfig(serviceUser: ServiceUser? = null, brokers: String? = null) = Properties().apply {
        if (isKafkaCloud) {
            log.info("RIVER-PRODUCER: Using Kafka Cloud")
            putAll(commonCloudKafkaConfig())
        } else {
            log.info("RIVER-PRODUCER: Using Kafka OnPrem")
            requireNotNull(serviceUser)
            putAll(commonOnPremKafkaConfig(serviceUser, brokers))
        }

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$kafkaClientId-producer")
    }

    private fun kafkaConsumerConfig(serviceUser: ServiceUser? = null, brokers: String? = null) = Properties().apply {
        if (isKafkaCloud) {
            log.info("RIVER-CONSUMER: Using Kafka Cloud")
            putAll(commonCloudKafkaConfig())
        } else {
            log.info("RIVER-CONSUMER: Using Kafka OnPrem")
            requireNotNull(serviceUser)
            putAll(commonOnPremKafkaConfig(serviceUser, brokers))
        }

        put(ConsumerConfig.GROUP_ID_CONFIG, "$kafkaClientId-consumer")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonObjectDeserializer::class.java)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        //put("default.deserialization.exception.handler", LogAndContinueExceptionHandler::class.java)
    }

    private fun commonOnPremKafkaConfig(serviceUser: ServiceUser, brokers: String?) = Properties().apply {
        put("application.id", kafkaClientId)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, systemEnvOrDefault("KAFKA_SECURITY_PROTOCOL_CONFIG",
            "SASL_SSL"))
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers ?: systemEnv("KAFKA_BOOTSTRAP_SERVERS"))
        put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required " +
            "username=\"${serviceUser.username}\" password=\"${serviceUser.password}\";")
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
