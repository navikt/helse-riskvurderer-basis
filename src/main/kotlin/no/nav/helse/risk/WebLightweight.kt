package no.nav.helse.risk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

fun webserverLightweight(
    collectorRegistry: CollectorRegistry,
    isReady: () -> Boolean,
    isAlive: () -> Boolean
) {
    val server = createWebServer(
        port = 8080,
        collectorRegistry = collectorRegistry,
        isReady = isReady,
        isAlive = isAlive
    )
    server.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(10)
    })
}

internal fun createWebServer(
    port: Int,
    collectorRegistry: CollectorRegistry,
    isReady: () -> Boolean,
    isAlive: () -> Boolean
): HttpServer {
    val server = HttpServer.create(InetSocketAddress(port), 0)

    server.createContext("/isalive") { exchange ->
        if (exchange.requestMethod != "GET") return@createContext methodNotAllowed(exchange)
        if (isAlive()) {
            plainText(exchange, 200, "ALIVE")
        } else {
            plainText(exchange, 500, "NOT_ALIVE")
        }
    }

    server.createContext("/isready") { exchange ->
        if (exchange.requestMethod != "GET") return@createContext methodNotAllowed(exchange)
        if (isReady()) {
            plainText(exchange, 200, "READY")
        } else {
            plainText(exchange, 503, "READY")
        }
    }

    server.createContext("/metrics") { exchange ->
        if (exchange.requestMethod != "GET") return@createContext methodNotAllowed(exchange)

        val names = parseMetricNames(exchange)
        exchange.responseHeaders.add("Content-Type", TextFormat.CONTENT_TYPE_004)
        exchange.sendResponseHeaders(200, 0)

        exchange.responseBody.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                TextFormat.write004(writer, collectorRegistry.filteredMetricFamilySamples(names))
            }
        }
    }

    server.executor = Executors.newFixedThreadPool(4)
    return server
}

private fun parseMetricNames(exchange: HttpExchange): Set<String> {
    val rawQuery = exchange.requestURI.rawQuery ?: return emptySet()
    return rawQuery
        .split("&")
        .asSequence()
        .mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            if (key != "name[]") return@mapNotNull null
            URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
        }
        .toSet()
}

private fun methodNotAllowed(exchange: HttpExchange) {
    plainText(exchange, 405, "METHOD_NOT_ALLOWED")
}

private fun plainText(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}