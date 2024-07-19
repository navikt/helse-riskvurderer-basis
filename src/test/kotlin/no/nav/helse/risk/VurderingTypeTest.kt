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
         behovOpprettet = LocalDateTime.now().toString(),
         riskNeedId = "RID" + vedtaksperiodeId
      )

   @Test
   fun `fastslå VurderingType utifra vedtaksperiodeId`() {
      riskneed("123").apply {
         assertEquals(VurderingType.PROD, this.vurderingstypeForventet())
         assertFalse(this.vurderingstypeForventet().erAnalyse())
         assertFalse(this.erKunAnalyse())
      }

      riskneed("prana:123").apply {
         assertEquals(VurderingType.ANALYSE, this.vurderingstypeForventet())
         assertTrue(this.vurderingstypeForventet().erAnalyse())
         assertTrue(this.erKunAnalyse())
      }

      riskneed("wHaTeVeR:123").apply {
         assertEquals(VurderingType.ANALYSE, this.vurderingstypeForventet())
         assertTrue(this.vurderingstypeForventet().erAnalyse())
         assertTrue(this.erKunAnalyse())
      }

   }

}
