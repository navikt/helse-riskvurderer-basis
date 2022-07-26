package no.nav.helse.risk

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import java.io.StringWriter
import java.util.concurrent.TimeUnit

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

