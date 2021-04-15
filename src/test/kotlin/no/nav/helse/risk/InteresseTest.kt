package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = JsonRisk

class InteresseTest {

    @BeforeEach
    fun skipProductionChecks() {
        Sanity.setSkipSanityChecksForProduction()
    }

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
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantUtenInfotype() {
        assertTrue(buildJsonObject {
            put("type", "RiskNeed")
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantMedIterasjonSomIgnoreres() {
        assertTrue(buildJsonObject {
            put("type", "RiskNeed")
            put("iterasjon", 1)
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedManglendeIterasjon() {
        assertFalse(buildJsonObject {
            put("type", "TulleNeed")
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedFeilIterasjon() {
        assertFalse(buildJsonObject {
            put("type", "TulleNeed")
            put("iterasjon", 1)
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantTypeMedRiktigIterasjon() {
        assertTrue(buildJsonObject {
            put("type", "TulleNeed")
            put("iterasjon", 2)
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testInteressantInfotype() {
        assertTrue(buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "orginfo-open")
        }.erInteressant(interesserSomObjekter))
    }

    @Test
    fun testUinteressantInfotype() {
        assertFalse(buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "nokogreior")
        }.erInteressant(interesserSomObjekter))
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
            melding.erInteressant(
                listOf(Interesse.oppslagsresultat(Oppslagtype("orginfo-open", Tulletype.serializer())))
            )
        )
        assertFalse(
            melding.erInteressant(
                listOf(Interesse.oppslagsresultat(Oppslagtype("nokoanna", Tulletype.serializer())))
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
                begrunnelserSomAleneKreverManuellBehandling = listOf("a"),
                sjekkresultater = listOf(
                    Sjekkresultat(
                        id = "SJEKK-1",
                        begrunnelse = "noko",
                        variabler = listOf("1000"),
                        score = 1,
                        vekt = 4,
                        kreverManuellBehandling = false
                    )
                )
            )
        ).jsonObject

        assertFalse(vurdering.erInteressant(interesserSomObjekter))
        assertTrue(
            vurdering.erInteressant(listOf(Interesse.vurdering("etellerannet-vurderer"))),
        )
        assertFalse(
            vurdering.erInteressant(listOf(Interesse.vurdering("en-annen-vurdering")))
        )
    }

    @Test
    fun riskNeed_Iterasjon() {
        val need1 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 1) }
        val need2 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 2) }
        val need3 = buildJsonObject { put("type", "RiskNeed"); put("iterasjon", 3) }

        val interessertIAlleIterasjoner = Interesse.riskNeed
        val interessertIIterasjon1 = Interesse.riskNeed(1)
        val interessertIIterasjon2 = Interesse.riskNeed(2)
        val interessertIIterasjon3 = Interesse.riskNeed(3)

        val interessertIIterasjon2EllerHoyere = Interesse.riskNeedMedMinimum(2)

        need1.apply {
            assertTrue(this.erInteressant(listOf(interessertIAlleIterasjoner)))
            assertTrue(this.erInteressant(listOf(interessertIIterasjon1)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon2)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon3)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon2EllerHoyere)))
        }

        need2.apply {
            assertTrue(this.erInteressant(listOf(interessertIAlleIterasjoner)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon1)))
            assertTrue(this.erInteressant(listOf(interessertIIterasjon2)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon3)))
            assertTrue(this.erInteressant(listOf(interessertIIterasjon2EllerHoyere)))
        }

        need3.apply {
            assertTrue(this.erInteressant(listOf(interessertIAlleIterasjoner)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon1)))
            assertFalse(this.erInteressant(listOf(interessertIIterasjon2)))
            assertTrue(this.erInteressant(listOf(interessertIIterasjon3)))
            assertTrue(this.erInteressant(listOf(interessertIIterasjon2EllerHoyere)))
        }
    }

    @Test
    fun `interesser med ikkePaakrevdHvis`() {
        val oppslag1 = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "oppslag-1")
        }
        val oppslag2A = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "oppslag-2")
            put("data", buildJsonObject {
                put("status", "A")
            })
        }
        val oppslag2B = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "oppslag-2")
            put("data", buildJsonObject {
                put("status", "B")
            })
        }
        val oppslag2_UtenStatus = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "oppslag-2")
            put("data", buildJsonObject {
                put("NOKO ANNA", "HEI HEI")
            })
        }

        val interesse = Interesse.oppslagsresultat("oppslag-1", ikkePaakrevdHvis = { meldinger ->
            meldinger.finnOppslagsresultat("oppslag-2")!!
                .jsonObject["status"]!!
                .jsonPrimitive.content == "A"
        })

        assertTrue(interesse.tilfredsstillesAv(listOf(oppslag1)))
        assertTrue(interesse.tilfredsstillesAv(listOf(oppslag2A)))

        assertFalse(interesse.tilfredsstillesAv(listOf(oppslag2B)))
        assertFalse(interesse.tilfredsstillesAv(listOf(oppslag2_UtenStatus)))
    }

}