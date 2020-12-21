package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeldingerTest {

    private val jsonFlexible = JsonRisk

    val riskNeed = buildJsonObject {
        put("type", "RiskNeed")
        put("vedtaksperiodeId", "1")
        put("organisasjonsnummer", "123456789")
        put("fnr", "01010100000")
        put("behovOpprettet", LocalDateTime.now().toString())
        put("iterasjon", 1)
        put("foersteFravaersdag", "2020-01-01")
        put("sykepengegrunnlag", 50000.0)
        put("periodeFom", "2020-02-01")
        put("periodeTom", "2020-02-28")
    }
    val testoppslag = buildJsonObject {
        put("type", "oppslagsresultat")
        put("infotype", "testoppslag")
        put("data", buildJsonObject {
            put("a", "b")
            put("fornavn", "Ola")
            put("etternavn", "Nordmann")
        })
        put("vedtaksperiodeId", "1")
    }
    val meldinger = listOf(riskNeed, testoppslag)

    @Test
    fun finnUnikVedtaksperiodeId() {
        assertEquals("1", meldinger.finnUnikVedtaksperiodeId())
    }

    @Test
    fun `finnOppslagsresultat returnerer data-elementet`() {
        assertEquals(
            buildJsonObject { put("a", "b"); put("fornavn", "Ola"); put("etternavn", "Nordmann") },
            meldinger.finnOppslagsresultat("testoppslag")
        )
    }

    @Serializable
    data class TestOppslag(val fornavn: String, val etternavn: String)

    val TEST_OPPSLAGSTYPE = Oppslagtype("testoppslag", TestOppslag.serializer())

    @Test
    fun `Typet oppslagsresultat gitt av Oppslagtype ignorerer ukjente felter ("a",  "b")`() {
        assertEquals(TestOppslag("Ola", "Nordmann"), meldinger.finnPaakrevdOppslagsresultat(TEST_OPPSLAGSTYPE))
    }

    @Test
    fun `manglende men paakrevd oppslagsresultat`() {
        assertThrows<java.lang.IllegalStateException> {
            listOf(riskNeed).finnPaakrevdOppslagsresultat(TEST_OPPSLAGSTYPE)
        }
    }

    @Test
    fun `manglende men IKKE paakrevd oppslagsresultat`() {
        assertNull(listOf(riskNeed).finnOppslagsresultat(TEST_OPPSLAGSTYPE))
    }

    @Test
    fun finnRiskNeed() {
        assertEquals(jsonFlexible.decodeFromJsonElement(RiskNeed.serializer(), riskNeed), meldinger.finnRiskNeed())
    }

    @Test
    fun `finnOppslagsresultat gir NULL hvis ikke finnes`() {
        assertNull(meldinger.finnOppslagsresultat("noeAnnet"))
    }

    @Test
    fun vurderingsmeldingDeserialiseres() {
        val melding = buildJsonObject {
            put("type", "vurdering")
            put("infotype", "whatever")
            put("vedtaksperiodeId", UUID.randomUUID().toString())
            put("score", 6)
            put("vekt", 7)
            put("begrunnelser", buildJsonArray { add("something"); add("showstopper") })
            put("begrunnelserSomAleneKreverManuellBehandling", buildJsonArray { add("showstopper") })
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
        val melding = buildJsonObject {
            put("type", "vurdering")
            put("infotype", "whatever")
            put("vedtaksperiodeId", UUID.randomUUID().toString())
            put("score", 6)
            put("vekt", 7)
            put("begrunnelser", buildJsonArray { add("something"); add("showstopper") })
        }
        val vurderingsmelding = melding.tilVurderingsmelding()
        vurderingsmelding.apply {
            assertEquals("whatever", this.infotype)
            assertEquals(listOf("something", "showstopper"), this.begrunnelser)
            assertNull(this.begrunnelserSomAleneKreverManuellBehandling)
        }
    }

    @Test
    fun `skal kunne deSerialisere RiskNeed med og uten optional verdier`() {
        buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("foersteFravaersdag", "2020-01-01")
            put("sykepengegrunnlag", 50000.0)
            put("periodeFom", "2020-02-01")
            put("periodeTom", "2020-02-28")
            put("fjlksdfdaslkfj", "sdfdskfdsj")
        }.jsonObject.tilRiskNeed().apply {
            assertEquals("1", this.vedtaksperiodeId)
            assertNull(this.originalBehov)
            assertNull(this.retryCount)
            assertNull(this.isRetry)
        }

        buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("isRetry", true)
        }.jsonObject.tilRiskNeed().apply {
            assertEquals("1", this.vedtaksperiodeId)
            assertTrue(this.isRetry ?: false)
            assertNull(this.retryCount)
        }

        buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("isRetry", true)
            put("retryCount", 2)
        }.jsonObject.tilRiskNeed().apply {
            assertEquals("1", this.vedtaksperiodeId)
            assertTrue(this.isRetry ?: false)
            assertEquals(2, this.retryCount)
        }

        buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("isRetry", true)
            put("retryCount", 2)
            put("originalBehov", buildJsonObject {
                put("felt1", "verdi1")
            })
        }.jsonObject.tilRiskNeed().apply {
            assertEquals("1", this.vedtaksperiodeId)
            assertEquals(buildJsonObject {
                put("felt1", "verdi1")
            }, this.originalBehov)
        }
    }
}