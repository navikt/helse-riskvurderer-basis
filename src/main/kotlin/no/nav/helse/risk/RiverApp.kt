package no.nav.helse.risk

import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

open class RiverApp internal constructor(
    val kafkaClientId: String,
    val interessertITypeInfotype: List<Pair<String, String?>>,
    private val answerer: (List<JsonObject>, String) -> JsonObject?,
    val windowTimeInSeconds: Long = 5,
    private val emitEarlyWhenAllInterestsPresent: Boolean = true,
    private val collectorRegistry: CollectorRegistry
) {
    private val environment: RiverEnvironment = RiverEnvironment(kafkaClientId)
    private val log: Logger = LoggerFactory.getLogger(VurderingsApp::class.java)
    private val kafkaUser = environment.readServiceUserCredentials()
    private val kafkaConsumerConfig = environment.kafkaConsumerConfig(kafkaUser)
    private val kafkaProducerConfig = environment.kafkaProducerConfig(kafkaUser)
    private val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { context, ex ->
        log.error("Feil boblet helt til topps", ex)
        context.cancel(CancellationException("Feil som (kanskje) burde vært catchet av noen andre", ex))
        applicationContext.close()
    }

    private var bufferedRiver: BufferedRiver? = null

    @FlowPreview
    fun start() {
        Runtime.getRuntime().addShutdownHook(Thread {
            applicationContext.close()
        })

        fun isAlive(): Boolean = bufferedRiver?.isRunning() ?: false

        GlobalScope.launch(applicationContext + exceptionHandler) {
            launch {
                webserver(collectorRegistry = collectorRegistry,
                    isAlive = ::isAlive,
                    isReady = ::isAlive)
            }
            launch {
                bufferedRiver = BufferedRiver(
                    kafkaProducer = KafkaProducer(kafkaProducerConfig),
                    kafkaConsumerConfig = kafkaConsumerConfig,
                    interessertITypeInfotype = interessertITypeInfotype,
                    answerer = answerer,
                    windowTimeInSeconds = windowTimeInSeconds,
                    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent
                ).apply { this.start() }
            }
        }
    }
}