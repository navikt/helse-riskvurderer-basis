package no.nav.helse.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VurderingBuilderTest {

    @Test
    fun etParBegrunnelser_LEGACY() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 2)
        vurdering.begrunnelse("endaMerErFeil", 5)
        vurdering.build(5).apply {
            assertEquals(listOf("noeErFeil", "endaMerErFeil"), this.begrunnelser)
            assertTrue(this.begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertEquals(emptyList<String>(), passerteSjekker)
            assertEquals(7, this.score)
            assertEquals(5, this.vekt)
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
    fun passerteSjekker_LEGACY() {
        val vurdering = VurderingBuilder()
        vurdering.passerteSjekk("ser greit ut")
        vurdering.passerteSjekk("np")
        vurdering.build(5).apply {
            assertEquals(emptyList<String>(), this.begrunnelser)
            assertTrue(this.begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertEquals(listOf("ser greit ut", "np"), this.passerteSjekker)
            assertEquals(0, this.score)
            assertEquals(5, this.vekt)
        }
    }

    @Test
    fun passerteSjekker() {
        val vurdering = VurderingBuilder()
        vurdering.passertSjekk(vekt = 4, "ser greit ut")
        vurdering.nySjekk(vekt = 5) { passert("np") }
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
                ), this.sjekkresultat
            )
        }
    }


    @Test
    fun scoreBlirAldriOver10_LEGACY() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 8)
        vurdering.begrunnelse("endaMerErFeil", 9)
        vurdering.build(7).apply {
            assertEquals(listOf("noeErFeil", "endaMerErFeil"), this.begrunnelser)
            assertTrue(this.begrunnelserSomAleneKreverManuellBehandling.isEmpty())
            assertEquals(10, this.score)
            assertEquals(7, this.vekt)
        }
    }

    @Test
    fun vektOver10TillatesIkke_LEGACY() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 8)
        assertThrows<IllegalArgumentException> { vurdering.build(11) }
    }

    @Test
    fun begrunnelserSomAleneKreverManuellBehandling_LEGACY() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 1)
        vurdering.begrunnelseSomKreverManuellBehandling("Noe er ALvorlig feil")
        vurdering.build(10).apply {
            assertEquals(listOf("noeErFeil", "Noe er ALvorlig feil"), this.begrunnelser)
            assertEquals(listOf("Noe er ALvorlig feil"), this.begrunnelserSomAleneKreverManuellBehandling)
            assertEquals(10, this.score)
            assertEquals(10, this.vekt)
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
}

