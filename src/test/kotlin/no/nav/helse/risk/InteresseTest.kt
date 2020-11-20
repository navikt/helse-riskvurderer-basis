package no.nav.helse.risk

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.*
import kotlin.test.assertTrue

private val json = Json(JsonConfiguration.Stable)

class InteresseTest {

    val interesserSomObjekter = listOf(
        Interesse.riskNeed,
        Interesse(type = "TulleNeed", iterasjonsMatcher = { it == 2 }),
        Interesse.oppslagsresultat("orginfo-open"),
        Interesse.oppslagsresultat("roller")
    )

    @Test
    fun testUinteressant() {
        assertFalse(json {
            "type" to "vurdering"
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantUtenInfotype() {
        assertTrue(json {
            "type" to "RiskNeed"
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantMedIterasjonSomIgnoreres() {
        assertTrue(json {
            "type" to "RiskNeed"
            "iterasjon" to 1
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedManglendeIterasjon() {
        assertFalse(json {
            "type" to "TulleNeed"
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedFeilIterasjon() {
        assertFalse(json {
            "type" to "TulleNeed"
            "iterasjon" to 1
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedRiktigIterasjon() {
        assertTrue(json {
            "type" to "TulleNeed"
            "iterasjon" to 2
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantInfotype() {
        assertTrue(json {
            "type" to "oppslagsresultat"
            "infotype" to "orginfo-open"
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testUinteressantInfotype() {
        assertFalse(json {
            "type" to "oppslagsresultat"
            "infotype" to "nokogreior"
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun interessertIVurdering() {
        val vurdering = json.toJson(Vurderingsmelding.serializer(), Vurderingsmelding(
            infotype = "etellerannet-vurderer",
            vedtaksperiodeId = "123",
            score = 10,
            vekt = 5,
            begrunnelser = listOf("a", "b"),
            begrunnelserSomAleneKreverManuellBehandling = listOf("a")
        )).jsonObject

        assertFalse(vurdering.tilfredsstillerInteresser(interesserSomObjekter))
        assertTrue(vurdering.tilfredsstillerInteresser(listOf(
            Interesse.vurdering("etellerannet-vurderer")
        )))
        assertFalse(vurdering.tilfredsstillerInteresser(listOf(
            Interesse.vurdering("en-annen-vurdering")
        )))
    }

    @Test
    fun riskNeed_Iterasjon() {
        val need1 = json { "type" to "RiskNeed"; "iterasjon" to 1 }
        val need2 = json { "type" to "RiskNeed"; "iterasjon" to 2 }
        val need3 = json { "type" to "RiskNeed"; "iterasjon" to 3 }

        val interessertIAlleIterasjoner = listOf(Interesse.riskNeed)
        val interessertIIterasjon1 = listOf(Interesse.riskNeed(1))
        val interessertIIterasjon2 = listOf(Interesse.riskNeed(2))
        val interessertIIterasjon3 = listOf(Interesse.riskNeed(3))

        val interessertIIterasjon2EllerHoyere = listOf(Interesse.riskNeedMedMinimum(2))

        need1.apply {
            assertTrue(this.tilfredsstillerInteresser(interessertIAlleIterasjoner))
            assertTrue(this.tilfredsstillerInteresser(interessertIIterasjon1))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon2))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon3))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon2EllerHoyere))
        }

        need2.apply {
            assertTrue(this.tilfredsstillerInteresser(interessertIAlleIterasjoner))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon1))
            assertTrue(this.tilfredsstillerInteresser(interessertIIterasjon2))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon3))
            assertTrue(this.tilfredsstillerInteresser(interessertIIterasjon2EllerHoyere))
        }

        need3.apply {
            assertTrue(this.tilfredsstillerInteresser(interessertIAlleIterasjoner))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon1))
            assertFalse(this.tilfredsstillerInteresser(interessertIIterasjon2))
            assertTrue(this.tilfredsstillerInteresser(interessertIIterasjon3))
            assertTrue(this.tilfredsstillerInteresser(interessertIIterasjon2EllerHoyere))
        }
    }

}