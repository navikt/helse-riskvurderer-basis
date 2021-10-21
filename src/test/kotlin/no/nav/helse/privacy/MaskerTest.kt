package no.nav.helse.privacy

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import no.nav.helse.risk.JsonRisk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MaskerTest {

   val arbeidsforholdEksempel1 = JsonRisk.parseToJsonElement(
      MaskerTest::class.java
         .getResource("/arbeidsforhold_info.json").readText().trim()
   ).jsonArray

   @Test
   fun `masker aaregResponse`() {
      val masker = IdMasker()
      arbeidsforholdEksempel1.toString().apply {
         assertTrue(this.contains("01129400001"))
         assertTrue(this.contains("998877665"))
      }
      masker.mask(arbeidsforholdEksempel1).toString().apply {
         assertFalse(this.contains("01129400001"))
         assertFalse(this.contains("998877665"))
      }
   }

   @Test
   fun `unnta visse felter`() {
      arbeidsforholdEksempel1.toString().apply {
         assertTrue(this.contains("1210118"))
         assertTrue(this.contains("01129400001"))
         assertTrue(this.contains("998877665"))
      }
      IdMasker().mask(arbeidsforholdEksempel1).toString().apply {
         assertFalse(this.contains("1210118"))
         assertFalse(this.contains("01129400001"))
         assertFalse(this.contains("998877665"))
      }
      IdMasker(primitiveFieldNamesExcludedFromMasking = setOf("yrke")).mask(arbeidsforholdEksempel1).toString().apply {
         assertTrue(this.contains("1210118"))
         assertFalse(this.contains("01129400001"))
         assertFalse(this.contains("998877665"))
      }
   }

   @Test
   fun `masker riktig`() {
      val fnr = "01019077777"
      val fnr2 = "01018511111"
      val orgnr = "999888555"
      val orgnr2 = "999888444"

      val data = buildJsonObject {
         put("identifikator", fnr)
         put("orgs", buildJsonObject {
            put(orgnr, buildJsonObject {
               put("fnr", fnr2)
               put("a", "b")
               put("opplysningspliktig", orgnr2)
               put(fnr, "JA")
            })
         })
      }

      val fnrMaskert = sha1(IdMasker.hashingsalt + fnr).substring(0,11) + "(11)"
      val fnr2Maskert = sha1(IdMasker.hashingsalt + fnr2).substring(0,11) + "(11)"
      val orgnrMaskert = sha1(IdMasker.hashingsalt + orgnr).substring(0,9) + "(9)"
      val orgnr2Maskert = sha1(IdMasker.hashingsalt + orgnr2).substring(0,9) + "(9)"

      val expected = buildJsonObject {
         put("identifikator", fnrMaskert)
         put("orgs", buildJsonObject {
            put(orgnrMaskert, buildJsonObject {
               put("fnr", fnr2Maskert)
               put("a", "b")
               put("opplysningspliktig", orgnr2Maskert)
               put(fnrMaskert, "JA")
            })
         })
      }
      println(data)
      println(expected)
      assertEquals(expected, IdMasker().mask(data))
   }
}

