package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.KafkaException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

data class KafkaRiverEnvironment(
    val kafkaConsumer: KafkaConsumer<String, JsonObject>,
    val kafkaProducer: KafkaProducer<String, JsonObject>
)

open class RiverApp internal constructor(
    val kafkaClientId: String,
    val interessertI: List<Interesse>,
    private val answerer: (List<JsonObject>, String) -> JsonObject?,
    val windowTimeInSeconds: Long = 5,
    private val emitEarlyWhenAllInterestsPresent: Boolean = true,
    private val collectorRegistry: CollectorRegistry
) {
    private val environment: RiverEnvironment = RiverEnvironment(kafkaClientId)
    private val log: Logger = LoggerFactory.getLogger(VurderingsApp::class.java)

    private var overriddenKafkaEnvironment: KafkaRiverEnvironment? = null
    private fun createKafkaEnvironment() : KafkaRiverEnvironment {
        return overriddenKafkaEnvironment?:environment.readServiceUserCredentials().let { kafkaUser ->
            val kafkaConsumerConfig = environment.kafkaConsumerConfig(kafkaUser)
            val kafkaProducerConfig = environment.kafkaProducerConfig(kafkaUser)
            KafkaRiverEnvironment(
                kafkaProducer = KafkaProducer<String,JsonObject>(kafkaProducerConfig),
                kafkaConsumer = KafkaConsumer<String,JsonObject>(kafkaConsumerConfig)
            )
        }
    }

    private val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private var healthy = true
    fun isHealthy(): Boolean = healthy
    val exceptionHandler = CoroutineExceptionHandler { _, ex ->
        log.error("Feil boblet helt til topps", ex)
        if (shouldCauseRestart(ex)) {
            log.error("Setting status to UN-healthy")
            healthy = false
        }
    }
    private fun shouldCauseRestart(ex: Throwable): Boolean =
        (ex is KafkaException)

    private var bufferedRiver: BufferedRiver? = null

    fun ovverrideKafkaEnvironment(kafkaEnvironment: KafkaRiverEnvironment) : RiverApp {
        overriddenKafkaEnvironment = kafkaEnvironment
        return this
    }

    @FlowPreview
    fun start() {
        Runtime.getRuntime().addShutdownHook(Thread {
            applicationContext.close()
        })

        val kafka = createKafkaEnvironment()

        GlobalScope.launch(applicationContext + exceptionHandler) {
            launch {
                webserver(collectorRegistry = collectorRegistry,
                    isAlive = ::isHealthy,
                    isReady = ::isHealthy)
            }
            launch {
                bufferedRiver = BufferedRiver(
                    kafkaProducer = kafka.kafkaProducer,
                    kafkaConsumer = kafka.kafkaConsumer,
                    interessertI = interessertI,
                    answerer = answerer,
                    windowTimeInSeconds = windowTimeInSeconds,
                    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent
                ).apply { this.start() }
            }
        }
    }
}