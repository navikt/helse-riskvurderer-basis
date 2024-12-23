package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import no.nav.helse.privacy.IdMasker.Companion.hashingsalt
import no.nav.helse.privacy.sha1
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.*
import kotlin.test.*

class MeldingerTest {
    init {
        Sanity.setSkipSanityChecksForProduction()
    }

    private val jsonFlexible = JsonRisk

    val riskNeed = buildJsonObject {
        put("type", "RiskNeed")
        put("vedtaksperiodeId", "1")
        put("riskNeedId", "rid1")
        put("organisasjonsnummer", "123456789")
        put("fnr", "01010100000")
        put("behovOpprettet", LocalDateTime.now().toString())
        put("iterasjon", 1)
    }
    val testoppslag = buildJsonObject {
        put("type", "oppslagsresultat")
        put("infotype", "testoppslag")
        put("riskNeedId", "rid1")
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
    fun `Typet oppslagsresultat gitt av Oppslagtype ignorerer ukjente felter (a,  b)`() {
        assertEquals(TestOppslag("Ola", "Nordmann"), meldinger.finnPaakrevdOppslagsresultat(TEST_OPPSLAGSTYPE))
    }

    @Test
    fun `deserialiseringsfeil med logging til tjenestelogg`() {
        val manglerEtternavn = buildJsonObject {
            put("type", "oppslagsresultat")
            put("infotype", "testoppslag")
            put("data", buildJsonObject {
                put("a", "b")
                put("fornavn", "Ola")
                put("fnr-ident", "01019012345")
            })
            put("vedtaksperiodeId", "1")
        }

        val logTap = LogTapper(Sanity.getSecureLogger())
        val meldinger = listOf(manglerEtternavn)

        assertThrows<SerializationException> {
            meldinger.finnPaakrevdOppslagsresultat(TEST_OPPSLAGSTYPE)
        }
        assertEquals(0, logTap.messages().size,
            "skal ikke logge når ikke logOnDeserializationError er satt")

        assertThrows<SerializationException> {
            meldinger.finnPaakrevdOppslagsresultat(TEST_OPPSLAGSTYPE, true)
        }
        assertEquals(1, logTap.messages().size)


        assertThrows<SerializationException> {
            meldinger.finnOppslagsresultat(TEST_OPPSLAGSTYPE)
        }
        assertEquals(1, logTap.messages().size,
            "skal ikke logge når ikke logOnDeserializationError er satt")

        assertThrows<SerializationException> {
            meldinger.finnOppslagsresultat(TEST_OPPSLAGSTYPE, true)
        }
        assertEquals(2, logTap.messages().size)

        logTap.messages().forEach {
            assertEquals(
                """SerializationException: Field 'etternavn' is required for type with serial name 'no.nav.helse.risk.MeldingerTest.TestOppslag', but it was missing: {
    "a": "b",
    "fornavn": "Ola",
    "fnr-ident": "${sha1(hashingsalt + "01019012345").take(11)}(11)"
}""", it, "Skal logge original JSON til tjenestelogg med maskerte identer"
            )
        }
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
    fun tilleggsdata() {
        val riskneedJson = buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("riskNeedId", "rid1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("tilleggsdata", buildJsonObject {
                put("data1", buildJsonObject {
                    put("theKey", JsonPrimitive("theValue"))
                    put("jaNei", JsonPrimitive(false))
                })
                put("data2", true)
                put("unrecognizedData", "N/A")
            }
            )
        }
        val riskNeed = jsonFlexible.decodeFromJsonElement(RiskNeed.serializer(), riskneedJson)
        assertNull(riskNeed.tilleggsdata?.get("notThere"))
        assertEquals("theValue", riskNeed.tilleggsdata?.get("data1")?.jsonObject?.get("theKey")?.jsonPrimitive?.content)
        assertEquals(false, riskNeed.tilleggsdata?.get("data1")?.jsonObject?.get("jaNei")?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, riskNeed.tilleggsdata?.get("data2")?.jsonPrimitive?.booleanOrNull)

        @Serializable
        data class Data1(
            val theKey: String,
            val jaNei: Boolean,
        )
        @Serializable
        data class OurData(
            val data1: Data1,
            val data2: Boolean,
        )

        val tillegg = riskNeed.tilleggsdata(OurData.serializer())
        assertNotNull(tillegg)
        assertEquals("theValue", tillegg.data1.theKey)
        assertEquals(false, tillegg.data1.jaNei)
        assertEquals(true, tillegg.data2)

        @Serializable
        data class FeilTillegg(
            val something: Int
        )

        assertNull(riskNeed.tilleggsdata(FeilTillegg.serializer()), "default is to return null instead of throwing exception")

        assertThrows<SerializationException> {
            riskNeed.tilleggsdata(FeilTillegg.serializer(), true)
        }

        @Serializable
        data class OptionalTillegg(
            val optionalString: String? = null,
            val optionalInt: Int? = null,
            val optionalBool: Boolean? = null,
        )

        val opt = riskNeed.tilleggsdata(OptionalTillegg.serializer())
        assertNotNull(opt)
        assertNull(opt.optionalString)
        assertNull(opt.optionalInt)
        assertNull(opt.optionalBool)
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
            put("sjekkresultater", buildJsonArray {
                add(Sjekkresultat(
                    id = "1",
                    begrunnelse = "something",
                    score = 1,
                    vekt = 1
                ).toJsonElement())
                add(Sjekkresultat(
                    id = "1",
                    begrunnelse = "showstopper",
                    score = 1,
                    vekt = 1,
                    kreverManuellBehandling = true
                ).toJsonElement())
            })
        }
        val vurderingsmelding = melding.tilVurderingsmelding()
        vurderingsmelding.apply {
            //assertTrue(this.erGammeltFormat())
            assertEquals("whatever", this.infotype)
            assertEquals(listOf("something", "showstopper"), this.begrunnelser())
            assertEquals(listOf("showstopper"), this.begrunnelserSomAleneKreverManuellBehandling())
        }
    }

    @Test
    fun `skal kunne deSerialisere RiskNeed med og uten optional verdier`() {
        buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("riskNeedId", "rid1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
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
            put("riskNeedId", "rid1")
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
            put("riskNeedId", "rid1")
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
            put("riskNeedId", "rid1")
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

    @Test
    fun tillegsbehov() {
        fun needMedTillegsbehov(tilleggsbehov: List<String>?) = buildJsonObject {
            put("type", "RiskNeed")
            put("vedtaksperiodeId", "1")
            put("riskNeedId", "rid1")
            put("organisasjonsnummer", "123456789")
            put("fnr", "01010100000")
            put("behovOpprettet", LocalDateTime.now().toString())
            put("iterasjon", 1)
            put("isRetry", true)
            put("retryCount", 2)
            if (tilleggsbehov != null) {
                put("tilleggsbehov", buildJsonArray {
                    tilleggsbehov.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("originalBehov", buildJsonObject {
                put("felt1", "verdi1")
            })
        }.jsonObject

        needMedTillegsbehov(null).apply {
            assertFalse(tilRiskNeed().harTilleggsbehov("BEHOV-1"))
        }
        needMedTillegsbehov(listOf("BEHOV-1")).apply {
            assertTrue(tilRiskNeed().harTilleggsbehov("BEHOV-1"))
        }
        needMedTillegsbehov(listOf("BEHOV-2")).apply {
            assertFalse(tilRiskNeed().harTilleggsbehov("BEHOV-1"))
        }
        needMedTillegsbehov(listOf("B1", "B2")).apply {
            assertFalse(tilRiskNeed().harTilleggsbehov("BEHOV-1"))
            assertTrue(tilRiskNeed().harTilleggsbehov("B1"))
            assertTrue(tilRiskNeed().harTilleggsbehov("B2"))
        }
    }
}

