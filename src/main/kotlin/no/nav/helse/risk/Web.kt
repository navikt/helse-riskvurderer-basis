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
import java.io.StringWriter
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
            if (isAlive()) {
                call.respondText("ALIVE", ContentType.Text.Plain)
            } else {
                call.respond(HttpStatusCode.InternalServerError, "NOT_ALIVE")
            }
        }
        get("/isready") {
            if (isReady()) {
                call.respondText("READY", ContentType.Text.Plain)
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, "READY")
            }
        }
        get("/metrics") {
            val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()
            val text = StringWriter()
            TextFormat.write004(text, collectorRegistry.filteredMetricFamilySamples(names))
            call.respondText(text = text.toString(), contentType = ContentType.parse(TextFormat.CONTENT_TYPE_004))
        }
    }
}

