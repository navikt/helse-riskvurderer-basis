package no.nav.helse.risk

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.*
import kotlin.test.assertTrue

class InteresseTest {

    val interesserSomObjekter = listOf(
        Interesse(type = "RiskNeed"),
        Interesse(type = "TulleNeed", iterasjon = 2),
        Interesse(type = "oppslagsresultat", infotype = "orginfo-open"),
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

}