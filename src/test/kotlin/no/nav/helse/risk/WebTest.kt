package no.nav.helse.risk

import io.ktor.http.*
import io.ktor.server.testing.*
import io.prometheus.client.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@io.ktor.util.KtorExperimentalAPI
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class WebTest {

   @Test
   fun `reports isalive status for nais`() {
      withTestApplication({
         riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
      }) {
         handleRequest(HttpMethod.Get, "/isalive").apply {
            assertTrue { response.status()?.isSuccess() ?: false }
         }
      }

   }

   @Test
   fun `reports isready status for nais`() {
      withTestApplication({
         riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
      }) {
         handleRequest(HttpMethod.Get, "/isready").apply {
            assertTrue { response.status()?.isSuccess() ?: false }
         }
      }
   }

   @Test
   fun `reports negative isalive status for nais`() {
      withTestApplication({
         riskvurderer(CollectorRegistry.defaultRegistry, isReady = { true }, isAlive = { false })
      }) {
         handleRequest(HttpMethod.Get, "/isalive").apply {
            assertEquals(false, response.status()?.isSuccess())
         }
      }

   }

   @Test
   fun `reports negative isready status for nais`() {
      withTestApplication({
         riskvurderer(CollectorRegistry.defaultRegistry, isReady = { false }, isAlive = { true })
      }) {
         handleRequest(HttpMethod.Get, "/isready").apply {
            assertEquals(false, response.status()?.isSuccess())
         }
      }
   }

   @Test
   fun `reports metrics`() {
      withTestApplication({
         riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
      }) {
         handleRequest(HttpMethod.Get, "/metrics").apply {
            assertTrue { response.status()?.isSuccess() ?: false }
         }
      }

   }

}
