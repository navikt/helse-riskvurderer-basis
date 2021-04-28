package no.nav.helse.risk

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VurderingTypeTest {

   private fun riskneed(vedtaksperiodeId: String) =
      RiskNeed(
         vedtaksperiodeId = vedtaksperiodeId,
         fnr = "01010199999",
         organisasjonsnummer = "999888777",
         iterasjon = 1,
         behovOpprettet = LocalDateTime.now().toString()
      )

   @Test
   fun `fastslå VurderingType utifra vedtaksperiodeId`() {
      riskneed("123").apply {
         assertEquals(VurderingType.PROD, this.vurderingstypeForventet())
         assertFalse(this.vurderingstypeForventet().erStageEllerAnalyse())
         assertFalse(this.erKunAnalyse())
      }

      riskneed("prana:123").apply {
         assertEquals(VurderingType.ANALYSE, this.vurderingstypeForventet())
         assertTrue(this.vurderingstypeForventet().erStageEllerAnalyse())
         assertTrue(this.erKunAnalyse())
      }

      riskneed("wHaTeVeR:123").apply {
         assertEquals(VurderingType.ANALYSE, this.vurderingstypeForventet())
         assertTrue(this.vurderingstypeForventet().erStageEllerAnalyse())
         assertTrue(this.erKunAnalyse())
      }

      riskneed("stage:123").apply {
         assertEquals(VurderingType.STAGE, this.vurderingstypeForventet())
         assertTrue(this.vurderingstypeForventet().erStageEllerAnalyse())
         assertFalse(this.erKunAnalyse())
      }
   }

}
