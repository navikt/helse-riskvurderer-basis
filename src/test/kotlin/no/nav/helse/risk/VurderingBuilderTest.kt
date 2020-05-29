package no.nav.helse.risk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VurderingBuilderTest {

    @Test
    fun etParBegrunnelser() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 2)
        vurdering.begrunnelse("endaMerErFeil", 5)
        vurdering.build(5).apply {
            assertEquals(listOf("noeErFeil", "endaMerErFeil"), this.begrunnelser)
            assertNull(this.begrunnelserSomAleneKreverManuellBehandling)
            assertEquals(7, this.score)
            assertEquals(5, this.vekt)
        }
    }

    @Test
    fun scoreBlirAldriOver10() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 8)
        vurdering.begrunnelse("endaMerErFeil", 9)
        vurdering.build(7).apply {
            assertEquals(listOf("noeErFeil", "endaMerErFeil"), this.begrunnelser)
            assertNull(this.begrunnelserSomAleneKreverManuellBehandling)
            assertEquals(10, this.score)
            assertEquals(7, this.vekt)
        }
    }

    @Test
    fun vektOver10TillatesIkke() {
        val vurdering = VurderingBuilder()
        vurdering.begrunnelse("noeErFeil", 8)
        assertThrows<IllegalArgumentException> { vurdering.build(11) }
    }

    @Test
    fun begrunnelserSomAleneKreverManuellBehandling() {
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
}