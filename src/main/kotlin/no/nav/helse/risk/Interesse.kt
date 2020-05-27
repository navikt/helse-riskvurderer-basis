package no.nav.helse.risk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.content
import kotlinx.serialization.json.intOrNull

data class Interesse(
    val type: String,
    val infotype: String? = null,
    val iterasjon: Int? = null
) {
    companion object {
        val riskNeed:Interesse = Interesse(type = typeRiskNeed)
        fun riskNeed(iterasjon: Int) = Interesse(type = typeRiskNeed, iterasjon = iterasjon)
        fun oppslagsresultat(infotype: String) = Interesse(type = typeOppslagsresultat, infotype = infotype)
    }
}

fun List<Pair<String, String?>>.tilInteresser() =
    this.map {
        Interesse(it.first, it.second, null)
    }

internal fun JsonObject.tilfredsstillerInteresser(interesser: List<Interesse>): Boolean {
    interesser.forEach {
        if (it.type == this[typeKey]?.content &&
            (it.infotype == null || (it.infotype == this[infotypeKey]?.content)) &&
            (it.iterasjon == null || (it.iterasjon == this[iterasjonKey]?.intOrNull)))
            return true
    }
    return false
}