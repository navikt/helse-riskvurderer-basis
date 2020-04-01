package no.nav.helse.risk

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlinx.serialization.json.*
import kotlin.test.assertTrue

class InteresseTest {

    val interesser: List<Pair<String, String?>> = listOf(
        "RiskNeed" to null,
        "oppslagsresultat" to "orginfo-open",
        "oppslagsresultat" to "roller"
    )

    @Test
    fun testUinteressant() {
        assertFalse(json {
            "type" to "vurdering"
        }.tilfredsstillerInteresse(interesser))
    }

    @Test
    fun testInteressantUtenInfotype() {
        assertTrue(json {
            "type" to "RiskNeed"
        }.tilfredsstillerInteresse(interesser))
    }

    @Test
    fun testInteressantInfotype() {
        assertTrue(json {
            "type" to "oppslagsresultat"
            "infotype" to "orginfo-open"
        }.tilfredsstillerInteresse(interesser))
    }

    @Test
    fun testUinteressantInfotype() {
        assertFalse(json {
            "type" to "oppslagsresultat"
            "infotype" to "nokogreior"
        }.tilfredsstillerInteresse(interesser))
    }

}