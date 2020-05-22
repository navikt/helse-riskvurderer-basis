package no.nav.helse.risk

import com.nimbusds.jose.jwk.JWKSet
import io.prometheus.client.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.*
import java.util.concurrent.*

data class Vurdering(
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>
)

open class VurderingsApp(
    val kafkaClientId: String,
    val interessertITypeInfotype: List<Pair<String, String?>>,
    val vurderer: (List<JsonObject>) -> Vurdering,
    val windowTimeInSeconds: Long = 5,
    private val environment: Environment = Environment(kafkaClientId),
    private val decryptionJWKS: JWKSet? = null,
    private val emitEarlyWhenAllInterestsPresent: Boolean = true
) {

    private val collectorRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
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
                    topicConfig = environment,
                    interessertITypeInfotype = interessertITypeInfotype,
                    vurderer = vurderer,
                    decryptionJWKS = decryptionJWKS,
                    windowTimeInSeconds = windowTimeInSeconds,
                    emitEarlyWhenAllInterestsPresent = emitEarlyWhenAllInterestsPresent
                ).apply { this.start() }

            }
        }

    }

}