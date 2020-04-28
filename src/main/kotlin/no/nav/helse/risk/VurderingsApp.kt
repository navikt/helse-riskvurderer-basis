package no.nav.helse.risk

import io.prometheus.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.slf4j.*
import java.util.concurrent.*

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

/**
 * @kafkaClientId kafka consumer/appname: Husk at for windowed stream så må denne som "applicationName" i kombinasjon
 * med serviceuser opprettes som en stream-application i kafka-admin-rest for å få lov til å opprette internal topics.
 */
open class VurderingsApp(
    val kafkaClientId: String,
    val interessertITypeInfotype: List<Pair<String, String?>>,
    val vurderer: (List<JsonObject>) -> Vurdering,
    val windowTimeInSeconds: Long = 5,
    private val environment: Environment = Environment(kafkaClientId)
) {

    private val collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
    private val log: Logger = LoggerFactory.getLogger(VurderingsApp::class.java)
    private val kafkaConsumerConfig = environment.kafkaConsumerConfig(environment.readServiceUserCredentials())
    private val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val exceptionHandler = CoroutineExceptionHandler { context, ex ->
        log.error("Feil boblet helt til topps", ex)
        context.cancel(CancellationException("Feil som (kanskje) burde vært catchet av noen andre", ex))
        applicationContext.close()
    }

    private var river: River? = null

    @FlowPreview
    fun start() {
        Runtime.getRuntime().addShutdownHook(Thread {
            applicationContext.close()
        })

        fun isAlive(): Boolean = river?.state()?.isRunning ?: false

        GlobalScope.launch(applicationContext + exceptionHandler) {
            launch {
                webserver(collectorRegistry = collectorRegistry,
                    isAlive = ::isAlive,
                    isReady = ::isAlive)
            }
            launch {
                river = River(
                    kafkaConsumerConfig = kafkaConsumerConfig,
                    topicConfig = environment,
                    interessertITypeInfotype = interessertITypeInfotype,
                    vurderer = vurderer,
                    windowTimeInSeconds = windowTimeInSeconds
                )
            }
        }

    }

}