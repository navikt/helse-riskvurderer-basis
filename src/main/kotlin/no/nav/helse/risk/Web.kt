package no.nav.helse.risk

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.metrics.micrometer.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.micrometer.core.instrument.*
import io.micrometer.core.instrument.binder.jvm.*
import io.micrometer.core.instrument.binder.system.*
import io.micrometer.prometheus.*
import io.prometheus.client.*
import io.prometheus.client.exporter.common.*
import java.util.concurrent.*

fun webserver(collectorRegistry: CollectorRegistry,
              isReady: () -> Boolean, isAlive: () -> Boolean) {
    val server = embeddedServer(Netty, 8080) {
        riskvurderer(collectorRegistry, isReady, isAlive)
    }.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(10, 10, TimeUnit.SECONDS)
    })
}

fun Application.riskvurderer(collectorRegistry: CollectorRegistry,
                             isReady: () -> Boolean, isAlive: () -> Boolean) {
    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(
            PrometheusConfig.DEFAULT,
            collectorRegistry,
            Clock.SYSTEM
        )
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics()
        )
    }

    routing {
        get("/isalive") {
            val status = if (isAlive()) "ALIVE" else "NOT ALIVE"
            call.respondText(status, ContentType.Text.Plain)
        }
        get("/isready") {
            val status = if (isReady()) "READY" else "NOT READY"
            call.respondText(status, ContentType.Text.Plain)
        }
        get("/metrics") {
            val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()
            call.respondTextWriter(ContentType.parse(TextFormat.CONTENT_TYPE_004)) {
                TextFormat.write004(this, collectorRegistry.filteredMetricFamilySamples(names))
            }
        }
    }
}

