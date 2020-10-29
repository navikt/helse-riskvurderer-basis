package no.nav.helse.risk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val jsonFlexible = Json(JsonConfiguration.Stable.copy(
    ignoreUnknownKeys = true
))

const val typeOppslagsresultat = "oppslagsresultat"
const val typeRiskNeed = "RiskNeed"
const val vedtaksperiodeIdKey = "vedtaksperiodeId"
const val typeKey = "type"
const val typeVurdering = "vurdering"
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
    val originalBehov: JsonObject? = null,
    val type: String = "RiskNeed",
    val isRetry: Boolean? = null,
    val retryCount: Int? = null
)

fun JsonObject.tilRiskNeed(): RiskNeed =
    jsonFlexible.fromJson(RiskNeed.serializer(), this)

fun List<JsonObject>.finnRiskNeed(): RiskNeed? =
    this.find { it[typeKey]?.content == typeRiskNeed }?.tilRiskNeed()

fun List<JsonObject>.finnOppslagsresultat(infotype: String): JsonElement? {
    val kandidat = this.find {
        it[typeKey]?.contentOrNull == typeOppslagsresultat && it[infotypeKey]?.contentOrNull == infotype
    }
    return if (kandidat == null) null else kandidat.jsonObject[dataKey]
}

fun List<JsonObject>.finnUnikVedtaksperiodeId() : String =
    this.let { meldinger ->
        meldinger.first()[vedtaksperiodeIdKey]!!.content.apply {
            meldinger.forEach {
                val neste = it[vedtaksperiodeIdKey]!!.content
                if (neste != this) throw IllegalArgumentException("ulik id: $neste != $this")
            }
        }
    }


@Serializable
data class Vurderingsmelding(
    val type: String = typeVurdering,
    val infotype: String,
    val vedtaksperiodeId: String,
    val score: Int,
    val vekt: Int,
    val begrunnelser: List<String>,
    val begrunnelserSomAleneKreverManuellBehandling: List<String>? = null,
    val passerteSjekker: List<String>? = null // TODO: nullable inntil alle tjenester er migrert
)

fun JsonObject.tilVurderingsmelding(): Vurderingsmelding =
    jsonFlexible.fromJson(Vurderingsmelding.serializer(), this)