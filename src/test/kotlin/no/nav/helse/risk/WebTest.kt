package no.nav.helse.risk

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.prometheus.client.CollectorRegistry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class WebTest {

   @Test
   fun `reports isalive status for nais`() {
      testApplication {
         application {
            riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
         }
         client.get("/isalive").apply {
            assertTrue { status.isSuccess() }
         }
      }
   }

   @Test
   fun `reports isready status for nais`() {
      testApplication {
         application {
            riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
         }
         client.get("/isready").apply {
            assertTrue { status.isSuccess() }
         }
      }
   }

   @Test
   fun `reports negative isalive status for nais`() {
      testApplication {
         application {
            riskvurderer(CollectorRegistry.defaultRegistry, isReady = { true }, isAlive = { false })
         }
         client.get("/isalive").apply {
            assertFalse { status.isSuccess() }
         }
      }
   }

   @Test
   fun `reports negative isready status for nais`() {
      testApplication {
         application {
            riskvurderer(CollectorRegistry.defaultRegistry, isReady = { false }, isAlive = { true })
         }
         client.get("/isready").apply {
            assertFalse { status.isSuccess() }
         }
      }
   }

   @Test
   fun `reports metrics`() {
      testApplication {
         application {
            riskvurderer(CollectorRegistry.defaultRegistry, { true }, { true })
         }
         client.get("/metrics").apply {
            assertTrue { status.isSuccess() }
         }
      }
   }

}
