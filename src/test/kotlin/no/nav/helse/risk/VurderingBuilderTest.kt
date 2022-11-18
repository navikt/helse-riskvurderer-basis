package no.nav.helse.risk

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VurderingBuilderTest {

    @Test
    fun `variabler og grunnlag`() {
        val noentall = listOf(1, 2, 3)
        val vurdering = VurderingBuilder()
        vurdering.nySjekk(vekt = 4, id = "SJEKK-1") {
            resultat(
                tekst = tekst("To forskjellige verdier ({}% og {}%) 5% 6% opp (7%): {}", 3000, 4.54, noentall),
                score = 1,
                grunnlag = SjekkresultatGrunnlag(versjon = 1, data = buildJsonObject {
                    put("tall", buildJsonArray { noentall.forEach { this.add(it) } })
                })
            )
        }
        vurdering.build().apply {
            assertEquals(listOf("To forskjellige verdier (3000% og 4.54%) 5% 6% opp (7%): [1, 2, 3]"), this.begrunnelser)
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "SJEKK-1",
                        tekst = "To forskjellige verdier (3000% og 4.54%) 5% 6% opp (7%): [1, 2, 3]",
                        begrunnelse = "To forskjellige verdier (3000% og 4.54%) 5% 6% opp (7%): [1, 2, 3]",
                        variabler = listOf("3000", "4.54", "[1, 2, 3]"),
                        score = 1,
                        vekt = 4,
                        kreverManuellBehandling = false,
                        grunnlag = SjekkresultatGrunnlag(versjon = 1, data = JsonRisk.parseToJsonElement("""
                            { "tall" : [1, 2, 3] }
                        """).jsonObject)
                    )
                ), this.sjekkresultat
            )

        }
    }

    @Test
    fun `variabler for passerte sjekker`() {
        val vurdering = VurderingBuilder()
        vurdering.nySjekk(vekt = 4, id = "SJEKK-1") {
            passert(
                tekst = tekst("Helt grei verdi: {}.", 3000),
            )
        }
        vurdering.build().apply {
            assertEquals(listOf("Helt grei verdi: 3000."), this.passerteSjekker)
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "SJEKK-1",
                        tekst = "Helt grei verdi: 3000.",
                        begrunnelse = "Helt grei verdi: 3000.",
                        variabler = listOf("3000"),
                        score = 0,
                        vekt = 4,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }
    }

    @Test
    fun etParBegrunnelser() {
        val vurdering = VurderingBuilder()
        vurdering.nySjekk(vekt = 4, id = "SJEKK-1") { resultat("FEIL-1", score = 1) }
        vurdering.nySjekk(vekt = 6) { resultat("noeErFeil", score = 2) }
        vurdering.nySjekk(vekt = 7) { resultat("endaMerErFeil", score = 4) }
        vurdering.build(5).apply {
            assertEquals(listOf("FEIL-1", "noeErFeil", "endaMerErFeil"), this.begrunnelser)
            assertTrue(this.begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertEquals(emptyList<String>(), passerteSjekker)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "SJEKK-1",
                        tekst = "FEIL-1",
                        begrunnelse = "FEIL-1",
                        score = 1,
                        vekt = 4,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "1",
                        tekst = "noeErFeil",
                        begrunnelse = "noeErFeil",
                        score = 2,
                        vekt = 6,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "2",
                        tekst = "endaMerErFeil",
                        begrunnelse = "endaMerErFeil",
                        score = 4,
                        vekt = 7,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }
    }


    @Test
    fun `to passerte sjekker og 2 ikke-aktuelle`() {
        val vurdering = VurderingBuilder()
        vurdering.passertSjekk(vekt = 4, "ser greit ut")
        vurdering.nySjekk(vekt = 5) { passert("np") }
        vurdering.nySjekk(vekt = 10) { ikkeAktuell("Sjekk 3 ikke relevant") }
        vurdering.ikkeAktuellSjekk("Sjekk 4 er ikke aktuell")
        vurdering.build(5).apply {
            assertEquals(emptyList<String>(), this.begrunnelser)
            assertTrue(this.begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertEquals(listOf("ser greit ut", "np"), this.passerteSjekker)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        tekst = "ser greit ut",
                        begrunnelse = "ser greit ut",
                        score = 0,
                        vekt = 4,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "2",
                        tekst = "np",
                        begrunnelse = "np",
                        score = 0,
                        vekt = 5,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "3",
                        tekst = "Sjekk 3 ikke relevant",
                        begrunnelse = "Sjekk 3 ikke relevant",
                        score = 0,
                        vekt = 0,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "4",
                        tekst = "Sjekk 4 er ikke aktuell",
                        begrunnelse = "Sjekk 4 er ikke aktuell",
                        score = 0,
                        vekt = 0,
                        kreverManuellBehandling = false
                    ),
                ), this.sjekkresultat
            )
        }
    }

    @Test
    fun begrunnelserSomAleneKreverManuellBehandling() {
        val vurdering = VurderingBuilder()
        vurdering.nySjekk(vekt = 5, kategorier = listOf("Type-1")) {
            resultat("noeErFeil", 1, ytterligereKategorier = listOf("Type-1B"))
        }
        vurdering.nySjekk(vekt = 5) {
            kreverManuellBehandling("Noe er ALvorlig feil")
        }
        vurdering.build(10).apply {
            assertEquals(listOf("noeErFeil", "Noe er ALvorlig feil"), this.begrunnelser)
            assertEquals(listOf("Noe er ALvorlig feil"), this.begrunnelserSomAleneKreverManuellBehandling)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        tekst = "noeErFeil",
                        begrunnelse = "noeErFeil",
                        score = 1,
                        vekt = 5,
                        kreverManuellBehandling = false,
                        kategorier = listOf("Type-1", "Type-1B")
                    ),
                    Sjekkresultat(
                        id = "2",
                        tekst = "Noe er ALvorlig feil",
                        begrunnelse = "Noe er ALvorlig feil",
                        score = 10,
                        vekt = 5,
                        kreverManuellBehandling = true
                    ),
                ), this.sjekkresultat
            )

        }
    }


    @Test
    fun `negativ score skal funke for passert`() {
        val vurdering = VurderingBuilder()
        vurdering.nySjekk(id = "score0", vekt = 5) {
            passert("OK med 0")
        }
        vurdering.nySjekk(id = "score-3", vekt = 5) {
            passert(tekst = "OK med -3", score = -3)
        }
        vurdering.build(10).apply {
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "score0",
                        tekst = "OK med 0",
                        score = 0,
                        vekt = 5,
                        kreverManuellBehandling = false,
                    ),
                    Sjekkresultat(
                        id = "score-3",
                        tekst = "OK med -3",
                        score = -3,
                        vekt = 5,
                        kreverManuellBehandling = false,
                    ),
                ), this.sjekkresultat
            )
            assertEquals(
                listOf("OK med 0", "OK med -3"),
                passerteSjekker
            )
            assertTrue(begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertTrue(begrunnelser.isEmpty())
        }
        assertThrows<IllegalArgumentException>("positiv score er ugyldig") {
            vurdering.nySjekk(id = "score1", vekt = 5) {
                passert(tekst = "OK", score = 1)
            }
        }
    }



    @Test
    fun `sjekk delegert til funksjon`() {
        fun sjekk(info: List<Int>): VurderingBuilder.SjekkresultatBuilder.() -> Sjekkresultat =
            {
                if (info.isEmpty())
                    ikkeAktuell("Ingen data")
                else if (info.size == 1)
                    passert("Kun ett element")
                else resultat("${info.size} elementer", score = info.size)
            }

        VurderingBuilder().apply {
            nySjekk(vekt = 5, sjekk = sjekk(emptyList()))
        }.build().apply {
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        tekst = "Ingen data",
                        begrunnelse = "Ingen data",
                        score = 0,
                        vekt = 0,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }

        VurderingBuilder().apply {
            nySjekk(vekt = 5, sjekk = sjekk(listOf(1)))
        }.build().apply {
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        tekst = "Kun ett element",
                        begrunnelse = "Kun ett element",
                        score = 0,
                        vekt = 5,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }

        VurderingBuilder().apply {
            nySjekk(vekt = 5, sjekk = sjekk(listOf(1, 2, 3)))
        }.build().apply {
            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        tekst = "3 elementer",
                        begrunnelse = "3 elementer",
                        score = 3,
                        vekt = 5,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }
    }

    @Test
    fun `nySjekk returnerer sjekkresultatet (kan i sjeldne tilfeller tenkes regler som avhenger av resultatet fra andre regler i samme vurderingstjeneste)`() {
        VurderingBuilder().apply {
            nySjekk(vekt = 11, id = "entest") {
                kreverManuellBehandling("jauda")
            }.also { sjekkres ->
                kotlin.test.assertEquals(
                    Sjekkresultat(
                        id = "entest",
                        tekst = "jauda",
                        begrunnelse = "jauda",
                        variabler = listOf(),
                        score = 10,
                        vekt = 11,
                        kreverManuellBehandling = true,
                    ), sjekkres
                )
            }
        }

        VurderingBuilder().apply {
            nySjekk(vekt = 9, id = "annentest") {
                passert("all good")
            }.also { sjekkres ->
                kotlin.test.assertEquals(
                    Sjekkresultat(
                        id = "annentest",
                        tekst = "all good",
                        begrunnelse = "all good",
                        variabler = listOf(),
                        score = 0,
                        vekt = 9,
                        kreverManuellBehandling = false,
                    ), sjekkres
                )
            }
        }

        VurderingBuilder().apply {
            nySjekk(vekt = 20, id = "tredjetest") {
                ikkeAktuell("nothing to check")
            }.also { sjekkres ->
                kotlin.test.assertEquals(
                    Sjekkresultat(
                        id = "tredjetest",
                        tekst = "nothing to check",
                        begrunnelse = "nothing to check",
                        variabler = listOf(),
                        score = 0,
                        vekt = 0,
                        kreverManuellBehandling = false,
                    ), sjekkres
                )
            }
        }
    }
}

