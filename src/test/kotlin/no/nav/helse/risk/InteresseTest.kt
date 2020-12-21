package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = JsonRisk

class InteresseTest {

    val interesserSomObjekter = listOf(
        Interesse.riskNeed,
        Interesse(type = "TulleNeed", iterasjonsMatcher = { it == 2 }),
        Interesse.oppslagsresultat("orginfo-open"),
        Interesse.oppslagsresultat("roller")
    )

    @Test
    fun testUinteressant() {
        assertFalse(buildJsonObject {
            put("type", "vurdering")
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantUtenInfotype() {
        assertTrue(buildJsonObject {
            put("type", "RiskNeed")
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantMedIterasjonSomIgnoreres() {
        assertTrue(buildJsonObject {
            put("type", "RiskNeed")
            put("iterasjon", 1)
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedManglendeIterasjon() {
        assertFalse(buildJsonObject {
            put("type", "TulleNeed")
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedFeilIterasjon() {
        assertFalse(buildJsonObject {
            put("type", "TulleNeed")
            put("iterasjon", 1)
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedRiktigIterasjon() {
        assertTrue(buildJsonObject {
            put("type", "TulleNeed")
            put("iterasjon", 2)
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testInteressantInfotype() {
        assertTrue(buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "orginfo-open")
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Test
    fun testUinteressantInfotype() {
        assertFalse(buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "nokogreior")
        }.tilfredsstillerInteresser(interesserSomObjekter))
    }

    @Serializable
    data class Tulletype(val a: String)

    @Test
    fun interesserKanAngisSomOppslagstype() {
        val melding = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "orginfo-open")
        }
        assertTrue(
            melding.tilfredsstillerInteresser(
                listOf(
                    Interesse.oppslagsresultat(Oppslagtype("orginfo-open", Tulletype.serializer()))
                )
            )
        )
        assertFalse(
            melding.tilfredsstillerInteresser(
                listOf(
                    Interesse.oppslagsresultat(Oppslagtype("nokoanna", Tulletype.serializer()))
                )
            )
        )
    }

    @Test
    fun interessertIVurdering() {
        val vurdering = json.encodeToJsonElement(
            Vurderingsmelding.serializer(), Vurderingsmelding(
                infotype = "etellerannet-vurderer",
                vedtaksperiodeId = "123",
                score = 10,
                vekt = 5,
                begrunnelser = listOf("a", "b"),
                begrunnelserSomAleneKreverManuellBehandling = listOf("a")
            )
        ).jsonObject

        assertFalse(vurdering.tilfredsstillerInteresser(interesserSomObjekter))
        assertTrue(
            vurdering.tilfredsstillerInteresser(
                listOf(
                    Interesse.vurdering("etellerannet-vurderer")
                )
            )
        )
        assertFalse(
            vurdering.tilfredsstillerInteresser(
                listOf(
                    Interesse.vurdering("en-annen-vurdering")
                )
            )
        )
    }

    @Test
    fun riskNeed_Iterasjon() {
        val need1 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 1) }
        val need2 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 2) }
        val need3 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 3) }

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