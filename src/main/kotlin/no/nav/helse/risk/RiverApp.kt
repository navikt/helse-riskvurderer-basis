package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.KafkaException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.SQLTransientException
import java.util.concurrent.Executors

data class KafkaRiverEnvironment(
    val kafkaConsumer: Consumer<String, JsonObject>,
    val kafkaProducer: Producer<String, JsonObject>
)

private val log: Logger = LoggerFactory.getLogger(RiverApp::class.java)

open class RiverApp internal constructor(
    val kafkaClientId: String,
    val interessertI: List<Interesse>,
    val skipEmitIfNotPresent: List<Interesse>,
    private val answerer: (List<JsonObject>, String, String?) -> JsonObject?,
    val windowTimeInSeconds: Long = 5,
    private val emitEarlyWhenAllInterestsPresent: Boolean = true,
    private val collectorRegistry: CollectorRegistry,
    private val launchAlso: List<suspend CoroutineScope.() -> Unit>,
    private val additionalHealthCheck: (() -> Boolean)?,
    private val skipMessagesOlderThanSeconds: Long = -1,
    private val disableWebEndpoints: Boolean = false,
    private val sessionAggregationFieldName: String,
) {
    companion object {
        const val SESSION_AGGREGATION_FIELD_NAME_DEFAULT: String = vedtaksperiodeIdKey
    }

    private val environment: RiverEnvironment = RiverEnvironment(kafkaClientId)

    private var overriddenKafkaEnvironment: KafkaRiverEnvironment? = null
    private fun createKafkaEnvironment(): KafkaRiverEnvironment =
        overriddenKafkaEnvironment ?: environment.createKafkaEnvironment()

    private val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private var healthy = true
    fun isHealthy(): Boolean = healthy
        && (bufferedRiver?.isHealthy() ?: true)
        && (additionalHealthCheck?.invoke() ?: true)

    val exceptionHandler = CoroutineExceptionHandler { _, ex ->
        log.error("Feil boblet helt til topps", ex)
        if (shouldCauseRestart(ex)) {
            log.error("Setting status to UN-healthy")
            healthy = false
        }
    }

    private fun shouldCauseRestart(ex: Throwable): Boolean =
        (ex is KafkaException) || (ex is SQLTransientException) || (ex is Error) || restartExceptionClassNames.contains(ex.javaClass.canonicalName)

    private val restartExceptionClassNames = listOf(
        "com.bettercloud.vault.VaultException",
        "no.nav.vault.jdbc.hikaricp.VaultError"
    )

    private var bufferedRiver: BufferedRiver? = null

    fun overrideKafkaEnvironment(kafkaEnvironment: KafkaRiverEnvironment): RiverApp {
        overriddenKafkaEnvironment = kafkaEnvironment
        return this
    }

    @FlowPreview
    fun start() {
        Sanity.runSanityChecks()

        Runtime.getRuntime().addShutdownHook(Thread {
            applicationContext.close()
        })

        val kafka = createKafkaEnvironment()

        GlobalScope.launch(applicationContext + exceptionHandler) {
            if (!disableWebEndpoints) {
                launch {
                    webserver(
                        collectorRegistry = collectorRegistry,
                        isAlive = ::isHealthy,
                        isReady = ::isHealthy
                    )
                }
            }
            launch {
                bufferedRiver = BufferedRiver(
                    kafkaProducer = kafka.kafkaProducer,
                    kafkaConsumer = kafka.kafkaConsumer,
                    interessertI = interessertI,
                    skipEmitIfNotPresent = skipEmitIfNotPresent,
                    answerer = answerer,
                    windowTimeInSeconds = windowTimeInSeconds,
                    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent,
                    skipMessagesOlderThanSeconds = skipMessagesOlderThanSeconds,
                    collectorRegistry = collectorRegistry,
                    sessionAggregationFieldName = sessionAggregationFieldName,
                ).apply { this.start() }
            }
            launchAlso.forEach {
                launch(block = it)
            }
        }
    }
}