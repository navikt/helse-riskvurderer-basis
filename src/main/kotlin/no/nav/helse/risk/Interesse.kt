package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class Interesse internal constructor(
    val type: String,
    val infotype: String? = null,
    val iterasjonsMatcher: ((Int) -> Boolean)? = null
) {
    companion object {
        val riskNeed:Interesse = Interesse(type = Meldingstype.RiskNeed.name)
        fun riskNeed(iterasjon: Int) = Interesse(type = Meldingstype.RiskNeed.name, iterasjonsMatcher = {
            it == iterasjon
        })
        fun riskNeedMedMinimum(iterasjon: Int) = Interesse(type = Meldingstype.RiskNeed.name, iterasjonsMatcher = {
            it >= iterasjon
        })
        fun oppslagsresultat(infotype: String) = Interesse(type = Meldingstype.oppslagsresultat.name, infotype = infotype)
        fun oppslagsresultat(oppslagstype: Oppslagtype<*>) = oppslagsresultat(infotype = oppslagstype.infotype)
        fun vurdering(infotype: String) = Interesse(type = Meldingstype.vurdering.name, infotype = infotype)
    }
}

fun List<Pair<String, String?>>.tilInteresser() =
    this.map {
        Interesse(it.first, it.second, null)
    }

private fun Int?.matcherIterasjon(interesse: Interesse) : Boolean {
    if (interesse.iterasjonsMatcher == null) return true
    if (this == null) return false
    return interesse.iterasjonsMatcher.invoke(this)
}

internal fun JsonObject.tilfredsstillerInteresser(interesser: List<Interesse>): Boolean {
    interesser.forEach { interesse ->
        if (interesse.type == this[typeKey]?.jsonPrimitive?.content &&
            (interesse.infotype == null || (interesse.infotype == this[infotypeKey]?.jsonPrimitive?.content)) &&
            (this[iterasjonKey]?.jsonPrimitive?.intOrNull.matcherIterasjon(interesse)))
            return true
    }
    return false
}