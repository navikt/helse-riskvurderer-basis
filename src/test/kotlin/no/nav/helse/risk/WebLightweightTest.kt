package no.nav.helse.risk

import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class WebLightweightTest {

    private var server: HttpServer? = null
    private val client = HttpClient(CIO)

    @AfterEach
    fun tearDown() {
        server?.stop(1)
        client.close()
    }

    @Test
    fun `isalive returns ALIVE when alive`() = runBlocking {
        testServer(
            isReady = { true },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/isalive")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ALIVE", response.bodyAsText())
        }
    }

    @Test
    fun `isalive returns 500 when not alive`() = runBlocking {
        testServer(
            isReady = { true },
            isAlive = { false }
        ) { baseUrl ->
            val response = client.get("$baseUrl/isalive")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("NOT_ALIVE", response.bodyAsText())
        }
    }

    @Test
    fun `isready returns READY when ready`() = runBlocking {
        testServer(
            isReady = { true },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/isready")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("READY", response.bodyAsText())
        }
    }

    @Test
    fun `isready returns 503 when not ready`() = runBlocking {
        testServer(
            isReady = { false },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/isready")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("READY", response.bodyAsText())
        }
    }

    @Test
    fun `metrics returns prometheus format`() = runBlocking {
        val registry = CollectorRegistry()
        Counter.build("webtest_counter_total", "test counter").register(registry).inc()
        Counter.build("another_counter_total", "another test counter").register(registry).inc()

        testServer(
            collectorRegistry = registry,
            isReady = { true },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/metrics")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("another_counter_total"))
            assertTrue(body.contains("webtest_counter_total"))
        }
    }

    @Test
    fun `metrics supports name filter`() = runBlocking {
        testServer(
            isReady = { true },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/metrics?name[]=jvm_threads_live_threads")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `metrics filtering works`() = runBlocking {
        val registry = CollectorRegistry()
        Counter.build("webtest_counter_total", "test counter").register(registry).inc()
        Counter.build("another_counter_total", "another test counter").register(registry).inc()
        Counter.build("athird_counter_total", "athird test counter").register(registry).inc()

        testServer(
            collectorRegistry = registry,
            isReady = { true },
            isAlive = { true }
        ) { baseUrl ->
            val response = client.get("$baseUrl/metrics?name[]=another_counter_total&name[]=athird_counter_total")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("another_counter_total"))
            assertTrue(body.contains("athird_counter_total"))
            assertFalse(body.contains("webtest_counter_total"))
        }
    }

    private suspend fun testServer(
        collectorRegistry: CollectorRegistry = CollectorRegistry(),
        isReady: () -> Boolean,
        isAlive: () -> Boolean,
        block: suspend (String) -> Unit
    ) {
        val localServer = createWebServer(
            port = 0,
            collectorRegistry = collectorRegistry,
            isReady = isReady,
            isAlive = isAlive
        )
        server = localServer
        localServer.start()

        val baseUrl = "http://127.0.0.1:${localServer.address.port}"
        block(baseUrl)
    }
}