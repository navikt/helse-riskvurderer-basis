package no.nav.helse.risk

import org.apache.kafka.clients.*
import org.apache.kafka.clients.consumer.*
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.config.*
import org.apache.kafka.common.serialization.*
import org.apache.kafka.streams.errors.*
import java.nio.file.*
import java.util.*

data class ServiceUser(
    val username: String,
    val password: String
) {
    override fun toString() = "ServiceUser:$username"
}

class Environment(
    override val kafkaClientId: String
) : TopicAndClientIdHolder {
    val vaultBase = "/var/run/secrets/nais.io/serviceuser"
    val vaultBasePath: Path = Paths.get(vaultBase)

    override val riskRiverTopic = "helse-risk-river-v1"

    fun readServiceUserCredentials() = ServiceUser(
        username = Files.readString(vaultBasePath.resolve("username")),
        password = Files.readString(vaultBasePath.resolve("password"))
    )


    /*fun kafkaProducerConfig(serviceUser: ServiceUser, brokers: String? = null) = Properties().apply {
        putAll(commonKafkaConfig(serviceUser, brokers))

        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonObjectSerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.CLIENT_ID_CONFIG, "$kafkaClientId-producer")
    }*/

    fun kafkaConsumerConfig(serviceUser: ServiceUser, brokers: String? = null) = Properties().apply {
        putAll(commonKafkaConfig(serviceUser, brokers))

        put(ConsumerConfig.GROUP_ID_CONFIG, "$kafkaClientId-consumer")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonObjectDeserializer::class.java)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put("default.deserialization.exception.handler", LogAndContinueExceptionHandler::class.java)
    }

    internal fun commonKafkaConfig(serviceUser: ServiceUser, brokers: String?) = Properties().apply {
        put("application.id", kafkaClientId)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, systemEnvOrDefault("KAFKA_SECURITY_PROTOCOL_CONFIG",
            "SASL_SSL"))
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers ?: systemEnv("KAFKA_BOOTSTRAP_SERVERS"))
        put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required " +
            "username=\"${serviceUser.username}\" password=\"${serviceUser.password}\";")
    }

    private fun systemEnvOrDefault(name: String, default: String) =
        System.getenv(name) ?: default

    private fun systemEnv(name: String) = System.getenv(name)
        ?: error("Mangler env var '$name'")
}