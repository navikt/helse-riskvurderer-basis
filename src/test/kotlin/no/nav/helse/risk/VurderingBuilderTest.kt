package no.nav.helse.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VurderingBuilderTest {

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
            assertEquals(7, this.score)
            assertEquals(5, this.vekt)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "SJEKK-1",
                        begrunnelse = "FEIL-1",
                        score = 1,
                        vekt = 4,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "1",
                        begrunnelse = "noeErFeil",
                        score = 2,
                        vekt = 6,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "2",
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
            assertEquals(0, this.score)
            assertEquals(5, this.vekt)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        begrunnelse = "ser greit ut",
                        score = 0,
                        vekt = 4,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "2",
                        begrunnelse = "np",
                        score = 0,
                        vekt = 5,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "3",
                        begrunnelse = "Sjekk 3 ikke relevant",
                        score = 0,
                        vekt = 0,
                        kreverManuellBehandling = false
                    ),
                    Sjekkresultat(
                        id = "4",
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
            assertEquals(10, this.score)
            assertEquals(10, this.vekt)

            assertEquals(
                listOf(
                    Sjekkresultat(
                        id = "1",
                        begrunnelse = "noeErFeil",
                        score = 1,
                        vekt = 5,
                        kreverManuellBehandling = false,
                        kategorier = listOf("Type-1", "Type-1B")
                    ),
                    Sjekkresultat(
                        id = "2",
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
                        begrunnelse = "3 elementer",
                        score = 3,
                        vekt = 5,
                        kreverManuellBehandling = false
                    )
                ), this.sjekkresultat
            )
        }
    }
}

