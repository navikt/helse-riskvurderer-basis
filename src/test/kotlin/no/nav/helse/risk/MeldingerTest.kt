package no.nav.helse.risk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeldingerTest {

    private val json = Json(JsonConfiguration.Stable)
    private val jsonFlexible = Json(JsonConfiguration.Stable.copy(
        ignoreUnknownKeys = true
    ))
    val riskNeed = json {
        "type" to "RiskNeed"
        "vedtaksperiodeId" to "1"
        "organisasjonsnummer" to "123456789"
        "fnr" to "01010100000"
        "behovOpprettet" to  LocalDateTime.now().toString()
        "iterasjon" to 1
        "foersteFravaersdag" to "2020-01-01"
        "sykepengegrunnlag" to 50000.0
        "periodeFom" to "2020-02-01"
        "periodeTom" to "2020-02-28"
    }
    val testoppslag = json {
        "type" to "oppslagsresultat"
        "infotype" to "testoppslag"
        "data" to json {
            "a" to "b"
        }
        "vedtaksperiodeId" to "1"
    }
    val meldinger = listOf(riskNeed, testoppslag)

    @Test
    fun finnUnikVedtaksperiodeId() {
        assertEquals("1", meldinger.finnUnikVedtaksperiodeId())
    }

    @Test
    fun `finnOppslagsresultat returnerer data-elementet`() {
        assertEquals(json { "a" to "b" }, meldinger.finnOppslagsresultat("testoppslag"))
    }
    @Test
    fun finnRiskNeed() {
        assertEquals(jsonFlexible.fromJson(RiskNeed.serializer(), riskNeed), meldinger.finnRiskNeed())
    }
    @Test
    fun `finnOppslagsresultat gir NULL hvis ikke finnes`() {
        assertNull(meldinger.finnOppslagsresultat("noeAnnet"))
    }

    @Test
    fun vurderingsmeldingDeserialiseres() {
        val melding = json {
            "type" to typeVurdering
            "infotype" to "whatever"
            "vedtaksperiodeId" to UUID.randomUUID().toString()
            "score" to 6
            "vekt" to 7
            "begrunnelser" to jsonArray { +"something"; +"showstopper" }
            "begrunnelserSomAleneKreverManuellBehandling" to jsonArray { +"showstopper" }
        }
        val vurderingsmelding = melding.tilVurderingsmelding()
        vurderingsmelding.apply {
            assertEquals("whatever", this.infotype)
            assertEquals(listOf("something", "showstopper"), this.begrunnelser)
            assertEquals(listOf("showstopper"), this.begrunnelserSomAleneKreverManuellBehandling)
        }
    }

    @Test
    fun `vurderingsmelding kan deSerialiseres uten begrunnelserSomAleneKreverManuellBehandling`() {
        val melding = json {
            "type" to typeVurdering
            "infotype" to "whatever"
            "vedtaksperiodeId" to UUID.randomUUID().toString()
            "score" to 6
            "vekt" to 7
            "begrunnelser" to jsonArray { +"something"; +"showstopper" }
        }
        val vurderingsmelding = melding.tilVurderingsmelding()
        vurderingsmelding.apply {
            assertEquals("whatever", this.infotype)
            assertEquals(listOf("something", "showstopper"), this.begrunnelser)
            assertNull(this.begrunnelserSomAleneKreverManuellBehandling)
        }
    }
}