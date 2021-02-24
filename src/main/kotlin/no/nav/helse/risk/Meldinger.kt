package no.nav.helse.risk

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import no.nav.helse.crypto.JWKSetHolder
import no.nav.helse.crypto.decryptFromJWE

private val jsonFlexible = JsonRisk

enum class Meldingstype {
    oppslagsresultat,
    RiskNeed,
    vurdering
}

const val vedtaksperiodeIdKey = "vedtaksperiodeId"
const val typeKey = "type"
const val infotypeKey = "infotype"
const val iterasjonKey = "iterasjon"
const val dataKey = "data"

@Serializable
data class RiskNeed(
    val vedtaksperiodeId: String,
    val organisasjonsnummer: String,
    val fnr: String,
    val behovOpprettet: String,
    val iterasjon: Int,
    val periodetype: String? = null,
    val originalBehov: JsonObject? = null,
    val type: String = "RiskNeed",
    val isRetry: Boolean? = null,
    val retryCount: Int? = null
)

class Oppslagtype<T>(val infotype: String, val serializer: DeserializationStrategy<T>)

fun JsonObject.tilRiskNeed(): RiskNeed =
    jsonFlexible.decodeFromJsonElement(RiskNeed.serializer(), this)

fun List<JsonObject>.finnRiskNeed(): RiskNeed? =
    this.find { it[typeKey]?.jsonPrimitive?.content == Meldingstype.RiskNeed.name }?.tilRiskNeed()

fun List<JsonObject>.finnOppslagsresultat(infotype: String): JsonElement? {
    val kandidat = this.find {
        it[typeKey]?.jsonPrimitive?.contentOrNull == Meldingstype.oppslagsresultat.name && it[infotypeKey]?.jsonPrimitive?.contentOrNull == infotype
    }
    return if (kandidat == null) null else kandidat.jsonObject[dataKey]
}

fun <T>List<JsonObject>.finnOppslagsresultat(oppslagstype: Oppslagtype<T>): T? =
    this.finnOppslagsresultat(oppslagstype.infotype)?.let { jsonFlexible.decodeFromJsonElement(oppslagstype.serializer, it) }

fun <T>List<JsonObject>.finnPaakrevdOppslagsresultat(oppslagstype: Oppslagtype<T>): T =
    this.finnOppslagsresultat(oppslagstype.infotype)?.let { jsonFlexible.decodeFromJsonElement(oppslagstype.serializer, it) }
        ?:error("Mangler oppslagsresultat med infotype=${oppslagstype.infotype}")


fun List<JsonObject>.finnUnikVedtaksperiodeId() : String =
    this.let { meldinger ->
        meldinger.first()[vedtaksperiodeIdKey]!!.jsonPrimitive.content.apply {
            meldinger.forEach {
                val neste = it[vedtaksperiodeIdKey]!!.jsonPrimitive.content
                if (neste != this) throw IllegalArgumentException("ulik id: $neste != $this")
            }
        }
    }


@Serializable
data class Vurderingsmelding(
    val type: String = Meldingstype.vurdering.name,
    val infotype: String,
    val vedtaksperiodeId: String,
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>,
    val begrunnelserSomAleneKreverManuellBehandling: List<String>? = null,
    val passerteSjekker: List<String>? = null, // TODO: nullable inntil alle tjenester er migrert
    val metadata: Map<String,String>? = null
)

fun JsonObject.tilVurderingsmelding(): Vurderingsmelding =
    jsonFlexible.decodeFromJsonElement(Vurderingsmelding.serializer(), this)


internal fun JsonObject.decryptIfEncrypted(decryptionJWKS: JWKSetHolder?): JsonObject {
    return try {
        if (decryptionJWKS != null
            && this.containsKey(dataKey)
            && this[dataKey]?.jsonPrimitive?.contentOrNull != null
            && this[dataKey]!!.jsonPrimitive.content.startsWith("ey")) {
            val decrypted = JsonElement.decryptFromJWE(this[dataKey]!!.jsonPrimitive.content, decryptionJWKS)
            JsonObject(this.toMutableMap().also { newContent -> newContent[dataKey] = decrypted })
        } else {
            this
        }
    } catch (exceptionBecauseDataElementIsNotAStringAndThusNotJWE: IllegalArgumentException) {
        this
    }
}